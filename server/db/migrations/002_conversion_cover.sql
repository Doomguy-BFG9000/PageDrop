alter table public.jobs
  add column if not exists input_cover_storage_path text;
