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
/docker-compose.yml
```

The root compose file exists for Coolify's create screen. The equivalent compose
definition also lives at `server/docker/docker-compose.yml` for server-specific
work, but Coolify is easiest when pointed at the root default.

5. Add environment variables:

```bash
SUPABASE_URL=https://bzvvpwykluklvvjyzytn.supabase.co
SUPABASE_SERVICE_KEY=<service_role_key>
POLL_INTERVAL=5
MAX_CONCURRENT=1
JOB_TIMEOUT=300
WORKER_MEMORY_LIMIT=3g
WORKER_CPU_LIMIT=1.0
```

6. Deploy and watch logs. The worker should claim pending jobs and update them
to `complete` or `failed`.

The existing Ampere A1 box is the right place for this because Calibre can use
real RAM/CPU. Keep `MAX_CONCURRENT=1` so it coexists politely with other
services.

### 1. Supabase Setup

1. Go to [supabase.com](https://supabase.com) and create a project
2. Open the SQL Editor and run `server/db/migrations/001_schema.sql` — creates all tables, indexes, RLS policies, and the auto-profile trigger
3. Go to **Storage** and create two buckets:
   - `sources` — private (only service_role can read)
   - `artifacts` — public read (users download their results)
4. Go to **Authentication → Settings** and enable Anonymous Sign-Ins (for quick start) or configure Email/password auth
5. Go to **SQL Editor** and grant worker permissions:

```sql
grant usage on schema public to service_role;
grant all on all tables in schema public to service_role;
grant all on all functions in schema public to service_role;
```

6. Deploy the Edge Function:

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

3. Copy the worker to the instance:

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

### 3. Android App Setup

1. In the app, go to **Tools → Connection** (or the server settings flow)
2. Enable "Server-Assisted Mode"
3. Enter your Supabase URL and anon key (from Settings → API)
4. The app will anonymously sign in and start checking health

## API (via Supabase Edge Functions)

### `POST create-job`

Creates a conversion job in the queue. Requires auth token.

**Request:**
```json
{
  "jobType": "convert",
  "targetFormat": "mobi",
  "targetProfile": "kindle-stock",
  "fileSizeBytes": 5000000
}
```

**Response (201):**
```json
{ "jobId": "uuid", "status": "pending" }
```

**Errors:**
- `401` — Unauthorized
- `429` — Daily job limit reached
- `413` — File exceeds 100MB

### Job Status

Poll `GET /rest/v1/jobs?id=eq.{jobId}` via Supabase PostgREST.

The app uses the Supabase Kotlin SDK to query job status directly.

## Limits (Free Tier)

| Limit | Free | Pro |
|-------|------|-----|
| Conversions/day | 20 | Unlimited |
| Max file size | 100 MB | 500 MB |
| Storage per user | 500 MB | 5 GB |
| Artifact TTL | 24 hours | 7 days |
| Queue priority | Normal | High |

## Worker Details

The worker is a Python script (`server/worker/poll.py`) that:

1. Polls Supabase for `pending` jobs (oldest first)
2. Atomically claims a job (sets status to `processing`)
3. Downloads the source file from Supabase Storage
4. Runs `ebook-convert` to the target format with PageDrop's title and author
5. Reapplies that metadata with `ebook-meta`, preventing source-PDF metadata
   from becoming the title shown by a Kindle or KOReader
6. Extracts the embedded cover with `ebook-meta --get-cover`
7. Uploads results to Supabase Storage
8. Updates job status to `complete` or `failed`
9. Sleeps 5 seconds, repeats

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
