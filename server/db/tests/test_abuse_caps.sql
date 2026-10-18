-- Behaviour tests for 004_abuse_caps.sql (run by run.sh after test_launch_hardening.sql).
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

insert into auth.users values ('aaaaaaaa-0000-0000-0000-000000000004');  -- D
update public.profiles set daily_job_limit = 1000, storage_quota_bytes = 100000000000
  where id = 'aaaaaaaa-0000-0000-0000-000000000004';

create function test.article(u uuid, ip text default null) returns jsonb language sql as $$
  select public.create_job_with_quota(u, gen_random_uuid(), 'article', null, null,
    'https://example.com/a', 'mobi', 'kindle-stock', null, null, null, null, ip)
$$;
grant execute on function test.article(uuid, text) to public;

begin;
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

-- Per-IP cap: 30 jobs per day per hash, then refused without counting anywhere.
select test.article('aaaaaaaa-0000-0000-0000-000000000004', 'hash-of-ip-aaaaaaaaaaaaaaaa') from generate_series(1, 30);
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004', 'hash-of-ip-aaaaaaaaaaaaaaaa') ->> 'error') = 'ip_limit',
  'the 31st job from one IP hash is refused');
select test.ok((select job_count from public.ip_rate_limits where ip_hash = 'hash-of-ip-aaaaaaaaaaaaaaaa') = 30,
  'refused job does not bump the IP counter');
select test.ok((select job_count from public.rate_limits where user_id = 'aaaaaaaa-0000-0000-0000-000000000004') = 30,
  'refused IP-limited job does not use the user allowance (rolled back)');
select test.ok((select job_count from public.daily_totals where date = (now() at time zone 'utc')::date) >= 30,
  'daily totals count accepted jobs');
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004', 'hash-of-ip-bbbbbbbbbbbbbbbb') ->> 'ok')::boolean,
  'a different IP hash is unaffected');
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004', null) ->> 'ok')::boolean,
  'no IP hash skips the per-IP cap');
select test.ok((select count(*) from public.ip_rate_limits where ip_hash like '%.%') = 0, 'only hashes are stored');

-- Global daily upload bytes: 5 GB across all users.
update public.daily_totals set upload_bytes = 5368709120 - 50 where date = (now() at time zone 'utc')::date;
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000004', gen_random_uuid(), 100) ->> 'error') = 'daily_capacity',
  'upload that would pass the global daily byte cap is refused');
select test.ok((select upload_bytes from public.daily_totals where date = (now() at time zone 'utc')::date) = 5368709120 - 50,
  'refused upload leaves the byte total unchanged');
select test.ok((test.create_convert('aaaaaaaa-0000-0000-0000-000000000004', gen_random_uuid(), 50) ->> 'ok')::boolean,
  'upload that exactly reaches the cap is accepted');
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004') ->> 'ok')::boolean,
  'article jobs (0 bytes) are not blocked by a full byte budget');
update public.daily_totals set upload_bytes = 0 where date = (now() at time zone 'utc')::date;

-- Global queue cap: 200 active jobs.
delete from public.jobs;
insert into public.jobs (user_id, status, job_type, source_url, target_format)
  select 'aaaaaaaa-0000-0000-0000-000000000004', 'pending', 'article', 'https://example.com/' || g, 'mobi' from generate_series(1, 199) g;
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004') ->> 'ok')::boolean, 'the 200th active job is accepted');
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004') ->> 'error') = 'server_busy', 'the 201st active job gets server_busy');
update public.jobs set status = 'complete' where id = (select id from public.jobs where status = 'pending' limit 1);
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004') ->> 'ok')::boolean, 'a finished job frees a slot');
update public.jobs set status = 'awaiting_upload', created_at = now() - interval '2 hours', job_type = 'article'
  where status = 'pending' and id in (select id from public.jobs where status = 'pending' limit 50);
select test.ok((test.article('aaaaaaaa-0000-0000-0000-000000000004') ->> 'ok')::boolean, 'abandoned awaiting_upload jobs stop counting after an hour');
delete from public.jobs;
delete from public.ip_rate_limits;
commit;

select test.fails($$set role authenticated; select * from public.daily_totals$$, '42501', 'clients cannot read daily_totals');
