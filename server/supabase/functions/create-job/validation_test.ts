// node --test server/supabase/functions/create-job/validation_test.ts
// (Node 22.6+ strips the types; `deno test` also runs node:test suites.)
import { test } from "node:test";
import assert from "node:assert/strict";
import { blockedUrlReason, buildJobParams, quotaRejection, sanitizeFileName } from "./validation.ts";

const USER = "aaaaaaaa-0000-0000-0000-000000000001";
const JOB = "11111111-0000-0000-0000-000000000001";

const params = (body: unknown) => {
  const r = buildJobParams(USER, JOB, body);
  assert.ok("params" in r, JSON.stringify(r));
  return r.params;
};
const rejection = (body: unknown) => {
  const r = buildJobParams(USER, JOB, body);
  assert.ok("rejection" in r, JSON.stringify(r));
  return r.rejection;
};

test("the app's convert request builds paths inside the user's job folder", () => {
  const p = params({
    jobType: "convert", targetFormat: "mobi", fileName: "My Book.epub", title: "T", author: "A",
    coverFileName: "cover.jpg", coverSizeBytes: 500, fileSizeBytes: 1000,
  });
  assert.equal(p.p_source_storage_path, `${USER}/${JOB}/My Book.epub`);
  assert.equal(p.p_input_cover_storage_path, `${USER}/${JOB}/cover_cover.jpg`);
  assert.equal(p.p_file_size_bytes, 1000);
  assert.equal(p.p_input_cover_size_bytes, 500);
});

test("file names cannot escape the job folder", () => {
  for (const name of ["../../other/book.epub", "..", ".", "a/b/../c.epub", "x\\..\\y.epub"]) {
    const path = params({ jobType: "convert", fileName: name, fileSizeBytes: 1 }).p_source_storage_path!;
    assert.ok(path.startsWith(`${USER}/${JOB}/`), path);
    assert.ok(!path.slice(`${USER}/${JOB}/`.length).includes("/"), path);
    assert.ok(!path.endsWith("/.."), path);
  }
  assert.equal(sanitizeFileName(".."), "source.epub");
});

test("convert jobs must declare sizes within the limits", () => {
  assert.equal(rejection({ jobType: "convert" }).status, 400);
  assert.equal(rejection({ jobType: "convert", fileSizeBytes: -1 }).status, 400);
  assert.equal(rejection({ jobType: "convert", fileSizeBytes: "5" }).status, 400);
  assert.equal(rejection({ jobType: "convert", fileSizeBytes: 100 * 1024 * 1024 + 1 }).status, 413);
  assert.equal(rejection({ jobType: "convert", fileSizeBytes: 1, coverFileName: "c.jpg" }).status, 400);
  assert.equal(
    rejection({ jobType: "convert", fileSizeBytes: 1, coverFileName: "c.jpg", coverSizeBytes: 15 * 1024 * 1024 + 1 }).status,
    413,
  );
});

test("target format is allow-listed", () => {
  assert.equal(rejection({ jobType: "convert", fileSizeBytes: 1, targetFormat: "../x" }).status, 400);
  assert.equal(params({ jobType: "convert", fileSizeBytes: 1, targetFormat: "AZW3" }).p_target_format, "azw3");
});

test("article jobs need an http(s) url and get no storage paths", () => {
  assert.equal(rejection({ jobType: "article", url: "file:///etc/passwd" }).status, 400);
  assert.equal(rejection({ jobType: "article" }).status, 400);
  const p = params({ jobType: "article", url: "https://example.com/a", fileName: "x" });
  assert.equal(p.p_source_url, "https://example.com/a");
  assert.equal(p.p_source_storage_path, null);
});

test("invalid bodies are rejected", () => {
  assert.equal(rejection(null).status, 400);
  assert.equal(rejection({ jobType: "delete" }).status, 400);
});

test("quota RPC rejections become real HTTP errors", () => {
  assert.equal(quotaRejection({ ok: true }), null);
  assert.equal(quotaRejection({ ok: false, error: "daily_limit" })!.status, 429);
  assert.equal(quotaRejection({ ok: false, error: "storage_quota" })!.status, 403);
  assert.equal(quotaRejection({ ok: false, error: "file_too_large" })!.status, 413);
  assert.equal(quotaRejection(null)!.status, 500);
});

test("article URLs pointing at internal addresses are rejected early", () => {
  for (const url of [
    "http://localhost/", "http://foo.localhost/", "http://127.0.0.1/", "http://10.0.0.1/", "http://172.16.0.1/",
    "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data/", "http://0.0.0.0/", "http://100.64.0.1/",
    "http://[::1]/", "http://[fd00::1]/", "http://[fe80::1]/", "http://[::]/", "http://[::ffff:127.0.0.1]/",
    "http://[::ffff:10.0.0.1]/", "http://2130706433/", "http://0x7f000001/", "http://127.1/",
    "http://example.com:8080/", "https://example.com:22/", "http://user:pw@example.com/",
  ]) {
    assert.equal(rejection({ jobType: "article", url }).status, 400, url);
  }
  for (const url of ["https://example.com/a", "http://example.com:80/", "https://example.com:443/x", "http://93.184.216.34/",
    "http://[2606:4700:4700::1111]/", "https://172.32.0.1/"]) {
    assert.equal(blockedUrlReason(url), null, url);
  }
});

test("cap rejections map to 429 / 503 with plain messages", () => {
  assert.equal(quotaRejection({ ok: false, error: "ip_limit" })?.status, 429);
  assert.equal(quotaRejection({ ok: false, error: "server_busy" })?.status, 503);
  assert.equal(quotaRejection({ ok: false, error: "daily_capacity" })?.status, 503);
});

test("the hashed client IP is passed through", () => {
  const r = buildJobParams(USER, JOB, { jobType: "article", url: "https://example.com/" }, "abc");
  assert.ok("params" in r && r.params.p_ip_hash === "abc");
});

test("only allowlisted book formats reach Calibre", () => {
  for (const name of ["x.recipe", "x.downloaded_recipe", "x.html", "x.htm", "x.htmlz", "x.zip", "x.chm", "x.py", "noextension", "x.epub.recipe", "x.RECIPE"]) {
    const r = rejection({ jobType: "convert", fileName: name, fileSizeBytes: 1 });
    assert.equal(r.status, 415, name);
  }
  for (const name of ["a.epub", "a.PDF", "a.mobi", "a.azw3", "a.docx", "a.txt", "a.fb2", "a.cbz"]) {
    params({ jobType: "convert", fileName: name, fileSizeBytes: 1 });
  }
});

test("only image covers are accepted", () => {
  for (const name of ["c.recipe", "c.html", "c.svg", "c"]) {
    const r = rejection({ jobType: "convert", fileName: "a.epub", fileSizeBytes: 1, coverFileName: name, coverSizeBytes: 1 });
    assert.equal(r.status, 415, name);
  }
  params({ jobType: "convert", fileName: "a.epub", fileSizeBytes: 1, coverFileName: "c.PNG", coverSizeBytes: 1 });
});
