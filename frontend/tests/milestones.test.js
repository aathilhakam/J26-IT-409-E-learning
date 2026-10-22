import test from "node:test";
import assert from "node:assert/strict";
import { buildSync } from "esbuild";
import { createRequire } from "node:module";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
const b=buildSync({stdin:{contents:'export {default as MilestoneProgress,MilestoneSummary,milestoneReviewStatus} from "./frontend/src/features/recommendations/components/MilestoneProgress";export {default as ThreeLessonPerformance} from "./frontend/src/features/recommendations/components/ThreeLessonPerformance";export {api} from "./frontend/src/services/api";',resolveDir:process.cwd()},bundle:true,write:false,platform:"node",format:"cjs",external:["react","react-dom"],define:{"import.meta.env":"{}"}});
const m={exports:{}};new Function("require","module","exports",b.outputFiles[0].text)(createRequire(import.meta.url),m,m.exports);
const {MilestoneProgress,MilestoneSummary,milestoneReviewStatus,ThreeLessonPerformance,api}=m.exports;
const render=(c,p)=>renderToStaticMarkup(React.createElement(c,p));
test("milestone view separates a selected student's progress from classroom and upload totals",()=>{
 assert.match(render(MilestoneProgress,{}),/Select a student/);
 assert.match(render(MilestoneProgress,{studentId:"s1"}),/Loading this student/);
 assert.match(render(MilestoneSummary,{progress:{lessonsTowardNext:2,nextMilestone:6,completedLessons:5}}),/2 of 3 lessons completed.*milestone 6.*5 eligible/);
 assert.match(render(MilestoneSummary,{progress:{lessonsTowardNext:0,nextMilestone:9,completedLessons:6}}),/0 of 3 lessons completed.*milestone 9/);
});
test("review and assigned statuses reflect actual recommendation decisions",()=>{
 const milestone={milestone:3,status:"GENERATED"};
 for(const [rows,status] of [[[{completionMilestone:3,status:"PENDING"}],"Awaiting teacher review"],[[{completionMilestone:3,status:"APPROVED"}],"Approved · ready to assign"],[[{completionMilestone:3,status:"REJECTED"}],"Rejected by teacher"],[[{completionMilestone:3,status:"APPROVED",assignedAt:"2026-10-20"}],"Approved activities assigned"]])assert.equal(milestoneReviewStatus(milestone,rows),status);
 assert.match(milestoneReviewStatus({status:"INSUFFICIENT_DATA"},[]),/More learning evidence/);
 assert.match(milestoneReviewStatus({status:"NO_MATCH"},[]),/No suitable/);
});
test("three lesson summary renders the saved scores, dates, feedback, average and trend",()=>{
 const html=render(ThreeLessonPerformance,{performance:{averagePercentage:70,changePercentagePoints:20,trend:"Improving",lessons:[
  {materialId:"a",worksheetTitle:"Lesson A",score:6,maxScore:10,percentage:60,assessmentDate:"2026-10-18",teacherFeedback:"Needs more practice"},
  {materialId:"b",worksheetTitle:"Lesson B",score:7,maxScore:10,percentage:70,assessmentDate:"2026-10-19",teacherFeedback:"Good progress"},
  {materialId:"c",worksheetTitle:"Lesson C",score:8,maxScore:10,percentage:80,assessmentDate:"2026-10-20",teacherFeedback:"Excellent work"}
 ]}});
 for(const value of ["Lesson 1: Lesson A","6 / 10 · 60.0%","Lesson 2: Lesson B","7 / 10 · 70.0%","Lesson 3: Lesson C","8 / 10 · 80.0%","Average","70.0%","Improving","+20.0 points","Needs more practice","Excellent work"])assert.ok(html.includes(value),value);
});
test("milestone reads and retries use owned backend APIs and never send frontend features",async()=>{
 const original=globalThis.fetch,calls=[];globalThis.fetch=async(url,init)=>{calls.push({url,init});return {ok:true,headers:new Headers(),json:async()=>url.endsWith("csrf")?{csrfToken:"test-csrf"}:{completedLessons:3}};};
 try{await api.milestoneProgress("s/1");await api.evaluateMilestones("s/1");assert.equal(calls[0].url,"/api/recommendations/student/s%2F1/milestones");const last=calls.at(-1);assert.equal(last.url,"/api/recommendations/student/s%2F1/milestones?retry=true");assert.equal(last.init.method,"POST");assert.equal(last.init.credentials,"same-origin");assert.equal(last.init.headers["X-XSRF-TOKEN"],"test-csrf");assert.equal(last.init.body,undefined);}finally{globalThis.fetch=original;}
});
