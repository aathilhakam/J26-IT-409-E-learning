import test from "node:test";
import assert from "node:assert/strict";
import { buildSync } from "esbuild";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
const bundle = buildSync({ stdin: { contents: 'export {default as DashboardPage} from "./frontend/src/pages/DashboardPage"; export {default as ClassroomLessons} from "./frontend/src/features/dashboard/components/ClassroomLessons"; export {loadTeacherDashboard} from "./frontend/src/features/dashboard/useTeacherDashboard"; export {api} from "./frontend/src/services/api";', resolveDir: process.cwd() }, bundle: true, write: false, platform: "node", format: "cjs", external: ["react", "react-dom"], define: { "import.meta.env": "{}" } });
const module = { exports: {} }; new Function("require", "module", "exports", bundle.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const { DashboardPage, ClassroomLessons, loadTeacherDashboard, api } = module.exports;
const render = (Component, props) => renderToStaticMarkup(React.createElement(Component, props));
const lesson = { id: "m1", title: "Shared counting lesson", description: "Count together", subject: "Math", grade: "Grade 1", materialType: "Worksheet", assignmentMode: "GRADE", assignmentGrade: "Grade 1", assignedAt: "2026-10-20", assigned: 3, completed: 1, inProgress: 1, notStarted: 1, overdue: null, unrecorded: 0, recipients: [] };
const base = { sessionUser: { name: "Teacher" }, students: [], activeId: "", records: [], areas: [], recommendations: [], average: 0 };
test("teacher dashboard shows actual combined statistics independently of selected-student profile", () => {
  const html = render(DashboardPage, { ...base, profile: { assessmentCount: 999 }, average: 99, classroom: { statistics: { studentsEnrolled: 18, learningRecords: 12, teacherMaterials: 12, learningProgress: 25, completedLessons: 3, remainingLessons: 9, pendingReviews: 4 }, lessons: [] } });
  assert.match(html, />18</); assert.match(html, />12</); assert.match(html, />25%</); assert.match(html, />9</); assert.doesNotMatch(html, />999</);
  assert.match(html, /All uploaded learning materials/); assert.match(html, /3 of 12 lessons completed/);
  assert.doesNotMatch(html, /Teacher Materials Repository|Pending reviews/); assert.match(html, /href="\/dashboard\/content"/);
  const labels=["Students enrolled", "Learning records", "Learning progress", "Lessons remaining"];
  for(let i=1;i<labels.length;i++) assert.ok(html.indexOf(labels[i-1])<html.indexOf(labels[i]));
});
test("summary and feed have loading, actionable error and empty states", () => {
  assert.match(render(DashboardPage, { ...base, classroom: { loading: true } }), /Loading classroom lessons/);
  const error = render(DashboardPage, { ...base, classroom: { error: "Server unavailable" } });
  assert.match(error, /Server unavailable/); assert.match(error, /Retry statistics/); assert.match(error, /Retry classroom data/);
  assert.match(render(ClassroomLessons, { state: { lessons: [] } }), /Private-library materials stay/);
});
test("shared lesson appears once with assigned and completed counts only", () => {
  const html = render(ClassroomLessons, { state: { lessons: [lesson] } });
  assert.equal((html.match(/<h3><a[^>]*>Shared counting lesson<\/a><\/h3>/g) || []).length, 1);
  for (const text of ["3 assigned", "1 completed", "Grade group", "Open Current Lesson"]) assert.ok(html.includes(text));
  assert.match(html, /href="\/dashboard\/lessons\/m1"/); assert.doesNotMatch(html, /in progress|not started|Overdue|overdue|status unrecorded/);
});
test("feed shows only two weekly lessons and explains queued progression", () => {
  const html = render(ClassroomLessons, { state: { lessons: Array.from({ length: 12 }, (_, index) => ({ ...lesson, id: String(index) })) } });
  assert.equal((html.match(/class="classroom-lesson"/g) || []).length, 2); assert.match(html, /10 more lessons queued/); assert.match(html, /Current week lesson/); assert.match(html, /Next week/); assert.doesNotMatch(html, /Show more lessons/);
});
test("dashboard refresh fetches scoped statistics, common lessons and pending reviews; errors propagate", async () => {
  const original = globalThis.fetch; const calls = [];
  globalThis.fetch = async url => { calls.push(url); return { ok: true, headers: new Headers(), json: async () => url.includes("statistics") ? { learningRecords: 2, completedLessons: 0, remainingLessons: 2 } : [] }; };
  try {
    const result = await loadTeacherDashboard("student/id"); assert.equal(result.statistics.learningRecords, 2);
    assert.deepEqual(calls.sort(), ["/api/dashboard/lessons", "/api/dashboard/reviews", "/api/dashboard/lessons/history", "/api/dashboard/statistics?studentId=student%2Fid"].sort());
    globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ message: "Try again" }) });
    await assert.rejects(loadTeacherDashboard(""), /Try again/);
  } finally { globalThis.fetch = original; }
});
test("assignment status API sends only status with encoded IDs and authenticated CSRF", async () => {
  const original = globalThis.fetch; const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({url, init}); return {ok: true, headers: new Headers(), json: async () => url.endsWith("csrf") ? {csrfToken: "secure"} : {}}; };
  try { await api.updateAssignmentStatus("material/id", "student/id", "COMPLETED"); const {url, init} = calls.at(-1); assert.equal(url, "/api/dashboard/lessons/material%2Fid/students/student%2Fid"); assert.equal(init.method, "PATCH"); assert.equal(init.credentials, "same-origin"); assert.equal(init.headers["X-XSRF-TOKEN"], "secure"); assert.deepEqual(JSON.parse(init.body), {status: "COMPLETED"}); }
  finally {globalThis.fetch = original;}
});

test("weekly lessons open dedicated pages instead of one status form per student", () => {
  const html = render(ClassroomLessons, { state: { lessons: [lesson, { ...lesson, id: "m2" }] } });
  assert.match(html, /Open Current Lesson/);
  assert.match(html, /Preview next week’s lesson/);
  assert.match(html, /href="\/dashboard\/lessons\/m2"/);
  assert.doesNotMatch(html, /Assignment status for|<select|Mark lesson complete/);
});

test("dashboard refetch uses current persisted counts after a review or status change", async () => {
  const original=globalThis.fetch;let statistics={teacherMaterials:3,learningRecords:2,learningProgress:0,pendingReviews:1,completedLessons:0,remainingLessons:3};
  globalThis.fetch=async url=>({ok:true,headers:new Headers(),json:async()=>url.includes("statistics")?statistics:[]});
  try {
    assert.equal((await loadTeacherDashboard("")).statistics.pendingReviews,1);
    statistics={...statistics,learningProgress:50,pendingReviews:0};
    const refreshed=await loadTeacherDashboard("");assert.equal(refreshed.statistics.pendingReviews,0);assert.equal(refreshed.statistics.learningProgress,50);assert.equal(refreshed.statistics.teacherMaterials,3);
  }finally{globalThis.fetch=original;}
});

test("outdated statistics preserve the lesson feed without fabricating totals", async () => {
 const original=globalThis.fetch;
 globalThis.fetch=async url=>({ok:true,headers:new Headers(),json:async()=>url.includes("statistics")?{learningRecords:0}:url.includes("history")?[]:url.includes("lessons")?[lesson]:[]});
 try{const result=await loadTeacherDashboard("");assert.match(result.statisticsError,/Restart the backend/);assert.equal(result.statistics,null);assert.equal(result.lessons[0].id,"m1");const html=render(DashboardPage,{...base,classroom:result});assert.match(html,/Shared counting lesson/);assert.match(html,/>—</);}finally{globalThis.fetch=original;}
});

test("history is collapsed by default, retains completed details, and supports grade selection", () => {
 const complete={...lesson,id:"finished",originalFileName:"counting.pdf",completed:3,completedAt:"2026-10-20T10:00:00Z"};
 const html=render(ClassroomLessons,{state:{lessons:[lesson],history:[complete],grade:"Grade 1",grades:["Grade 1","Grade 2"],setGrade:()=>{}}});
 assert.match(html,/<details class="completed-lessons-history">/);assert.doesNotMatch(html,/<details[^>]*open/);
 assert.match(html,/Completed Lessons History/);assert.match(html,/counting.pdf/);assert.match(html,/Completed Oct 20/);assert.match(html,/Review completed lesson/);assert.match(html,/Lesson grade group/);
 assert.equal((html.match(/href="\/dashboard\/lessons\/finished"/g)||[]).length,2);
 const empty=render(ClassroomLessons,{state:{lessons:[],history:[complete]}});assert.match(empty,/All eligible lessons completed/);assert.match(empty,/Review completed lesson/);
 const zero=render(ClassroomLessons,{state:{lessons:[{...lesson,assigned:0,completed:0}],history:[]}});assert.match(zero,/No students assigned/);
});
test("queue and history API requests apply the same encoded grade restriction", async()=>{
 const original=globalThis.fetch,calls=[];
 globalThis.fetch=async url=>{calls.push(url);return {ok:true,headers:new Headers(),json:async()=>url.includes("statistics")?{completedLessons:0,remainingLessons:0}:[]};};
 try {await loadTeacherDashboard("","Grade 1");assert.ok(calls.includes("/api/dashboard/lessons?grade=Grade%201"));assert.ok(calls.includes("/api/dashboard/lessons/history?grade=Grade%201"));}finally{globalThis.fetch=original;}
});

test("a completion racing queue and history requests never duplicates a lesson", async()=>{
 const original=globalThis.fetch;
 globalThis.fetch=async url=>({ok:true,headers:new Headers(),json:async()=>url.includes("statistics")?{completedLessons:1,remainingLessons:0}:url.includes("lessons")?[lesson]:[]});
 try{const state=await loadTeacherDashboard("");assert.equal(state.lessons.length,0);assert.equal(state.history.length,1);}finally{globalThis.fetch=original;}
});
