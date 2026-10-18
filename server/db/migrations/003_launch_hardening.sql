-- PageDrop launch hardening
-- Run after 001_schema.sql and 002_conversion_cover.sql, in the Supabase SQL editor.
-- Safe to re-run.
--
-- 1. Clients (anon / authenticated roles) can no longer insert job rows or change any
--    job column except `status`, and only from awaiting_upload to pending once the
--    declared uploads exist in Storage at no more than their declared size.
-- 2. Daily job limits and upload size limits are enforced atomically by
--    create_job_with_quota(), which the create-job Edge Function calls.
-- 3. Profiles, counters and quota helpers are no longer writable or callable by clients.
-- 4. Buckets are private, the sources bucket has a hard per-object size cap, and
--    clients may only upload to the exact paths create-job issued for a job that is
--    still awaiting upload.
--
-- Retention (deleting files and job rows after 24 hours) runs in the worker; see
-- server/README.md.

-- ── Jobs: new column and value constraints ──────────────────────────────────

alter table public.jobs
  add column if not exists input_cover_size_bytes bigint;

-- Must match ALLOWED_TARGET_FORMATS in create-job and the worker. NOT VALID keeps
-- older rows readable; every new or updated row is checked.
alter table public.jobs drop constraint if exists jobs_target_format_check;
alter table public.jobs
  add constraint jobs_target_format_check
  check (target_format in (
    'azw3', 'epub', 'mobi', 'docx', 'fb2', 'htmlz', 'kepub', 'lit', 'lrf', 'pdb',
    'pdf', 'pmlz', 'rb', 'rtf', 'snb', 'tcr', 'txt', 'txtz', 'zip'
  )) not valid;

-- ── Jobs: client privileges and RLS ─────────────────────────────────────────

-- Jobs are created only by the create-job Edge Function (service role).
revoke insert, update, delete, truncate on public.jobs from anon, authenticated;
-- The app's only write: PATCH {"status": "pending"} after uploading.
grant update (status) on public.jobs to authenticated;

drop policy if exists "Users can insert own jobs" on public.jobs;
drop policy if exists "Users can update own jobs" on public.jobs;
drop policy if exists "Users can mark own uploaded jobs pending" on public.jobs;
create policy "Users can mark own uploaded jobs pending"
  on public.jobs for update
  to authenticated
  using (auth.uid() = user_id and status = 'awaiting_upload')
  with check (auth.uid() = user_id and status = 'pending');

-- Rechecks the transition even if a grant or policy is loosened later, and refuses
-- to queue a convert job whose uploads are missing or larger than declared.
create or replace function public.guard_client_job_update()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_size bigint;
begin
  if coalesce(auth.role(), '') not in ('anon', 'authenticated') then
    return new;  -- service role (worker, Edge Functions) or a direct SQL session
  end if;

  if old.status <> 'awaiting_upload' or new.status <> 'pending'
     or (to_jsonb(new) - 'status') is distinct from (to_jsonb(old) - 'status') then
    raise exception 'clients may only move a job from awaiting_upload to pending'
      using errcode = '42501';
  end if;

  if old.job_type = 'convert' then
    select (o.metadata->>'size')::bigint into v_size
    from storage.objects o
    where o.bucket_id = 'sources' and o.name = old.source_storage_path;
    if v_size is null then
      raise exception 'source file has not been uploaded' using errcode = 'P0001';
    end if;
    if v_size > coalesce(old.file_size_bytes, 0) then
      raise exception 'uploaded source is larger than declared' using errcode = 'P0001';
    end if;

    if old.input_cover_storage_path is not null then
      v_size := null;
      select (o.metadata->>'size')::bigint into v_size
      from storage.objects o
      where o.bucket_id = 'sources' and o.name = old.input_cover_storage_path;
      if v_size is null then
        raise exception 'cover file has not been uploaded' using errcode = 'P0001';
      end if;
      if v_size > coalesce(old.input_cover_size_bytes, 0) then
        raise exception 'uploaded cover is larger than declared' using errcode = 'P0001';
      end if;
    end if;
  end if;

  return new;
end;
$$;

drop trigger if exists guard_client_job_update on public.jobs;
create trigger guard_client_job_update
  before update on public.jobs
  for each row execute function public.guard_client_job_update();

-- ── Profiles, counters, usage: read-only for clients ────────────────────────

-- Users could previously raise their own daily_job_limit / storage_quota_bytes / tier.
drop policy if exists "Users can update own profile" on public.profiles;
revoke insert, update, delete, truncate on public.profiles from anon, authenticated;
revoke insert, update, delete, truncate on public.rate_limits from anon, authenticated;
revoke insert, update, delete, truncate on public.storage_usage from anon, authenticated;

alter function public.handle_new_user() set search_path = public, pg_temp;

-- Security definer and callable over PostgREST by default: anyone could burn another
-- user's daily quota with it. create_job_with_quota() replaces it.
revoke execute on function public.increment_rate_limit(uuid) from public, anon, authenticated;
grant execute on function public.increment_rate_limit(uuid) to service_role;
alter function public.increment_rate_limit(uuid) set search_path = public, pg_temp;

-- ── Atomic quota check + job creation ───────────────────────────────────────

-- Locks the user's profile row so concurrent requests serialise, checks sizes and the
-- storage quota, takes one unit of today's (UTC) job allowance, and inserts the job.
-- Any rejection happens before a write, so nothing is counted for a refused job.
-- Returns {"ok": true, "job_id", "status"} or {"ok": false, "error", ...}.
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
  p_input_cover_size_bytes bigint
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  -- Keep in sync with MAX_FILE_SIZE / MAX_COVER_SIZE in create-job and the
  -- sources bucket's file_size_limit below.
  c_max_file_bytes constant bigint := 104857600;   -- 100 MB
  c_max_cover_bytes constant bigint := 15728640;   -- 15 MB
  v_today date := (now() at time zone 'utc')::date;
  v_limit int;
  v_quota bigint;
  v_used bigint;
  v_count int;
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
    -- Job rows (and their files) are deleted after 24 hours, so this is what the
    -- user currently has stored.
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
  insert into public.rate_limits as rl (user_id, date, job_count)
  values (p_user_id, v_today, 1)
  on conflict (user_id, date)
  do update set job_count = rl.job_count + 1
  where rl.job_count < v_limit
  returning rl.job_count into v_count;
  if v_count is null then
    return jsonb_build_object('ok', false, 'error', 'daily_limit', 'limit', v_limit);
  end if;

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
  uuid, uuid, text, text, text, text, text, text, text, text, bigint, bigint
) from public, anon, authenticated;
grant execute on function public.create_job_with_quota(
  uuid, uuid, text, text, text, text, text, text, text, text, bigint, bigint
) to service_role;

-- ── Storage: private buckets, size cap, upload only to issued paths ─────────

update storage.buckets set public = false where id in ('sources', 'artifacts');
update storage.buckets set file_size_limit = 104857600 where id = 'sources';  -- 100 MB

-- Previously any path under the user's folder was writable at any time, which made the
-- bucket free file hosting and let a source be swapped after the job was queued.
drop policy if exists "Users can upload own sources" on storage.objects;
create policy "Users can upload own sources"
  on storage.objects for insert
  to authenticated
  with check (
    bucket_id = 'sources'
    and exists (
      select 1 from public.jobs j
      where j.user_id = auth.uid()
        and j.status = 'awaiting_upload'
        and storage.objects.name in (j.source_storage_path, j.input_cover_storage_path)
    )
  );

-- The app uploads with x-upsert, which needs UPDATE on a retry.
drop policy if exists "Users can replace own pending uploads" on storage.objects;
create policy "Users can replace own pending uploads"
  on storage.objects for update
  to authenticated
  using (
    bucket_id = 'sources'
    and exists (
      select 1 from public.jobs j
      where j.user_id = auth.uid()
        and j.status = 'awaiting_upload'
        and storage.objects.name in (j.source_storage_path, j.input_cover_storage_path)
    )
  );
