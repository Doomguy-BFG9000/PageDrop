// PageDrop Edge Function: Create Conversion Job
//
// Validates the request, then calls create_job_with_quota() (migration 003), which in
// one transaction checks upload sizes and the storage quota, takes one unit of the
// user's daily job allowance, applies the global caps (queue length, daily upload
// bytes, per-IP jobs; migration 004) and inserts the job. Called by the Android app.
//
// Deploy as a Supabase Edge Function:
//   supabase functions deploy create-job --no-verify-jwt

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { buildJobParams, quotaRejection } from "./validation.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// Salt for hashing client IPs (per-IP daily cap). Set it with:
//   supabase secrets set IP_HASH_SALT=$(openssl rand -hex 32)
// Without it the per-IP cap is skipped; the raw IP is never stored or logged.
const ipHashSalt = Deno.env.get("IP_HASH_SALT") ?? "";

const supabase = createClient(supabaseUrl, supabaseServiceKey);

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: corsHeaders(),
    });
  }
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  // 1. Get authenticated user
  const authHeader = req.headers.get("Authorization")?.replace("Bearer ", "");
  if (!authHeader) {
    return json({ error: "Unauthorized" }, 401);
  }

  const { data: { user }, error: authError } = await supabase.auth.getUser(authHeader);
  if (authError || !user) {
    return json({ error: "Invalid token" }, 401);
  }

  // 2. Parse and validate body
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }

  const jobId = crypto.randomUUID();
  const built = buildJobParams(user.id, jobId, body, await hashClientIp(req));
  if ("rejection" in built) {
    return json({ error: built.rejection.error }, built.rejection.status);
  }
  const params = built.params;

  // 3. Check limits and create the job atomically
  const { data: result, error: rpcError } = await supabase.rpc("create_job_with_quota", params);
  if (rpcError) {
    console.error("create_job_with_quota failed:", rpcError);
    return json({ error: "Failed to create job" }, 500);
  }
  const rejection = quotaRejection(result);
  if (rejection) {
    return json({ error: rejection.error }, rejection.status);
  }

  // 4. Return job info to the app
  return json({
    jobId,
    status: result.status,
    sourceStoragePath: params.p_source_storage_path,
    inputCoverStoragePath: params.p_input_cover_storage_path,
  }, 201);
});

async function hashClientIp(req: Request): Promise<string | null> {
  if (!ipHashSalt) return null;
  const ip = req.headers.get("cf-connecting-ip")?.trim() ||
    req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (!ip) return null;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${ipHashSalt}:${ip}`));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

function json(payload: unknown, status: number) {
  return new Response(JSON.stringify(payload), { status, headers: corsHeaders() });
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Content-Type": "application/json",
  };
}
