-- Behaviour tests for 003_launch_hardening.sql. Run via run.sh, which loads the
-- Supabase stubs and all migrations into a throwaway database first.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

create schema test;
grant usage on schema test to public;

create function test.ok(cond boolean, msg text) returns void language plpgsql as $$
begin
  if not coalesce(cond, false) then raise exception 'FAIL: %', msg; end if;
  raise notice 'ok - %', msg;
end $$;

create function test.fails(stmt text, code text, msg text) returns void language plpgsql as $$
begin
  begin
    execute stmt;
  exception when others then
    if sqlstate = code then raise notice 'ok - %', msg; return; end if;
    raise exception 'FAIL: % (expected %, got % %)', msg, code, sqlstate, sqlerrm;
  end;
  raise exception 'FAIL: % (statement succeeded)', msg;
end $$;

-- Calls create_job_with_quota with the shape create-job uses.
create function test.create_convert(u uuid, j uuid, size bigint, cover_size bigint default null)
returns jsonb language sql as $$
  select public.create_job_with_quota(
    u, j, 'convert', u || '/' || j || '/book.epub',
    case when cover_size is not null then u || '/' || j || '/cover_c.jpg' end,
    null, 'mobi', 'kindle-stock', 'T', 'A', size, cover_size)
$$;

create function test.as_user(u uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', u, 'role', 'authenticated')::text, true)
$$;

grant execute on all functions in schema test to public;

insert into auth.users values
  ('aaaaaaaa-0000-0000-0000-000000000001'),  -- A
  ('aaaaaaaa-0000-0000-0000-000000000002'),  -- B
  ('aaaaaaaa-0000-0000-0000-000000000003');  -- C (used by run.sh's concurrency test)
update public.profiles set daily_job_limit = 3 where id = 'aaaaaaaa-0000-0000-0000-000000000001';
update public.profiles set storage_quota_bytes = 5000 where id = 'aaaaaaaa-0000-0000-0000-000000000002';
update public.profiles set daily_job_limit = 5 where id = 'aaaaaaaa-0000-0000-0000-000000000003';

-- ── Quotas (as the service role, like create-job) ──────────────────────────
begin;
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001', 1000) ->> 'status') = 'awaiting_upload',
  'convert job starts awaiting_upload');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000002', 1000, 500) ->> 'ok')::boolean,
  'second job under the daily limit is accepted');
select test.ok((public.create_job_with_quota('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000003', 'article', null, null,
  'https://example.com/a', 'mobi', 'kindle-stock', null, null, null, null) ->> 'status') = 'pending',
  'article job starts pending and counts toward the limit');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000004', 1000) ->> 'error') = 'daily_limit',
  'fourth job is rejected at daily_job_limit = 3');
select test.ok((select job_count from public.rate_limits where user_id = 'aaaaaaaa-0000-0000-0000-000000000001') = 3,
  'rejected job does not increment the counter');
select test.ok((select count(*) from public.jobs where user_id = 'aaaaaaaa-0000-0000-0000-000000000001') = 3,
  'rejected job inserts no row');

select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', 104857601) ->> 'error') = 'file_too_large',
  'source over 100 MB is rejected');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', 100, 15728641) ->> 'error') = 'cover_too_large',
  'cover over 15 MB is rejected');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', null) ->> 'error') = 'file_size_required',
  'convert job without a declared size is rejected');
select test.ok((public.create_job_with_quota('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', 'article', null, null,
  'file:///etc/passwd', 'mobi', 'kindle-stock', null, null, null, null) ->> 'error') = 'invalid_url',
  'article job with a non-http URL is rejected');
select test.ok(not exists (select 1 from public.rate_limits where user_id = 'aaaaaaaa-0000-0000-0000-000000000002'),
  'size rejections do not use up the daily allowance');

select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', 4000) ->> 'ok')::boolean,
  'upload within the storage quota is accepted');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000002', 2000) ->> 'error') = 'storage_quota',
  'upload past the storage quota is rejected');
select test.fails($$select public.create_job_with_quota('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000009', 'convert',
  'x/y/z', null, null, '../../etc', 'kindle-stock', null, null, 10, null)$$, '23514',
  'target_format outside the allow-list is rejected');
commit;

-- ── Client writes to jobs (as user A) ───────────────────────────────────────
begin;
set local role authenticated;
select test.as_user('aaaaaaaa-0000-0000-0000-000000000001');

select test.fails($$insert into public.jobs (user_id, job_type) values ('aaaaaaaa-0000-0000-0000-000000000001', 'convert')$$,
  '42501', 'clients cannot insert job rows');
select test.fails($$update public.jobs set source_storage_path = 'aaaaaaaa-0000-0000-0000-000000000002/22222222-0000-0000-0000-000000000001/book.epub'
  where id = '11111111-0000-0000-0000-000000000001'$$,
  '42501', 'clients cannot rewrite source_storage_path');
select test.fails($$update public.jobs set input_cover_storage_path = 'x' where id = '11111111-0000-0000-0000-000000000001'$$,
  '42501', 'clients cannot rewrite input_cover_storage_path');
select test.fails($$update public.jobs set result_storage_path = 'x' where id = '11111111-0000-0000-0000-000000000001'$$,
  '42501', 'clients cannot write result_storage_path');
select test.fails($$update public.jobs set status = 'pending' where id = '11111111-0000-0000-0000-000000000001'$$,
  'P0001', 'job cannot be queued before its source is uploaded');
select test.fails($$update public.jobs set status = 'complete' where id = '11111111-0000-0000-0000-000000000001'$$,
  '42501', 'awaiting_upload -> complete is refused');
select test.ok((select count(*) from public.jobs where user_id <> auth.uid()) = 0,
  'users cannot see other users'' jobs');
select test.fails($$update public.profiles set daily_job_limit = 100000 where id = auth.uid()$$,
  '42501', 'users cannot raise their own daily_job_limit');
select test.fails($$select public.increment_rate_limit('aaaaaaaa-0000-0000-0000-000000000002')$$,
  '42501', 'users cannot call increment_rate_limit');
select test.fails($$select test.create_convert(auth.uid(), gen_random_uuid(), 10)$$,
  '42501', 'users cannot call create_job_with_quota directly');
commit;

-- ── Storage uploads (as user A) ─────────────────────────────────────────────
begin;
set local role authenticated;
select test.as_user('aaaaaaaa-0000-0000-0000-000000000001');

select test.fails($$insert into storage.objects (bucket_id, name, metadata)
  values ('sources', 'aaaaaaaa-0000-0000-0000-000000000001/anything/movie.mkv', '{"size": 10}')$$,
  '42501', 'upload to a path create-job did not issue is refused');
select test.fails($$insert into storage.objects (bucket_id, name, metadata)
  values ('sources', 'aaaaaaaa-0000-0000-0000-000000000002/22222222-0000-0000-0000-000000000001/book.epub', '{"size": 10}')$$,
  '42501', 'upload to another user''s issued path is refused');
select test.fails($$insert into storage.objects (bucket_id, name, metadata)
  values ('artifacts', 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/output.mobi', '{"size": 10}')$$,
  '42501', 'clients cannot write to the artifacts bucket');

insert into storage.objects (bucket_id, name, metadata)
  values ('sources', 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/book.epub', '{"size": 1000}');
select test.ok(true, 'upload to the issued source path is allowed');
update public.jobs set status = 'pending' where id = '11111111-0000-0000-0000-000000000001';
select test.ok((select status from public.jobs where id = '11111111-0000-0000-0000-000000000001') = 'pending',
  'awaiting_upload -> pending succeeds once the source exists at its declared size');

update storage.objects set metadata = '{"size": 99999999}'
  where name = 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/book.epub';
select test.ok((select metadata ->> 'size' from storage.objects
  where name = 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/book.epub') = '1000',
  'source cannot be replaced after the job is queued');
update public.jobs set status = 'awaiting_upload' where id = '11111111-0000-0000-0000-000000000001';
select test.ok((select status from public.jobs where id = '11111111-0000-0000-0000-000000000001') = 'pending',
  'pending -> awaiting_upload is a no-op for clients');

-- Job 2 declared a 1000-byte source and a 500-byte cover.
insert into storage.objects (bucket_id, name, metadata)
  values ('sources', 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000002/book.epub', '{"size": 1000}');
select test.fails($$update public.jobs set status = 'pending' where id = '11111111-0000-0000-0000-000000000002'$$,
  'P0001', 'job with a declared cover cannot be queued before the cover is uploaded');
insert into storage.objects (bucket_id, name, metadata)
  values ('sources', 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000002/cover_c.jpg', '{"size": 501}');
select test.fails($$update public.jobs set status = 'pending' where id = '11111111-0000-0000-0000-000000000002'$$,
  'P0001', 'cover larger than declared blocks queueing');
commit;

begin;
set local role authenticated;
select test.as_user('aaaaaaaa-0000-0000-0000-000000000002');
-- B's job declared 4000 bytes; B uploads 4001.
insert into storage.objects (bucket_id, name, metadata)
  values ('sources', 'aaaaaaaa-0000-0000-0000-000000000002/22222222-0000-0000-0000-000000000001/book.epub', '{"size": 4001}');
select test.fails($$update public.jobs set status = 'pending' where id = '22222222-0000-0000-0000-000000000001'$$,
  'P0001', 'source larger than declared blocks queueing');
commit;

-- ── Service role is unaffected ──────────────────────────────────────────────
begin;
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
update public.jobs set status = 'processing' where id = '11111111-0000-0000-0000-000000000001';
update public.jobs set status = 'complete', result_storage_path = 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/output.mobi'
  where id = '11111111-0000-0000-0000-000000000001';
select test.ok((select status from public.jobs where id = '11111111-0000-0000-0000-000000000001') = 'complete',
  'worker can still move jobs through processing -> complete');
delete from public.jobs where id = '11111111-0000-0000-0000-000000000001';
select test.ok(true, 'worker can delete expired job rows');
commit;

-- ── Buckets ─────────────────────────────────────────────────────────────────
select test.ok(not exists (select 1 from storage.buckets where id in ('sources', 'artifacts') and public),
  'sources and artifacts buckets are private');
select test.ok((select file_size_limit from storage.buckets where id = 'sources') = 104857600,
  'sources bucket caps objects at 100 MB');
