-- PageDrop Seed Data for testing
-- Run after the schema migration.

-- Create a test user profile (replace with an actual user ID from auth.users)
-- insert into public.profiles (id, tier) values ('<ACTUAL_USER_ID>', 'pro');

-- Create a test job (for manual worker testing). The worker only reads a source
-- stored at exactly <user_id>/<job_id>/<file name>, so upload it there (as the
-- service role) with the same job id, and declare its size.
-- insert into public.jobs (id, user_id, job_type, target_format, source_storage_path, file_size_bytes)
-- values (
--   '<NEW_JOB_UUID>',
--   '<ACTUAL_USER_ID>',
--   'convert',
--   'mobi',
--   '<ACTUAL_USER_ID>/<NEW_JOB_UUID>/source.epub',
--   <SOURCE_SIZE_IN_BYTES>
-- );

-- Grant worker permissions
-- Go to Supabase Dashboard → SQL Editor → run:
--   grant usage on schema public to service_role;
--   grant all on all tables in schema public to service_role;
