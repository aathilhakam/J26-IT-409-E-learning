import test from "node:test";
import assert from "node:assert/strict";
import { buildSync } from "esbuild";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { evidencePayload, OBSERVATION_FIELDS, MEASURES, createEvidenceSubmission } from "../src/features/evidence/evidence.js";
const compiled = buildSync({ stdin: { contents: 'export { default as EvidenceForm } from "./frontend/src/features/evidence/EvidenceForm"; export { api } from "./frontend/src/services/api";', resolveDir: process.cwd() }, bundle: true, write: false, platform: "node", format: "cjs", external: ["react", "react-dom"], define: { "import.meta.env": "{}" } });
const module = { exports: {} };
new Function("require", "module", "exports", compiled.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const { EvidenceForm, api } = module.exports;
const observation = Object.fromEntries(OBSERVATION_FIELDS.map(([key, , choices]) => [key, choices[0]]));
const history = { lessonId: "LES021", sequenceNumber: "1", completionStatus: "Completed" };
const assessment = { sourceComponent: "TEACHER_ASSESSMENT", assessmentId: "TEST1", score: "4", maxScore: "5", completionStatus: "COMPLETED" };
for (const [kind, values] of Object.entries({ observation, history, assessment })) {
  test(`${kind} form renders accessible fields, student and submit control`, () => {
    const html = renderToStaticMarkup(React.createElement(EvidenceForm, { kind, student: { id: "s1", name: "Amaya" } }));
    assert.match(html, /For Amaya/); assert.match(html, /<label/); assert.match(html, /required=""/); assert.match(html, /type="submit"/);
    if (kind === "assessment") for (const key of MEASURES) assert.ok(html.includes(`name="${key}"`));
    if (kind === "observation") for (const [, , options] of OBSERVATION_FIELDS) for (const option of options) assert.ok(html.includes(option));
  });
  test(`${kind} validates required data and successfully saves then refreshes`, async () => {
    assert.throws(() => evidencePayload(kind, {}));
    const events = [];
    await createEvidenceSubmission()({ kind, values, student: { id: "s1" }, save: async (id, payload) => { assert.equal(id, "s1"); assert.deepEqual(payload, evidencePayload(kind, values)); events.push("save"); }, onState: state => events.push(state), onSaved: async () => events.push("refresh") });
    assert.deepEqual(events.filter(e => typeof e === "string"), ["save", "refresh"]);
    assert.equal(events.at(-1).success, "Evidence saved successfully.");
  });
  test(`${kind} failures never show success or refresh`, async () => {
    const states = []; let refreshed = false;
    await createEvidenceSubmission()({ kind, values, student: { id: "s1" }, save: async () => { throw Object.assign(new Error("Invalid input"), { status: 400 }); }, onState: state => states.push(state), onSaved: () => { refreshed = true; } });
    assert.equal(states.at(-1).error, "Invalid input"); assert.equal(refreshed, false); assert.ok(states.every(s => !s.success));
  });
}
test("validates categorical values, skills and dates; emits UTC timestamps", () => {
  assert.throws(() => evidencePayload("observation", { ...observation, confidence: "Unknown" }));
  assert.throws(() => evidencePayload("observation", { ...observation, demonstratedSkills: "x".repeat(181) }));
  assert.throws(() => evidencePayload("observation", { ...observation, observedAt: "2999-01-01T10:00" }));
  const payload = evidencePayload("observation", { ...observation, demonstratedSkills: "Counting\nCounting\nReading", observedAt: "2020-01-01T10:00" });
  assert.deepEqual(payload.demonstratedSkills, ["Counting", "Reading"]); assert.match(payload.observedAt, /Z$/);
});
test("history rejects nonpositive/fractional sequence, bad status and future timestamps", () => {
  for (const sequenceNumber of ["0", "-1", "1.5", "NaN"]) assert.throws(() => evidencePayload("history", { ...history, sequenceNumber }));
  assert.throws(() => evidencePayload("history", { ...history, completionStatus: "COMPLETED" }));
  assert.throws(() => evidencePayload("history", { ...history, occurredAt: "2999-01-01T10:00" }));
});
test("assessment sends only supplied measurements and preserves separate scalar score", () => {
  assert.deepEqual(evidencePayload("assessment", assessment).measures, {});
  const payload = evidencePayload("assessment", { ...assessment, Pre_Test: "0", Post_Test: "80", Unit_Test: "" });
  assert.deepEqual(payload.measures, { Pre_Test: 0, Post_Test: 80 }); assert.equal(payload.score, 4);
  for (const value of ["-1", "101", "Infinity"]) assert.throws(() => evidencePayload("assessment", { ...assessment, Pre_Test: value }));
  assert.throws(() => evidencePayload("assessment", { ...assessment, score: "6" }));
});
test("submission lock covers saving and refresh and blocks rapid duplicates", async () => {
  let finishSave, finishRefresh, calls = 0; const states = []; const submit = createEvidenceSubmission();
  const args = { kind: "history", values: history, student: { id: "s" }, save: () => { calls++; return new Promise(resolve => { finishSave = resolve; }); }, onState: s => states.push(s), onSaved: () => new Promise(resolve => { finishRefresh = resolve; }) };
  const pending = submit(args); await submit(args); assert.equal(calls, 1); assert.equal(states[0].busy, true);
  finishSave(); await Promise.resolve(); await submit(args); assert.equal(calls, 1);
  finishRefresh(); await pending; assert.equal(states.at(-1).busy, undefined);
});
test("archived students and validation errors never send a request", async () => {
  let calls = 0; const states = []; const submit = createEvidenceSubmission();
  for (const student of [{ id: "s", active: false }, null]) await submit({ kind: "history", values: history, student, save: () => { calls++; }, onState: s => states.push(s) });
  await submit({ kind: "history", values: {}, student: { id: "s" }, save: () => { calls++; }, onState: s => states.push(s) });
  assert.equal(calls, 0); assert.ok(states.every(s => s.error));
});
test("API methods preserve encoded student scope, session credentials, CSRF and exact payloads", async () => {
  const original = globalThis.fetch; const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return { ok: true, headers: new Headers(), json: async () => url.endsWith("/csrf") ? { csrfToken: "token" } : [] }; };
  try {
    for (const [create, list, path, payload] of [["addObservation", "observations", "observations", evidencePayload("observation", observation)], ["addHistoricalLesson", "lessonHistory", "history", evidencePayload("history", history)], ["addAssessment", "assessments", "assessments", evidencePayload("assessment", assessment)]]) {
      await api[create]("student/id", payload); const call = calls.at(-1);
      assert.equal(call.url, `/api/students/student%2Fid/${path}`); assert.equal(call.init.credentials, "same-origin"); assert.equal(call.init.headers["X-XSRF-TOKEN"], "token"); assert.deepEqual(JSON.parse(call.init.body), payload); assert.equal(call.init.method, "POST"); assert.ok(!("ownerId" in payload));
      await api[list]("student/id"); assert.equal(calls.at(-1).url, call.url);
    }
    globalThis.fetch = async () => ({ ok: false, status: 403, json: async () => ({ message: "Forbidden" }) });
    await assert.rejects(api.addHistoricalLesson("s", history), error => error.status === 403);
  } finally { globalThis.fetch = original; }
});

test("authorization and archive failures display useful messages without success", async () => {
  for (const [status, expected] of [[403, /not authorized/], [409, /archived/]]) {
    const states = [];
    await createEvidenceSubmission()({ kind: "history", values: history, student: { id: "s" }, save: async () => { throw Object.assign(new Error("Request failed"), { status }); }, onState: state => states.push(state) });
    assert.match(states.at(-1).error, expected); assert.ok(states.every(state => !state.success));
  }
  const html = renderToStaticMarkup(React.createElement(EvidenceForm, { kind: "history", student: { id: "s", name: "Archived", active: false } }));
  assert.match(html, /<fieldset disabled=""/); assert.match(html, /student is archived/);
});
test("save succeeds but failed refresh does not encourage resubmitting the saved record", async () => {
  const states = [];
  await createEvidenceSubmission()({ kind: "history", values: history, student: { id: "s" }, save: async () => ({}), onState: state => states.push(state), onSaved: async () => { throw new Error("Offline"); } });
  assert.equal(states.at(-1).success, "Evidence saved successfully."); assert.match(states.at(-1).error, /Reload the profile/);
});
