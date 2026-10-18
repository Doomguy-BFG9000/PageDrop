# PageDrop Server

Conversion worker that pairs with the PageDrop Android app. Handles heavy
ebook conversion (Calibre CLI) and article extraction when the app decides
it can't do the conversion locally.

**Architecture:** Supabase (auth, DB, storage, API) + Worker (Calibre on VPS/Coolify)

## Architecture

```
Android App → Supabase Edge Functions → Postgres queue → Worker (polls) → Calibre CLI
                              ↕                               ↕
                     Supabase Storage                  Uploads result
```

- **Supabase** handles auth, database, file storage, and the REST API
- **Worker** runs on Oracle Cloud free tier (or any Linux box), polls for pending jobs
- **Calibre CLI** does the actual conversion

## Deploy

### Coolify on the existing Ampere VPS

Do **not** pick the Calibre Web or Calibre Web Automated template for the first
worker deploy. Those are user-facing web-library services. PageDrop needs the
thin conversion worker in this repo.

In Coolify:

1. Add Resource → Docker Compose / Git Repository.
2. Select this repository.
3. Set Base Directory to:

```text
/
```

4. Set Docker Compose Location to:

```text
/docker-compose.yaml
```

The root compose file (`docker-compose.yaml`) exists for Coolify's create screen.
The equivalent compose definition also lives at `server/docker/docker-compose.yml`
for server-specific work; keep the two in sync.

5. Add environment variables:

```bash
SUPABASE_URL=https://bzvvpwykluklvvjyzytn.supabase.co
SUPABASE_SERVICE_KEY=<service_role_key>
POLL_INTERVAL=5
MAX_CONCURRENT=1
JOB_TIMEOUT=300
RETENTION_HOURS=24
CLEANUP_INTERVAL=600
WORKER_MEMORY_LIMIT=3g
WORKER_CPU_LIMIT=1.0
```

6. Deploy and watch logs. The worker should claim pending jobs and update them
to `complete` or `failed`.

The existing Ampere A1 box is the right place for this because Calibre can use
real RAM/CPU. Keep `MAX_CONCURRENT=1` so it coexists politely with other
services.

### Deploy order (required)

Every release that touches the server must be rolled out in this order:

1. **Run migrations `003` then `004`** in the Supabase SQL editor (after `001`/`002` on a
   fresh project).
2. **Deploy `create-job`** (`supabase functions deploy create-job --no-verify-jwt`). The new
   function passes a `p_ip_hash` argument that only exists after `004`.
3. **Redeploy the worker** (Coolify redeploy). Do it last so it never runs against an older
   schema, and so the SSRF-safe fetcher and exact-path retention go live together.

Always re-run `003` **and then** `004` after `001` if you ever re-run `001`, and re-run
`004` after any re-run of `003`: `003` recreates the old 12-argument
`create_job_with_quota()`, which `004` drops again (both would otherwise be callable).

### 1. Supabase Setup

1. Go to [supabase.com](https://supabase.com) and create a project
2. Open the SQL Editor and run every file in `server/db/migrations/` **in order**
   (each is safe to re-run):
   - `001_schema.sql` — tables, indexes, RLS policies, the auto-profile trigger and
     the `sources` / `artifacts` buckets
   - `002_conversion_cover.sql` — the user-selected cover column
   - `003_launch_hardening.sql` — locks down what clients can write, adds the atomic
     quota function `create_job_with_quota()`, makes both buckets private and caps
     `sources` uploads at 100 MB
   - `004_abuse_caps.sql` — global active-job cap, global daily upload-byte cap and an
     optional per-IP daily job cap, all enforced inside `create_job_with_quota()`
3. Check **Storage**: both `sources` and `artifacts` must be **private**. Do not make
   `artifacts` public — the app downloads results with the user's token, and RLS
   limits each user to their own folder.
4. Check **Storage → Settings**: the global upload file size limit must be at least
   100 MB, or larger books fail to upload (Supabase's free plan caps it at 50 MB).
5. Go to **Authentication → Sign In / Providers** and enable Anonymous Sign-Ins — the
   app signs in anonymously.

   **Required before launch:** tighten the anonymous sign-in rate limit. Anyone can mint
   unlimited anonymous accounts, and every account gets its own daily quota, so this is
   the main brake on abuse. In the dashboard open **Authentication → Rate Limits**
   (`https://supabase.com/dashboard/project/<ref>/auth/rate-limits`), find **Rate limit
   for sign-ins of anonymous users** (default 30 per hour per IP) and lower it to about
   **5-10 per hour per IP**. Also keep CAPTCHA protection for anonymous sign-ins in mind
   if abuse appears (**Authentication → Attack Protection**).

   Also set the salt for the per-IP job cap (see Privacy notes):
   `supabase secrets set IP_HASH_SALT=$(openssl rand -hex 32)`.
6. Go to **SQL Editor** and grant worker permissions:

```sql
grant usage on schema public to service_role;
grant all on all tables in schema public to service_role;
grant all on all functions in schema public to service_role;
```

Never grant table writes or function execution to `anon` or `authenticated`;
migration 003 deliberately revokes them.

7. Deploy the Edge Function (step 2 of the deploy order above):

```bash
# Install Supabase CLI
npm install -g supabase

# Link to your project
supabase link --project-ref <your-project-ref>

# Deploy
supabase functions deploy create-job --no-verify-jwt
```

### 2. Worker Setup (Oracle Cloud Free Tier)

1. Provision an Oracle Cloud Ampere A1 instance (Ubuntu 24.04 ARM64)
2. Install Docker:

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker ubuntu
```

3. Copy the server folder to the instance:

```bash
scp -r server/ ubuntu@<oracle-ip>:~/pagedrop-server
```

4. Set environment variables and start:

```bash
cd ~/pagedrop-server/docker
export SUPABASE_URL="https://<project>.supabase.co"
export SUPABASE_SERVICE_KEY="<service_role_key>"
docker compose up -d
```

5. Verify:

```bash
docker compose logs -f
```

6. **Firewall (defence in depth).** The worker fetches user-supplied article URLs. It
   already refuses non-public addresses, pins requests to the address it validated and
   re-checks every redirect, but also block egress from the worker container/VPS to
   RFC1918 ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), `169.254.0.0/16`
   (cloud metadata), `100.64.0.0/10`, `127.0.0.0/8` and the IPv6 equivalents (`fc00::/7`,
   `fe80::/10`) at the host firewall, allowing only what the worker needs (Supabase,
   public internet, DNS). Docker example:
   `iptables -I DOCKER-USER -s <worker-subnet> -d 10.0.0.0/8 -j DROP` (repeat per range).

### 3. Android App Setup

The server address is a build-time setting, not something users enter. Build the
app against your project with your Supabase URL and anon key (Supabase
**Project Settings → API**):

```bash
./gradlew assembleDebug \
  -PPAGEDROP_SUPABASE_URL=https://<project>.supabase.co \
  -PPAGEDROP_SUPABASE_ANON_KEY=<anon_key>
```

Users can turn conversion off under **Settings → Conversion server → Use PageDrop
conversion server**. The app signs in anonymously when it first needs the server.

## API (via Supabase Edge Functions)

### `POST create-job`

Creates a conversion job in the queue. Requires auth token.

**Request:**
```json
{
  "jobType": "convert",
  "targetFormat": "mobi",
  "fileName": "book.epub",
  "fileSizeBytes": 5000000,
  "coverFileName": "cover.jpg",
  "coverSizeBytes": 120000,
  "title": "Title",
  "author": "Author"
}
```

`fileSizeBytes` is required for convert jobs (and `coverSizeBytes` with
`coverFileName`). Article jobs send `{"jobType": "article", "url": "https://..."}`.

**Response (201):**
```json
{
  "jobId": "uuid",
  "status": "awaiting_upload",
  "sourceStoragePath": "<user_id>/<job_id>/book.epub",
  "inputCoverStoragePath": "<user_id>/<job_id>/cover_cover.jpg"
}
```

The app then uploads to exactly those paths in the `sources` bucket and PATCHes the
job's `status` to `pending`. That is the only change a client can make to a job: the
database refuses it unless the uploads exist and are no larger than declared, and
refuses uploads to any other path or after the job is queued.

**Errors:**
- `400` — Invalid request (missing size, unsupported format, non-http URL)
- `401` — Unauthorized
- `403` — Storage quota exceeded
- `413` — File exceeds 100 MB (cover: 15 MB)
- `429` — Daily job limit reached, or too many jobs from this network today
- `503` — Server busy (global queue cap) or the daily upload capacity is used up; retry later

Limits are checked and the daily counter taken in one transaction, so concurrent
requests cannot overshoot the limit and a refused request is not counted.

### Job Status

Poll `GET /rest/v1/jobs?id=eq.{jobId}` via Supabase PostgREST.

The app uses the Supabase Kotlin SDK to query job status directly.

## Limits

Enforced for every user (there is no paid tier yet):

| Limit | Value | Where it is set |
|-------|-------|-----------------|
| Jobs per day (UTC) | 20 | `profiles.daily_job_limit` |
| Max source file | 100 MB | `create_job_with_quota()`, `sources` bucket limit, worker |
| Max cover image | 15 MB | `create_job_with_quota()`, worker |
| Uploads held per user | 500 MB | `profiles.storage_quota_bytes` (sum of the user's unexpired jobs) |
| Active jobs, all users | 200 (pending, processing, awaiting_upload younger than 1 h) | `c_max_active_jobs` in `create_job_with_quota()` (004) |
| Declared upload bytes per UTC day, all users | 5 GB | `c_max_daily_bytes` (004), counted in `daily_totals` |
| Jobs per client IP per UTC day | 30 | `c_max_ip_daily_jobs` (004), counted in `ip_rate_limits` |
| Retention | 24 hours | worker `RETENTION_HOURS` |

To change a cap, edit the constant in `004_abuse_caps.sql` and re-run it. Caps are checked
and counted in the same transaction as the job insert, and a refused job is not counted
against any of them.

### Privacy notes

- The per-IP cap uses `cf-connecting-ip`, else the first `x-forwarded-for` entry. create-job
  hashes it with SHA-256 and the secret `IP_HASH_SALT` and stores only that hash in
  `ip_rate_limits(date, ip_hash, job_count)`. Raw IPs are never stored or logged, and the
  rows are deleted by the worker after about two days. If `IP_HASH_SALT` is unset the cap
  is skipped.
- Article URLs are stored with the job and deleted with it after 24 hours.

## Retention

The worker deletes everything belonging to a job 24 hours after it was created,
whatever its status: the uploaded source and cover in `sources`, the converted book
and cover in `artifacts`, and the job row itself. It runs every `CLEANUP_INTERVAL`
seconds (default 600) and also drops daily counter rows older than two days. Files
are deleted before the row: the worker removes the exact paths recorded on the job plus
anything listed under `<user_id>/<job_id>/`, lists both buckets again, and deletes the
row only if nothing is left. Otherwise it logs the job id and retries on the next pass. Local temp
files are removed as soon as each job finishes.

Retention only runs while a worker is running. To check it, run this in the SQL
editor; it should return 0 (allowing for one cleanup interval):

```sql
select count(*) from public.jobs where created_at < now() - interval '24 hours 15 minutes';
```

## Worker Details

The worker is a Python script (`server/worker/poll.py`) that:

1. Polls Supabase for `pending` jobs (oldest first)
2. Atomically claims a job (a conditional PATCH with `Prefer: return=representation`;
   an empty result means another worker won and the job is skipped)
3. Downloads the source file from Supabase Storage
4. Runs `ebook-convert` to the target format with PageDrop's title and author.
   PDF sources additionally get reflow options: heuristic processing (unwrap
   lines, merge paragraphs split at page breaks, dehyphenate, drop blank
   paragraphs), no paragraph spacing with a 1.5em indent, the `kindle` output
   profile, cleared comments/tags, and search-and-replace rules that strip page
   anchors/breaks, bare page-number lines, `N | P a g e` labels, and running
   headers/footers detected by `pdftotext` (short lines repeating on many pages)
5. Reapplies that metadata with `ebook-meta`, preventing source-PDF metadata
   from becoming the title shown by a Kindle or KOReader
6. Extracts the embedded cover with `ebook-meta --get-cover`
7. Uploads results to Supabase Storage
8. Updates job status to `complete` or `failed`
9. Sleeps 5 seconds, repeats

Article URLs go through `server/worker/safe_fetch.py`: only http/https on ports 80/443,
no credentials, every address the host resolves to must be public (loopback, private,
link-local, `100.64.0.0/10`, multicast, reserved, unspecified and mapped/embedded forms
are refused), the request is sent to the validated IP with the original `Host` and TLS
SNI (no DNS rebinding), redirects are followed manually (max 5) and re-validated, and the
response is capped at 10 MB and by a timeout. `create-job` applies the same rules to
literal hosts as an early guard; the worker check is authoritative.

Before it touches Storage, the worker refuses any job whose source or cover path is
not a single file inside `<user_id>/<job_id>/` for that job's owner, whose target
format is not on the allow-list, or whose download is larger than the size declared
when the job was created. It runs as the service role and bypasses RLS, so these
checks stand on their own even if the database rules are loosened.

## Tests

```bash
server/db/tests/run.sh                                   # migrations + RLS/quota rules on a throwaway local Postgres
python3 -m unittest discover -s server/worker           # SSRF validator + retention (stdlib, offline)
python -m pytest server/worker/tests                     # worker path/size checks (pip install -r server/worker/requirements-dev.txt)
node --test server/supabase/functions/create-job/validation_test.ts   # create-job validation (Node 22.6+)
```

Concurrent jobs are limited to 1 by default to stay within free-tier
Oracle limits.

## Metadata Ownership

The Android app is the metadata control plane. It sends the title and author
selected by the user with every conversion job. The worker passes those values
to `ebook-convert` and applies them again to the finished book with
`ebook-meta`; source-file metadata is never authoritative.

When a field is missing, the app parses embedded data and filename hints, then
looks up the ISBN first or normalized title plus author second. It uses catalog
APIs rather than scraping arbitrary web pages: Google Books is the primary
candidate source and Open Library fills cover or identifier gaps. The app shows
candidate editions and the user must select one or edit the details manually;
low-confidence data is never silently applied.

The next metadata contract should add optional, app-owned fields for a supplied
cover image, language, publisher, description, ISBN, tags, series, and series
index. The app stores the confirmed values locally and uploads an explicitly
selected cover with the job. The worker then supplies the cover to both
`ebook-convert --cover` and `ebook-meta --cover` so the embedded first page,
ebook metadata, and Kindle thumbnail all use the same artwork. Existing books
use a metadata-only job that updates the existing artifact without a reflow
conversion.

References: [Google Books Volumes API](https://developers.google.com/books/docs/v1/reference/volumes),
[Open Library Search API](https://openlibrary.org/dev/docs/api/search), and
[Open Library Covers API](https://openlibrary.org/dev/docs/api/covers).

For PDFs, keep the Calibre PDF engine as the default because it supports
automatic header/footer removal. Expose advanced options only when needed:
header/footer removal, line unwrapping, and a Kindle output profile. Complex,
scanned, multi-column, mathematical, or right-to-left PDFs should remain PDF
unless the user explicitly accepts a reflowed conversion preview.
