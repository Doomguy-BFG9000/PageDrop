-- PageDrop abuse caps
-- Run after 003_launch_hardening.sql, in the Supabase SQL editor. Safe to re-run.
-- Re-run it (after 003) whenever 003 or 001 is re-run: 003 recreates the 12-argument
-- create_job_with_quota(), which this file drops again.
--
-- Adds three caps on top of the per-user limits, all checked in the same transaction
-- that inserts the job (create_job_with_quota), so concurrent requests cannot overshoot:
--   1. Global queue cap: at most c_max_active_jobs jobs pending / processing / (recently)
--      awaiting_upload  -> {"error": "server_busy"}        (create-job answers 503)
--   2. Global upload bytes per UTC day: c_max_daily_bytes   -> {"error": "daily_capacity"} (503)
--      Tracked in public.daily_totals with INSERT .. ON CONFLICT .. DO UPDATE .. WHERE guard.
--   3. Optional per-client-IP jobs per UTC day: c_max_ip_daily_jobs -> {"error": "ip_limit"} (429)
--      Only a salted SHA-256 of the IP (computed by create-job) is stored, never the IP.
--      Skipped when create-job passes no hash (IP_HASH_SALT unset or no client IP header).
-- Limits are constants in the function body below; change them and re-run this file.

-- ── Tables ──────────────────────────────────────────────────────────────────

create table if not exists public.daily_totals (
  date date primary key,
  job_count bigint not null default 0,
  upload_bytes bigint not null default 0
);

create table if not exists public.ip_rate_limits (
  date date not null,
  ip_hash text not null,
  job_count int not null default 0,
  primary key (date, ip_hash)
);

alter table public.daily_totals enable row level security;
alter table public.ip_rate_limits enable row level security;  -- no policies: service role only
revoke all on public.daily_totals from public, anon, authenticated;
revoke all on public.ip_rate_limits from public, anon, authenticated;
grant all on public.daily_totals to service_role;
grant all on public.ip_rate_limits to service_role;

-- ── create_job_with_quota with the caps ─────────────────────────────────────

-- The old signature (003) would be ambiguous next to the new one for named-argument calls.
drop function if exists public.create_job_with_quota(
  uuid, uuid, text, text, text, text, text, text, text, text, bigint, bigint
);

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

  insert into public.profiles (id) values (p_user_id) on conflict (id) do nothing;
  select daily_job_limit, storage_quota_bytes into v_limit, v_quota
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
