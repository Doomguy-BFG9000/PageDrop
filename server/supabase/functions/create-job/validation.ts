// Pure request validation for create-job, kept free of Deno/Supabase imports so it can
// be unit-tested with `node --test` (see validation_test.ts) as well as `deno test`.

// Keep in sync with create_job_with_quota() in 003_launch_hardening.sql and the worker.
export const MAX_FILE_SIZE = 100 * 1024 * 1024; // 100 MB
export const MAX_COVER_SIZE = 15 * 1024 * 1024; // 15 MB
export const ALLOWED_TARGET_FORMATS = new Set([
  "azw3", "epub", "mobi", "docx", "fb2", "htmlz", "kepub", "lit", "lrf", "pdb",
  "pdf", "pmlz", "rb", "rtf", "snb", "tcr", "txt", "txtz", "zip",
]);
const MAX_TEXT = 1000;
const MAX_URL = 2048;

export interface CreateJobBody {
  jobType: "convert" | "article";
  targetFormat?: string;
  targetProfile?: string;
  title?: string;
  author?: string;
  url?: string; // only for article jobs
  fileName?: string; // only for convert jobs
  coverFileName?: string; // optional, only for convert jobs
  coverSizeBytes?: number;
  fileSizeBytes?: number; // required for convert jobs
}

export interface Rejection {
  status: number;
  error: string;
}

export interface QuotaRpcParams {
  p_user_id: string;
  p_job_id: string;
  p_job_type: "convert" | "article";
  p_source_storage_path: string | null;
  p_input_cover_storage_path: string | null;
  p_source_url: string | null;
  p_target_format: string;
  p_target_profile: string;
  p_title: string | null;
  p_author: string | null;
  p_file_size_bytes: number | null;
  p_input_cover_size_bytes: number | null;
}

const isSize = (v: unknown): v is number =>
  typeof v === "number" && Number.isSafeInteger(v) && v > 0;

const optionalText = (v: unknown): string | null =>
  typeof v === "string" && v.trim().length > 0 ? v.slice(0, MAX_TEXT) : null;

/** Validates the body and builds create_job_with_quota's arguments, or says why not. */
export function buildJobParams(
  userId: string,
  jobId: string,
  body: unknown,
): { params: QuotaRpcParams } | { rejection: Rejection } {
  const reject = (status: number, error: string) => ({ rejection: { status, error } });
  if (typeof body !== "object" || body === null) return reject(400, "Invalid request body");
  const b = body as Record<string, unknown>;

  const jobType = b.jobType;
  if (jobType !== "convert" && jobType !== "article") return reject(400, "Invalid jobType");

  const targetFormat = typeof b.targetFormat === "string" ? b.targetFormat.toLowerCase() : "mobi";
  if (!ALLOWED_TARGET_FORMATS.has(targetFormat)) return reject(400, "Unsupported targetFormat");
  const targetProfile = typeof b.targetProfile === "string" && /^[a-z0-9_-]{1,40}$/i.test(b.targetProfile)
    ? b.targetProfile
    : "kindle-stock";

  let sourceStoragePath: string | null = null;
  let inputCoverStoragePath: string | null = null;
  let sourceUrl: string | null = null;
  let fileSize: number | null = null;
  let coverSize: number | null = null;

  if (jobType === "convert") {
    if (!isSize(b.fileSizeBytes)) return reject(400, "fileSizeBytes is required");
    if (b.fileSizeBytes > MAX_FILE_SIZE) return reject(413, "File exceeds maximum size of 100MB");
    fileSize = b.fileSizeBytes;
    sourceStoragePath = `${userId}/${jobId}/${sanitizeFileName(asString(b.fileName) ?? "source.epub")}`;

    const coverName = asString(b.coverFileName);
    if (coverName) {
      if (!isSize(b.coverSizeBytes)) return reject(400, "coverSizeBytes is required with coverFileName");
      if (b.coverSizeBytes > MAX_COVER_SIZE) return reject(413, "Cover image exceeds maximum size of 15MB");
      coverSize = b.coverSizeBytes;
      inputCoverStoragePath = `${userId}/${jobId}/cover_${sanitizeFileName(coverName)}`;
    }
  } else {
    const url = asString(b.url);
    if (!url || url.length > MAX_URL || !/^https?:\/\//i.test(url)) {
      return reject(400, "url must be an http(s) URL");
    }
    sourceUrl = url;
  }

  return {
    params: {
      p_user_id: userId,
      p_job_id: jobId,
      p_job_type: jobType,
      p_source_storage_path: sourceStoragePath,
      p_input_cover_storage_path: inputCoverStoragePath,
      p_source_url: sourceUrl,
      p_target_format: targetFormat,
      p_target_profile: targetProfile,
      p_title: optionalText(b.title),
      p_author: optionalText(b.author),
      p_file_size_bytes: fileSize,
      p_input_cover_size_bytes: coverSize,
    },
  };
}

/** Maps a {"ok": false} result from create_job_with_quota to an HTTP rejection. */
export function quotaRejection(result: { ok?: boolean; error?: string } | null): Rejection | null {
  if (result?.ok === true) return null;
  switch (result?.error) {
    case "daily_limit":
      return { status: 429, error: "Daily job limit reached" };
    case "storage_quota":
      return { status: 403, error: "Storage quota exceeded" };
    case "file_too_large":
      return { status: 413, error: "File exceeds maximum size of 100MB" };
    case "cover_too_large":
      return { status: 413, error: "Cover image exceeds maximum size of 15MB" };
    case undefined:
      return { status: 500, error: "Failed to create job" };
    default:
      return { status: 400, error: result.error };
  }
}

export function sanitizeFileName(name: string): string {
  const cleaned = name
    .split("/")
    .pop()!
    .replace(/[^A-Za-z0-9._ -]/g, "_")
    .trim()
    .slice(0, 200);
  return cleaned.length > 0 && cleaned !== "." && cleaned !== ".." ? cleaned : "source.epub";
}

function asString(v: unknown): string | null {
  return typeof v === "string" && v.length > 0 ? v : null;
}
