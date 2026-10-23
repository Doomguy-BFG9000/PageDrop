-- PageDrop upload limits and default privileges
-- Run after 004_abuse_caps.sql, in the Supabase SQL editor (or psql as the postgres role).
-- Safe to re-run. Re-run it whenever 004 is re-run: 004 recreates
-- create_job_with_quota() without the per-user active-job cap added here.
--
-- 1. Real upload sizes: a client may only move a job from awaiting_upload to pending when
--    the objects actually stored in the `sources` bucket (storage.objects.metadata->>'size')
--    exist and are no larger than the declared file_size_bytes / input_cover_size_bytes.
--    That check lives in guard_client_job_update() (003); this file refuses to run if it
--    is missing rather than defining a second copy that could drift.
-- 2. The artifacts bucket gets a hard per-object limit of 200 MB (the worker refuses
--    larger outputs too).
-- 3. Per-user cap on active jobs (pending, processing, awaiting_upload younger than 1 h):
--    profiles.max_active_jobs, default 2, enforced in create_job_with_quota()
--    -> {"error": "too_many_active_jobs"} (create-job answers 429).
--    The worker deletes jobs still awaiting_upload after 1 hour, files first.
-- 4. Supabase's default privileges grant every new table, sequence and function in
--    `public` to anon and authenticated (and functions to PUBLIC). They are revoked for
--    objects the postgres role creates from now on, so a future migration that forgets
--    its REVOKE does not silently expose a table or a SECURITY DEFINER function over the
--    API. Objects created by 001-004 keep the grants they already have (the app relies on
--    SELECT on jobs/profiles/rate_limits/storage_usage and UPDATE (status) on jobs, all
--    filtered by RLS); new objects need explicit grants.

-- ── Preconditions ───────────────────────────────────────────────────────────

do $$
begin
  if not exists (
    select 1 from pg_trigger
    where tgname = 'guard_client_job_update' and tgrelid = 'public.jobs'::regclass and not tgisinternal
  ) then
    raise exception '005 needs 003_launch_hardening.sql: trigger guard_client_job_update is missing';
  end if;
  if to_regprocedure('public.create_job_with_quota(uuid, uuid, text, text, text, text, text, text, text, text, bigint, bigint, text)') is null then
    raise exception '005 needs 004_abuse_caps.sql: create_job_with_quota(..., p_ip_hash) is missing';
  end if;
  -- The size check must compare against the real stored objects.
  if position('storage.objects' in pg_get_functiondef('public.guard_client_job_update()'::regprocedure)) = 0 then
    raise exception '005 expects guard_client_job_update() from 003 (size check against storage.objects)';
  end if;
end
$$;

-- ── Artifacts bucket size limit ─────────────────────────────────────────────

update storage.buckets set file_size_limit = 209715200 where id = 'artifacts';  -- 200 MB

-- ── Per-user active job cap ─────────────────────────────────────────────────

alter table public.profiles
  add column if not exists max_active_jobs int not null default 2;

create or replace function public.create_job_with_quota(
  p_user_id uuid,
  p_job_id uuid,
  p_job_type text,
  p_source_storage_path text,
  p_input_cover_storage_path text,
  p_source_url text,
  p_target_format text,
  p_target_profile text,
  p_title text,
  p_author text,
  p_file_size_bytes bigint,
  p_input_cover_size_bytes bigint,
  p_ip_hash text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  -- Keep in sync with MAX_FILE_SIZE / MAX_COVER_SIZE in create-job and the
  -- sources bucket's file_size_limit in 003.
  c_max_file_bytes constant bigint := 104857600;     -- 100 MB
  c_max_cover_bytes constant bigint := 15728640;     -- 15 MB
  -- Abuse caps (migration 004).
  c_max_active_jobs constant int := 200;             -- pending + processing + fresh awaiting_upload
  c_awaiting_window constant interval := interval '1 hour'; -- abandoned uploads stop counting after this
  c_max_daily_bytes constant bigint := 5368709120;   -- 5 GB declared uploads per UTC day, all users
  c_max_ip_daily_jobs constant int := 30;            -- jobs per client IP per UTC day
  v_today date := (now() at time zone 'utc')::date;
  v_limit int;
  v_quota bigint;
  v_user_max_active int;
  v_used bigint;
  v_count int;
  v_active int;
  v_bytes bigint;
  v_status text;
begin
  if p_job_type not in ('convert', 'article') then
    return jsonb_build_object('ok', false, 'error', 'invalid_job_type');
  end if;

  if p_job_type = 'convert' then
    if p_source_storage_path is null or p_file_size_bytes is null or p_file_size_bytes <= 0 then
      return jsonb_build_object('ok', false, 'error', 'file_size_required');
    end if;
    if p_file_size_bytes > c_max_file_bytes then
      return jsonb_build_object('ok', false, 'error', 'file_too_large', 'max_bytes', c_max_file_bytes);
    end if;
    if p_input_cover_storage_path is not null then
      if p_input_cover_size_bytes is null or p_input_cover_size_bytes <= 0 then
        return jsonb_build_object('ok', false, 'error', 'cover_size_required');
      end if;
      if p_input_cover_size_bytes > c_max_cover_bytes then
        return jsonb_build_object('ok', false, 'error', 'cover_too_large', 'max_bytes', c_max_cover_bytes);
      end if;
    end if;
  elsif p_source_url is null or p_source_url !~* '^https?://' or length(p_source_url) > 2048 then
    return jsonb_build_object('ok', false, 'error', 'invalid_url');
  end if;

  -- Locks the user's profile row: this user's concurrent requests serialise here, so the
  -- per-user counts below cannot be raced.
  insert into public.profiles (id) values (p_user_id) on conflict (id) do nothing;
  select daily_job_limit, storage_quota_bytes, max_active_jobs
    into v_limit, v_quota, v_user_max_active
  from public.profiles where id = p_user_id
  for update;

  if p_job_type = 'convert' then
    select coalesce(sum(coalesce(file_size_bytes, 0) + coalesce(input_cover_size_bytes, 0)), 0)
      into v_used
    from public.jobs where user_id = p_user_id;
    if v_used + p_file_size_bytes + coalesce(p_input_cover_size_bytes, 0) > v_quota then
      return jsonb_build_object('ok', false, 'error', 'storage_quota', 'quota_bytes', v_quota);
    end if;
  end if;

  -- Per-user active jobs (migration 005).
  select count(*) into v_active
  from public.jobs
  where user_id = p_user_id
    and (status in ('pending', 'processing')
         or (status = 'awaiting_upload' and created_at > now() - c_awaiting_window));
  if v_active >= v_user_max_active then
    return jsonb_build_object('ok', false, 'error', 'too_many_active_jobs', 'limit', v_user_max_active);
  end if;

  if v_limit <= 0 then
    return jsonb_build_object('ok', false, 'error', 'daily_limit', 'limit', v_limit);
  end if;

  -- Global queue cap. The advisory lock is held until this transaction ends, so two
  -- requests cannot both see "199 active" and both insert. Jobs only leave the active
  -- set outside this lock, which can only make the count smaller.
  perform pg_advisory_xact_lock(hashtext('pagedrop.create_job.active_cap'));
  select count(*) into v_active
  from public.jobs
  where status in ('pending', 'processing')
     or (status = 'awaiting_upload' and created_at > now() - c_awaiting_window);
  if v_active >= c_max_active_jobs then
    return jsonb_build_object('ok', false, 'error', 'server_busy');
  end if;

  v_bytes := case when p_job_type = 'convert'
    then p_file_size_bytes + coalesce(p_input_cover_size_bytes, 0) else 0 end;

  -- The counters below are written one after another; if a later one refuses, the
  -- exception rolls this block back so a refused job is never counted anywhere.
  begin
    insert into public.rate_limits as rl (user_id, date, job_count)
    values (p_user_id, v_today, 1)
    on conflict (user_id, date)
    do update set job_count = rl.job_count + 1
    where rl.job_count < v_limit
    returning rl.job_count into v_count;
    if v_count is null then
      raise exception 'daily_limit' using errcode = 'PD429';
    end if;

    if p_ip_hash is not null and length(p_ip_hash) between 16 and 128 then
      v_active := null;
      insert into public.ip_rate_limits as ip (date, ip_hash, job_count)
      values (v_today, p_ip_hash, 1)
      on conflict (date, ip_hash)
      do update set job_count = ip.job_count + 1
      where ip.job_count < c_max_ip_daily_jobs
      returning ip.job_count into v_active;
      if v_active is null then
        raise exception 'ip_limit' using errcode = 'PD429';
      end if;
    end if;

    v_active := null;
    insert into public.daily_totals as dt (date, job_count, upload_bytes)
    values (v_today, 1, v_bytes)
    on conflict (date)
    do update set job_count = dt.job_count + 1, upload_bytes = dt.upload_bytes + v_bytes
    where dt.upload_bytes + v_bytes <= c_max_daily_bytes
    returning 1 into v_active;
    if v_active is null then
      raise exception 'daily_capacity' using errcode = 'PD503';
    end if;
  exception
    when sqlstate 'PD429' or sqlstate 'PD503' then
      return jsonb_build_object('ok', false, 'error', sqlerrm, 'limit', v_limit);
  end;

  v_status := case when p_job_type = 'convert' then 'awaiting_upload' else 'pending' end;
  insert into public.jobs (
    id, user_id, status, job_type, source_storage_path, input_cover_storage_path,
    source_url, target_format, target_profile, title, author,
    file_size_bytes, input_cover_size_bytes
  ) values (
    p_job_id, p_user_id, v_status, p_job_type,
    case when p_job_type = 'convert' then p_source_storage_path end,
    case when p_job_type = 'convert' then p_input_cover_storage_path end,
    case when p_job_type = 'article' then p_source_url end,
    p_target_format, p_target_profile, p_title, p_author,
    case when p_job_type = 'convert' then p_file_size_bytes end,
    case when p_job_type = 'convert' then p_input_cover_size_bytes end
  );

  return jsonb_build_object('ok', true, 'job_id', p_job_id, 'status', v_status, 'jobs_today', v_count);
end;
$$;

revoke execute on function public.create_job_with_quota(
  uuid, uuid, text, text, text, text, text, text, text, text, bigint, bigint, text
) from public, anon, authenticated;
grant execute on function public.create_job_with_quota(
  uuid, uuid, text, text, text, text, text, text, text, text, bigint, bigint, text
) to service_role;

-- ── Default privileges ──────────────────────────────────────────────────────

-- Only affects objects created by the postgres role (the SQL editor and psql) from now
-- on. Supabase also keeps default privileges for supabase_admin, which only that role
-- can change; nothing in these migrations is created by it.
alter default privileges for role postgres in schema public
  revoke all on tables from anon, authenticated, public;
alter default privileges for role postgres in schema public
  revoke all on sequences from anon, authenticated, public;
alter default privileges for role postgres in schema public
  revoke all on functions from anon, authenticated, public;
-- EXECUTE for PUBLIC on new functions is a built-in *global* default, which a per-schema
-- REVOKE cannot remove (per-schema default privileges only add to the global ones), and
-- PUBLIC includes anon and authenticated. So this one is global: functions the postgres
-- role creates in any schema are no longer executable by everyone. If you later create an
-- extension or helper function that clients must call, GRANT EXECUTE on it explicitly.
alter default privileges for role postgres
  revoke execute on functions from public;
-- The worker and Edge Functions (service_role) keep working on new objects.
alter default privileges for role postgres in schema public
  grant all on tables to service_role;
alter default privileges for role postgres in schema public
  grant all on sequences to service_role;
alter default privileges for role postgres in schema public
  grant execute on functions to service_role;

-- Trigger functions never need EXECUTE for the triggering role; nobody should call them
-- directly either.
revoke execute on function public.handle_new_user() from public, anon, authenticated;
revoke execute on function public.guard_client_job_update() from public, anon, authenticated;
