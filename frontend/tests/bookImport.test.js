import test from "node:test";
import assert from "node:assert/strict";
import { buildSync } from "esbuild";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
const bundle = buildSync({ stdin: { contents: 'export {default as GradeOneBookImport} from "./frontend/src/features/content/components/GradeOneBookImport"; export {api} from "./frontend/src/services/api";', resolveDir: process.cwd() }, bundle: true, write: false, platform: "node", format: "cjs", external: ["react", "react-dom"], define: { "import.meta.env": "{}" } });
const module = { exports: {} };
new Function("require", "module", "exports", bundle.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const { GradeOneBookImport, api } = module.exports;
test("existing library exposes the Grade 1 import action without starting an import", () => {
  const html = renderToStaticMarkup(React.createElement(GradeOneBookImport));
  assert.match(html, /Import Grade 1 Books/);
  assert.doesNotMatch(html, /import summary|IMPORTED/);
});
test("book import APIs use the existing content namespace, session and CSRF", async () => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return { ok: true, headers: new Headers(), json: async () => url.endsWith("csrf") ? { csrfToken: "token" } : [] }; };
  try {
    await api.gradeOneBooks();
    const books = [{ filename: "Weather.pdf", title: "Weather", description: "", subject: "", materialType: "Learning Book" }];
    await api.importGradeOneBooks(books);
    assert.equal(calls[0].url, "/api/content/import-grade-one");
    const last = calls.at(-1);
    assert.equal(last.url, "/api/content/import-grade-one");assert.equal(last.init.method, "POST");
    assert.equal(last.init.credentials, "same-origin"); assert.equal(last.init.headers["X-XSRF-TOKEN"], "token");
    assert.deepEqual(JSON.parse(last.init.body), books);
    globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ message: "Retry later" }) });
    await assert.rejects(api.gradeOneBooks(), /Retry later/);
  } finally { globalThis.fetch = original; }
});
