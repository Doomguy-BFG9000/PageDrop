#!/usr/bin/env bash
# Runs the migrations and their behaviour tests against a throwaway local Postgres
# (needs initdb/pg_ctl/psql on PATH, Postgres 15+). Nothing touches Supabase.
#
#   server/db/tests/run.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
migrations="$here/../migrations"
tmp="$(mktemp -d)"
export PGHOST="$tmp" PGPORT=54329 PGUSER=postgres PGDATABASE=pagedrop_test
# Without a valid locale, Postgres on macOS refuses to start ("postmaster became multithreaded").
export LC_ALL="${LC_ALL:-C}"

cleanup() { pg_ctl -D "$tmp/data" -m immediate stop >/dev/null 2>&1 || true; rm -rf "$tmp"; }
trap cleanup EXIT

initdb -D "$tmp/data" -U postgres --auth=trust >/dev/null
pg_ctl -D "$tmp/data" -o "-k $tmp -c listen_addresses='' -p $PGPORT" -l "$tmp/log" -w start >/dev/null
createdb pagedrop_test

psql_run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

export PGOPTIONS="-c client_min_messages=warning"
psql_run -f "$here/supabase_stubs.sql"
for f in "$migrations"/0*.sql; do psql_run -f "$f"; done
# Every migration must be safe to re-run.
for f in "$migrations"/0*.sql; do psql_run -f "$f"; done
echo "ok - migrations apply and re-apply cleanly"

PGOPTIONS="" psql_run -At -f "$here/test_launch_hardening.sql" 2>&1 \
  | sed -n 's/^psql:[^:]*:[0-9]*: NOTICE:  //p; /ERROR/p'

PGOPTIONS="" psql_run -At -f "$here/test_abuse_caps.sql" 2>&1 \
  | sed -n 's/^psql:[^:]*:[0-9]*: NOTICE:  //p; /ERROR/p'

PGOPTIONS="" psql_run -At -f "$here/test_upload_limits.sql" 2>&1 \
  | sed -n 's/^psql:[^:]*:[0-9]*: NOTICE:  //p; /ERROR/p'

# 30 simultaneous create-job calls for user C (daily_job_limit = 5): exactly 5 may win.
seq 1 30 | xargs -P 30 -I{} psql -X -qAt -c "
  set role service_role;
  select set_config('request.jwt.claims', '{\"role\":\"service_role\"}', true);
  select test.create_convert('aaaaaaaa-0000-0000-0000-000000000003', gen_random_uuid(), 10) ->> 'ok';" \
  | grep -c '^true$' > "$tmp/accepted" || true
accepted="$(cat "$tmp/accepted")"
rows="$(psql -X -qAt -c "select count(*) from public.jobs where user_id = 'aaaaaaaa-0000-0000-0000-000000000003'")"
counter="$(psql -X -qAt -c "select job_count from public.rate_limits where user_id = 'aaaaaaaa-0000-0000-0000-000000000003'")"
if [[ "$accepted" == 5 && "$rows" == 5 && "$counter" == 5 ]]; then
  echo "ok - 30 concurrent requests against a limit of 5: 5 accepted, 5 rows, counter 5"
else
  echo "FAIL: concurrent quota (accepted=$accepted rows=$rows counter=$counter)"; exit 1
fi

# 10 simultaneous create-job calls for user F (default max_active_jobs = 2): exactly 2 may win.
seq 1 10 | xargs -P 10 -I{} psql -X -qAt -c "
  set role service_role;
  select set_config('request.jwt.claims', '{\"role\":\"service_role\"}', true);
  select test.create_convert('aaaaaaaa-0000-0000-0000-000000000006', gen_random_uuid(), 10) ->> 'ok';" \
  | grep -c '^true$' > "$tmp/accepted_f" || true
accepted="$(cat "$tmp/accepted_f")"
rows="$(psql -X -qAt -c "select count(*) from public.jobs where user_id = 'aaaaaaaa-0000-0000-0000-000000000006'")"
if [[ "$accepted" == 2 && "$rows" == 2 ]]; then
  echo "ok - 10 concurrent requests against max_active_jobs = 2: 2 accepted, 2 rows"
else
  echo "FAIL: concurrent active-job cap (accepted=$accepted rows=$rows)"; exit 1
fi

echo "all database tests passed"
