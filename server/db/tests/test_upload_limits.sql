-- Behaviour tests for 005_upload_limits.sql (run by run.sh after test_abuse_caps.sql).
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

insert into auth.users values
  ('aaaaaaaa-0000-0000-0000-000000000005'),  -- E: default max_active_jobs = 2
  ('aaaaaaaa-0000-0000-0000-000000000006');  -- F: used by run.sh's concurrency test

-- ── Per-user active job cap ─────────────────────────────────────────────────
begin;
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select test.ok((select max_active_jobs from public.profiles where id = 'aaaaaaaa-0000-0000-0000-000000000005') = 2,
  'new profiles get max_active_jobs = 2');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000005', '55555555-0000-0000-0000-000000000001', 10) ->> 'ok')::boolean,
  'first active job is accepted');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000005', '55555555-0000-0000-0000-000000000002', 10) ->> 'ok')::boolean,
  'second active job is accepted');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000005', '55555555-0000-0000-0000-000000000003', 10) ->> 'error') = 'too_many_active_jobs',
  'third active job is refused with too_many_active_jobs');
select test.ok((select job_count from public.rate_limits where user_id = 'aaaaaaaa-0000-0000-0000-000000000005') = 2,
  'refused job does not use the daily allowance');
select test.ok((select count(*) from public.jobs where user_id = 'aaaaaaaa-0000-0000-0000-000000000005') = 2,
  'refused job inserts no row');

update public.jobs set status = 'complete' where id = '55555555-0000-0000-0000-000000000001';
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000005', '55555555-0000-0000-0000-000000000003', 10) ->> 'ok')::boolean,
  'a finished job frees a slot');
update public.jobs set created_at = now() - interval '2 hours' where id = '55555555-0000-0000-0000-000000000002';
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000005', '55555555-0000-0000-0000-000000000004', 10) ->> 'ok')::boolean,
  'awaiting_upload older than an hour (abandoned) does not count');
update public.jobs set status = 'processing' where id = '55555555-0000-0000-0000-000000000003';
select test.ok((public.create_job_with_quota('aaaaaaaa-0000-0000-0000-000000000005', gen_random_uuid(), 'article', null, null,
  'https://example.com/a', 'mobi', 'kindle-stock', null, null, null, null) ->> 'error') = 'too_many_active_jobs',
  'processing and awaiting_upload jobs count, article jobs are capped too');
delete from public.jobs where user_id = 'aaaaaaaa-0000-0000-0000-000000000005';
commit;

select test.fails($$set role authenticated; select test.as_user('aaaaaaaa-0000-0000-0000-000000000005');
  update public.profiles set max_active_jobs = 100 where id = auth.uid()$$,
  '42501', 'users cannot raise their own max_active_jobs');

-- ── Real upload sizes (guard from 003, required by 005) ─────────────────────
begin;
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select test.create_convert('aaaaaaaa-0000-0000-0000-000000000005', '55555555-0000-0000-0000-000000000010', 100, 50);
commit;

begin;
set local role authenticated;
select test.as_user('aaaaaaaa-0000-0000-0000-000000000005');
insert into storage.objects (bucket_id, name, metadata) values
  ('sources', 'aaaaaaaa-0000-0000-0000-000000000005/55555555-0000-0000-0000-000000000010/book.epub', '{"size": 100}');
select test.fails($$update public.jobs set status = 'pending' where id = '55555555-0000-0000-0000-000000000010'$$,
  'P0001', 'queueing is refused while the declared cover is missing from storage');
insert into storage.objects (bucket_id, name, metadata) values
  ('sources', 'aaaaaaaa-0000-0000-0000-000000000005/55555555-0000-0000-0000-000000000010/cover_c.jpg', '{"size": 51}');
select test.fails($$update public.jobs set status = 'pending' where id = '55555555-0000-0000-0000-000000000010'$$,
  'P0001', 'queueing is refused when the stored cover is larger than declared');
commit;

begin;
set local role service_role;
update storage.objects set metadata = '{"size": 50}'
  where name = 'aaaaaaaa-0000-0000-0000-000000000005/55555555-0000-0000-0000-000000000010/cover_c.jpg';
commit;

begin;
set local role authenticated;
select test.as_user('aaaaaaaa-0000-0000-0000-000000000005');
update public.jobs set status = 'pending' where id = '55555555-0000-0000-0000-000000000010';
select test.ok((select status from public.jobs where id = '55555555-0000-0000-0000-000000000010') = 'pending',
  'queueing succeeds when stored sizes match the declared sizes');
commit;
delete from public.jobs where user_id = 'aaaaaaaa-0000-0000-0000-000000000005';
delete from storage.objects where name like 'aaaaaaaa-0000-0000-0000-000000000005/%';

-- ── Buckets ─────────────────────────────────────────────────────────────────
select test.ok((select file_size_limit from storage.buckets where id = 'artifacts') = 209715200,
  'artifacts bucket caps objects at 200 MB');

-- ── Default privileges ──────────────────────────────────────────────────────
create table public.zz_new_table (id int);
create function public.zz_new_function() returns int language sql security definer as $$ select 1 $$;
select test.ok(not has_table_privilege('anon', 'public.zz_new_table', 'select')
  and not has_table_privilege('authenticated', 'public.zz_new_table', 'insert'),
  'new tables are not granted to anon / authenticated');
select test.ok(has_table_privilege('service_role', 'public.zz_new_table', 'select,insert,update,delete'),
  'new tables are still granted to service_role');
select test.ok(not has_function_privilege('anon', 'public.zz_new_function()', 'execute')
  and not has_function_privilege('authenticated', 'public.zz_new_function()', 'execute'),
  'new functions are not executable by anon / authenticated (nor PUBLIC)');
select test.ok(has_function_privilege('service_role', 'public.zz_new_function()', 'execute'),
  'new functions are executable by service_role');
drop table public.zz_new_table;
drop function public.zz_new_function();

select test.ok(has_table_privilege('authenticated', 'public.jobs', 'select')
  and has_column_privilege('authenticated', 'public.jobs', 'status', 'update')
  and has_table_privilege('authenticated', 'public.profiles', 'select'),
  'existing grants the app relies on are untouched');
select test.ok(not has_function_privilege('authenticated', 'public.guard_client_job_update()', 'execute')
  and not has_function_privilege('anon', 'public.handle_new_user()', 'execute'),
  'trigger functions are not callable by clients');

-- Triggers still fire for roles without EXECUTE on the trigger function.
create role zz_signup nologin;
grant usage on schema auth to zz_signup;
grant insert on auth.users to zz_signup;
set role zz_signup;
insert into auth.users values ('aaaaaaaa-0000-0000-0000-0000000000ff');
reset role;
select test.ok(exists (select 1 from public.profiles where id = 'aaaaaaaa-0000-0000-0000-0000000000ff'),
  'handle_new_user still creates profiles on signup');
delete from auth.users where id = 'aaaaaaaa-0000-0000-0000-0000000000ff';
revoke all on auth.users from zz_signup;
revoke usage on schema auth from zz_signup;
drop role zz_signup;
