import test from "node:test";
import assert from "node:assert/strict";
import { buildSync } from "esbuild";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { refreshLessonCompletion, applySharedFeedback, emptyFeedback, feedbackRows, lessonFeedbackPayload, isLessonPath } from "../src/features/dashboard/lessonFeedback.js";
const bundle = buildSync({ stdin: { contents: 'export {default as LessonWorkspacePage} from "./frontend/src/pages/LessonWorkspacePage"; export {api} from "./frontend/src/services/api"; export {default as TeacherFeedback} from "./frontend/src/features/feedback/TeacherFeedback"; export {default as FeedbackFields} from "./frontend/src/features/feedback/FeedbackFields"; export {SubmittedFeedbackList,filterSubmittedFeedback} from "./frontend/src/features/feedback/SubmittedFeedback";', resolveDir: process.cwd() }, bundle: true, write: false, platform: "node", format: "cjs", external: ["react", "react-dom"], define: { "import.meta.env": "{}" } });
const module = { exports: {} };new Function("require", "module", "exports", bundle.outputFiles[0].text)(createRequire(import.meta.url),module,module.exports);
const { LessonWorkspacePage, TeacherFeedback, FeedbackFields, api, SubmittedFeedbackList, filterSubmittedFeedback } = module.exports;
const observed = { participation: "High", attentionLevel: "Medium", confidence: "High", collaboration: "Good", learningDifficulty: "None identified", learningStyle: "Visual learner", demonstratedSkills: "Named birds\nNamed birds", status: "COMPLETED" };
const workspace = { lesson: { recipients: [{ studentId: "s1", name: "Amaya", activeStudent: true, status: "NOT_STARTED" }, { studentId: "s2", name: "Nimal", activeStudent: true, status: "UNRECORDED" }, { studentId: "s3", name: "Archived", activeStudent: false, status: "NOT_STARTED" }] }, saved: [] };
test("lesson routes support direct links and browser history without accepting arbitrary routes", () => {
  assert.ok(isLessonPath("/dashboard/lessons/material-id"));assert.ok(isLessonPath("/dashboard/lessons/material%2Fid"));
  for(const path of ["/dashboard/lessons/", "/dashboard/lessons/a/feedback", "/dashboard/content", "/dashboard/lessons/%E0%A4", "/dashboard/lessons/%"]) assert.equal(isLessonPath(path),false);
  assert.match(renderToStaticMarkup(React.createElement(LessonWorkspacePage,{materialId:"m1"})),/Loading lesson and saved classroom feedback/);
});
test("lesson feedback export reports saved count and surfaces API failures", async () => {
  const originalFetch=globalThis.fetch;
  try {
    globalThis.fetch=async()=>({ok:true,headers:{get:key=>key==="X-Feedback-Count"?"2":key==="Content-Disposition"?'attachment; filename="Song_Book_Teacher_Feedback.xlsx"':null},blob:async()=>new Blob(["workbook"])});
    const downloaded=await api.downloadLessonTeacherFeedback("lesson/id");assert.equal(downloaded.feedbackCount,2);assert.equal(downloaded.filename,"Song_Book_Teacher_Feedback.xlsx");assert.equal(downloaded.blob.size,8);
    globalThis.fetch=async()=>({ok:false,status:403,json:async()=>({message:"Lesson not found"})});await assert.rejects(api.downloadLessonTeacherFeedback("lesson/id"),/Lesson not found/);
  } finally { globalThis.fetch=originalFetch; }
});
test("shared feedback changes only selected active students and preserves blank-field overrides", () => {
  const rows=feedbackRows(workspace).map(row=>({...row, selected: row.studentId !== "s2", studentAnswer: "Individual response"}));
  const next=applySharedFeedback(rows,{...emptyFeedback(),...observed,question:"Which bird is this?",studentAnswer:""});
  assert.equal(next[0].attentionLevel,"Medium"); assert.equal(next[0].question,""); assert.equal(next[0].studentAnswer,"Individual response");assert.ok(next[0].dirty);
  assert.equal(next[1].attentionLevel,"");assert.equal(next[2].attentionLevel,"");assert.equal(rows[0].attentionLevel,"");
});
test("one submission contains selected student feedback and training-compatible observations with no inferred scores", () => {
  const rows=applySharedFeedback(feedbackRows(workspace),{...observed,question:"Name this bird",studentAnswer:"Parrot",improvement:"Answered without prompting",feedback:"Practise two more names"},{replaceExisting:true}).map(row => ({...row, demonstratedSkills: observed.demonstratedSkills, studentAnswer: "Parrot"}));
  const payload=lessonFeedbackPayload(rows);
  assert.equal(payload.students.length,2);assert.deepEqual(payload.students[0].observation.demonstratedSkills,["Named birds"]);
  assert.equal(payload.students[0].studentAnswer,"Parrot");assert.equal(payload.students[0].status,undefined);assert.equal(payload.students[0].improvement,"Answered without prompting");
  assert.equal(payload.students[0].score,undefined);assert.equal(payload.students[0].ownerId,undefined);
  rows[1].confidence="";assert.throws(()=>lessonFeedbackPayload(rows),/Nimal: Choose confidence/);
  assert.throws(()=>lessonFeedbackPayload(rows.map(row=>({...row,selected:false}))),/Select at least one/);
});
test("saved observations and answers are restored on reload without inventing missing observations", () => {
  const rows=feedbackRows({...workspace,saved:[{studentId:"s1",observation:{...observed,demonstratedSkills:["Named birds"],studentAnswer:"Crow",feedback:"Keep practising"}}]});
  assert.equal(rows[0].studentAnswer,"Crow");assert.equal(rows[0].demonstratedSkills,"Named birds");assert.equal(rows[0].dirty,false);
  assert.equal(rows[1].attentionLevel,"");assert.equal(rows[1].status,"");assert.equal(rows[2].selected,false);
});
test("lesson details and batch feedback use authenticated content access and CSRF; API failures propagate",async()=>{
  const original=globalThis.fetch;const calls=[];
  globalThis.fetch=async(url,init)=>{calls.push({url,init});return{ok:true,headers:new Headers(),json:async()=>url.endsWith("csrf")?{csrfToken:"secure"}:{}};};
  try {
    await api.lessonWorkspace("lesson/id");assert.equal(calls[0].url,"/api/dashboard/lessons/lesson%2Fid");assert.equal(calls[0].init.credentials,"same-origin");
    const payload=lessonFeedbackPayload(applySharedFeedback(feedbackRows(workspace),observed));
    await api.saveLessonFeedback("lesson/id",payload);const last=calls.at(-1);
    assert.equal(last.url,"/api/dashboard/lessons/lesson%2Fid/feedback");assert.equal(last.init.method,"POST");assert.equal(last.init.headers["X-XSRF-TOKEN"],"secure");assert.deepEqual(JSON.parse(last.init.body),payload);
    globalThis.fetch=async()=>({ok:false,status:409,json:async()=>({message:"Current week's lesson only"})});await assert.rejects(api.saveLessonFeedback("m",payload),/Current week's lesson only/);
  } finally {globalThis.fetch=original;}
});

const submission = {id:"submitted-1",studentId:"s1",studentName:"Amaya",grade:"Grade 1",materialId:"m1",lessonTitle:"Birds",submissionStatus:"SUBMITTED",activityStatus:"COMPLETED",submittedAt:"2026-10-20T04:00:00Z",improvement:"Recognises names",feedback:"Try two more",observation:{}};
test("submitted feedback excludes drafts and filters by actual student, lesson and activity status",()=>{
  const items=[submission,{...submission,id:"draft",submissionStatus:"DRAFT",submittedAt:null},{...submission,id:"other",studentId:"s2",materialId:"m2",activityStatus:"IN_PROGRESS"}];
  assert.equal(filterSubmittedFeedback(items).length,2);assert.equal(filterSubmittedFeedback(items,{student:"s1",lesson:"m1",status:"COMPLETED"})[0].id,"submitted-1");
  assert.equal(filterSubmittedFeedback(items,{status:"NOT_STARTED"}).length,0);
});
test("submitted history groups a student's feedback by lesson with latest revision visible",()=>{
  const html=renderToStaticMarkup(React.createElement(SubmittedFeedbackList,{studentView:true,items:[submission,{...submission,id:"revision-2",submittedAt:"2026-10-20T05:00:00Z",feedback:"New revision"}]}));
  assert.match(html,/Teacher Feedback/);assert.match(html,/Latest for this lesson/);assert.match(html,/Birds/);assert.match(html,/New revision/);assert.match(html,/Try two more/);assert.match(html,/Recognises names/);
  assert.ok(html.indexOf("New revision")<html.indexOf("Try two more"));
});
test("submitted feedback has loading, error, retry and empty states",()=>{
  const render=props=>renderToStaticMarkup(React.createElement(SubmittedFeedbackList,props));
  assert.match(render({loading:true}),/Loading teacher feedback/);assert.match(render({error:"Server unavailable"}),/Retry teacher feedback/);
  assert.match(render({items:[]}),/No submitted feedback yet/);
  assert.doesNotMatch(render({items:[{...submission,submissionStatus:"DRAFT",submittedAt:null}]}),/Recognises names/);
});

test("Teacher Feedback provides eligible student selection and review without the retired panel or removed fields",()=>{
  const html=renderToStaticMarkup(React.createElement(TeacherFeedback,{workspace:{...workspace,weekPosition:1,completed:false,lesson:{...workspace.lesson,title:"Birds"}},materialId:"m1",onRefresh:()=>{}}));
  assert.match(html,/<h2>Teacher Feedback<\/h2>/);assert.match(html,/Select all eligible students/);assert.match(html,/aria-label="Select Amaya"/);assert.match(html,/aria-label="Select Nimal"/);assert.doesNotMatch(html,/aria-label="Select Archived"/);
  assert.match(html,/Submit feedback for 2 students/);assert.ok(html.indexOf("Submit feedback for 2 students") < html.indexOf('class="lesson-feedback-roster"'));assert.match(html,/Review feedback/);
  assert.doesNotMatch(html,/Submitted Feedback|Retry submitted feedback|Skills demonstrated|Question asked|Student&#x27;s answer/);
  const individual=renderToStaticMarkup(React.createElement(FeedbackFields,{values:emptyFeedback(),prefix:"Amaya",onChange:()=>{}}));
  assert.match(individual,/Improvement observed/);assert.match(individual,/Feedback \/ next step/);assert.doesNotMatch(individual,/Skills demonstrated|Question asked|answer \/ response|Learning difficulty|Activity status/);
});
test("ten selected students produce ten unique submission associations and only one individual adjustment changes",()=>{
  const ten={...workspace,lesson:{recipients:Array.from({length:10},(_,i)=>({studentId:`s${i}`,name:`Student ${i}`,activeStudent:true,status:"NOT_STARTED"}))},saved:[]};
  const rows=applySharedFeedback(feedbackRows(ten),{...observed,feedback:"Common next step",improvement:"Shared observation"},{replaceExisting:true});
  rows[0].feedback="Individual adjustment";
  const payload=lessonFeedbackPayload(rows);assert.equal(payload.students.length,10);assert.equal(new Set(payload.students.map(row=>row.studentId)).size,10);
  assert.equal(payload.students[0].feedback,"Individual adjustment");assert.ok(payload.students.slice(1).every(row=>row.feedback==="Common next step"));
  rows.slice(1).forEach(row=>{row.selected=false;});assert.equal(lessonFeedbackPayload(rows).students.length,1);
});

test("feedback submits without learning difficulty and common feedback cannot set that removed field",()=>{
  const rows=applySharedFeedback(feedbackRows(workspace),{...observed,learningDifficulty:"Vocabulary development"},{replaceExisting:true});
  const payload=lessonFeedbackPayload(rows);assert.equal(payload.students[0].observation.learningDifficulty,"");
  assert.equal(payload.students[0].observation.participation,"High");
});

test("feedback no longer applies or submits an activity-status change",()=>{
  const rows=applySharedFeedback(feedbackRows(workspace),{...observed,status:"COMPLETED"},{replaceExisting:true});
  assert.equal(rows[0].status,"NOT_STARTED");assert.equal(rows[1].status,"");
  const payload=lessonFeedbackPayload(rows);assert.ok(payload.students.every(row=>!("status" in row)));
});

test("submit can merge completed common fields directly without a separate Apply click",()=>{
  const rows=feedbackRows(workspace);
  const ready=applySharedFeedback(rows,{...observed,feedback:"Practise together"},{preserveIndividualEdits:true});
  const payload=lessonFeedbackPayload(ready);
  assert.equal(payload.students.length,2);assert.equal(payload.students[0].observation.participation,"High");assert.equal(payload.students[1].feedback,"Practise together");
});
test("automatic submit merging preserves individual adjustments even when replacing other common values",()=>{
  const rows=feedbackRows(workspace).map(row=>({...row,selected:row.studentId==="s1",feedback:"Individual next step",confidence:"Low",individualFields:{feedback:true,confidence:true}}));
  const ready=applySharedFeedback(rows,{...observed,feedback:"Common next step"},{replaceExisting:true,preserveIndividualEdits:true});
  const payload=lessonFeedbackPayload(ready);
  assert.equal(payload.students.length,1);assert.equal(payload.students[0].feedback,"Individual next step");assert.equal(payload.students[0].observation.confidence,"Low");assert.equal(payload.students[0].observation.participation,"High");
});

test("workspace refresh trusts persisted assignment completion without promoting from feedback", async () => {
 const saved={lesson:{recipients:[{studentId:"s1",status:"IN_PROGRESS"}]},completed:false,submitted:[{studentId:"s1",submissionStatus:"SUBMITTED",submittedAt:"2026-10-20"}]};
 let calls=0;
 const api={lessonWorkspace:async id=>{assert.equal(id,"m1");return saved;},completeClassroomLesson:async()=>calls++};
 assert.equal((await refreshLessonCompletion(api,"m1")).completed,false);assert.equal(calls,0);
 saved.completed=true;saved.lesson.recipients[0].status="COMPLETED";
 assert.equal((await refreshLessonCompletion(api,"m1")).completed,true);assert.equal(calls,0);
});
