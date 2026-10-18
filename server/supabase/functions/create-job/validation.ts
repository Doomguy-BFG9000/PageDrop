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
  p_ip_hash: string | null;
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
  ipHash: string | null = null,
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
    const blocked = blockedUrlReason(url);
    if (blocked) return reject(400, blocked);
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
      p_ip_hash: ipHash,
    },
  };
}

/** Maps a {"ok": false} result from create_job_with_quota to an HTTP rejection. */
export function quotaRejection(result: { ok?: boolean; error?: string } | null): Rejection | null {
  if (result?.ok === true) return null;
  switch (result?.error) {
    case "daily_limit":
      return { status: 429, error: "Daily job limit reached" };
    case "ip_limit":
      return { status: 429, error: "Too many jobs from this network today" };
    case "server_busy":
      return { status: 503, error: "The server is busy right now, please try again in a few minutes" };
    case "daily_capacity":
      return { status: 503, error: "Daily conversion capacity has been reached, please try again tomorrow" };
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

/**
 * Early SSRF guard for article URLs. The worker re-resolves the host and re-checks every
 * redirect hop, and is the authoritative check; this only rejects the obvious cases
 * (localhost, IP literals in private/special ranges, ports other than 80/443) up front.
 */
export function blockedUrlReason(raw: string): string | null {
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return "url must be an http(s) URL";
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") return "url must be an http(s) URL";
  if (u.username || u.password) return "url must not contain credentials";
  if (u.port !== "" && u.port !== "80" && u.port !== "443") return "url must use port 80 or 443";
  // WHATWG URL already normalises decimal/hex/octal IPv4 forms (http://2130706433/ -> 127.0.0.1).
  let host = u.hostname.toLowerCase().replace(/\.$/, "");
  if (host.startsWith("[") && host.endsWith("]")) host = host.slice(1, -1);
  if (host === "localhost" || host.endsWith(".localhost") || !host) return "url host is not allowed";
  if (isPrivateIPv4(host) || (host.includes(":") && isPrivateIPv6(host))) return "url host is not allowed";
  return null;
}

function isPrivateIPv4(host: string): boolean {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  return a === 0 || a === 10 || a === 127 || a >= 224 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 192 && b === 0 && Number(m[3]) === 0) ||
    (a === 198 && (b === 18 || b === 19));
}

function isPrivateIPv6(host: string): boolean {
  // Expand to eight 16-bit groups (URL has already normalised and compressed the literal).
  let h = host;
  const dotted = /(\d+\.\d+\.\d+\.\d+)$/.exec(h);
  if (dotted) {
    const o = dotted[1].split(".").map(Number);
    h = h.slice(0, -dotted[1].length) + ((o[0] << 8) | o[1]).toString(16) + ":" + ((o[2] << 8) | o[3]).toString(16);
  }
  const [head, tail] = h.split("::");
  const left = head ? head.split(":") : [];
  const right = tail === undefined ? [] : tail ? tail.split(":") : [];
  const fill = tail === undefined ? [] : Array(Math.max(0, 8 - left.length - right.length)).fill("0");
  const g = [...left, ...fill, ...right].map((x) => parseInt(x || "0", 16));
  if (g.length !== 8 || g.some(Number.isNaN)) return true; // unparseable: refuse
  if (g.slice(0, 5).every((x) => x === 0) && (g[5] === 0 || g[5] === 0xffff)) {
    // ::, ::1 and IPv4-mapped / compatible: judge the embedded IPv4 address.
    if (g[5] === 0 && g[6] === 0 && g[7] <= 1) return true;
    return isPrivateIPv4(`${g[6] >> 8}.${g[6] & 255}.${g[7] >> 8}.${g[7] & 255}`);
  }
  return (g[0] & 0xfe00) === 0xfc00 || (g[0] & 0xffc0) === 0xfe80 || (g[0] & 0xff00) === 0xff00 ||
    g[0] === 0x2002 || (g[0] === 0x64 && g[1] === 0xff9b) || (g[0] === 0x2001 && g[1] === 0);
}
