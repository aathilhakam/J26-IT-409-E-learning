import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { buildSync } from "esbuild";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";

// Use Vite's installed JSX compiler, with no additional test dependencies.
const compiled = buildSync({
  stdin: {
    contents: `export { default as LivePredictionState } from "./features/recommendations/components/LivePredictionState";
      export { default as RecommendationCard } from "./features/recommendations/components/RecommendationCard";
      export { default as AssignedMaterials } from "./features/content/components/AssignedMaterials";
      export { default as MaterialEditor } from "./features/content/components/MaterialEditor";
      export { default as LearningMaterialForm } from "./features/content/components/LearningMaterialForm";
      export { default as DashboardPage } from "./pages/DashboardPage";
      export { default as StudentProfilePage } from "./pages/StudentProfilePage";
      export { default as ArchiveStudentDialog } from "./features/students/components/ArchiveStudentDialog";
      export { default as ContentPage } from "./pages/ContentPage";
      export { default as AIRecommendationPanel } from "./features/recommendations/components/AIRecommendationPanel";
      export { default as StudentPathways } from "./features/recommendations/components/StudentPathways";
      export { default as WorksheetScores } from "./features/worksheets/WorksheetScores";
      export { WorksheetScorePreview } from "./features/worksheets/WorksheetScores";
      export { default as LearningEvidence } from "./features/evidence/LearningEvidence";
      export { default as WorksheetPrintActions } from "./features/worksheets/WorksheetPrintActions";`,
    resolveDir: fileURLToPath(new URL("../src", import.meta.url)),
  },
  bundle: true, write: false, platform: "node", format: "cjs",
  external: ["react", "react-dom"], define: { "import.meta.env": "{}" },
});
const compiledModule = { exports: {} };
new Function("require", "module", "exports", compiled.outputFiles[0].text)(createRequire(import.meta.url), compiledModule, compiledModule.exports);
const { LivePredictionState, RecommendationCard, AssignedMaterials, MaterialEditor, LearningMaterialForm, DashboardPage, StudentProfilePage, ArchiveStudentDialog, ContentPage, AIRecommendationPanel, StudentPathways, WorksheetScores, WorksheetScorePreview, LearningEvidence, WorksheetPrintActions } = compiledModule.exports;
const render = (component, props) => renderToStaticMarkup(React.createElement(component, props));

test("empty roster keeps archive success visible and import/create controls available", () => {
  const html = render(StudentProfilePage, { students: [], importNotice: "Student archived successfully. Their learning records are retained." });
  assert.match(html, /No learner selected/);
  assert.match(html, /role="status">Student archived successfully/);
  assert.match(html, /Import Excel/);
  assert.match(html, /Add student/);
});

test("roster shows student count and selectable cards", () => {
  const student = { id: "1", name: "Amaya Silva", grade: "Grade 1" };
  const html = render(StudentProfilePage, { students: [student], activeStudent: student });
  assert.match(html, /1 student/);
  assert.match(html, /View Amaya Silva&#x27;s profile/);
});

test("archive dialog retains confirmation, failure text, and busy controls", () => {
  const html = render(ArchiveStudentDialog, { activeStudent: { name: "Amaya Silva" }, archiveBusy: true, archiveFullName: "Amaya Silva", archiveError: "Name does not match" });
  assert.match(html, /Student full name/);
  assert.match(html, /role="alert">Name does not match/);
  assert.match(html, /disabled=""/);
  assert.match(html, /Removing…/);
});

test("content page retains upload formats and size limit", () => {
  const html = render(ContentPage, { items: [] });
  assert.match(html, /up to 20 MB per file/);
  assert.match(html, /\.pdf,.jpg,.jpeg,.png,.webp/);
});

test("AI feature renders separately with an empty student selection", () => {
  const html = render(AIRecommendationPanel, { studentId: "" });
  assert.match(html, /Random Forest prediction/);
  assert.match(html, /Personalized activities and teacher decisions appear separately/);
});

test("student pathway panel uses saved recommendations and does not repeat model predictions", () => {
  const html = render(StudentPathways, { student: { id: "s1", name: "Amaya Silva", grade: "Grade 1" }, initial: [{ id: "r1", status: "PENDING", activityTitle: "Count objects", objective: "Count to ten", reason: "Next curriculum objective", difficulty: "Beginner", createdAt: "2026-10-20T00:00:00Z" }] });
  assert.match(html, /Personalized learning pathways/);
  assert.match(html, /Count objects/);
  assert.match(html, /Next curriculum objective/);
  assert.doesNotMatch(html, /Predicted class:/);
});

test("student evidence keeps assessment entry and saved evidence views without observation or lesson entry forms", () => {
  const html = render(LearningEvidence, { student: { id: "s1", name: "Amaya Silva" } });
  assert.match(html, /Assessment results/);
  assert.match(html, /Teacher observations/);
  assert.match(html, /Historical lessons/);
  assert.doesNotMatch(html, /Save Historical Lesson|Lesson identifier|Sequence number|Occurrence date\/time/);
  assert.doesNotMatch(html, /Save observation|Record teacher observation/);
});

test("worksheet score workflow requires a material and previews the standard score template before import", () => {
  const materials = [{ id: "m1", title: "Fruits Worksheet", subject: "English", grade: "Grade 1" }];
  const empty = render(WorksheetScores, { materials });
  assert.match(empty, /Please select a worksheet material before downloading the score template/);
  assert.match(empty, /<option value="" selected="">Select a worksheet material<\/option>/);
  assert.match(empty, /disabled="">Download Score Template/);
  assert.match(empty, /disabled="">Preview Scores/);
  for (const label of ["Worksheet Assessment Scores", "Download Score Template", "Upload Completed Score Template", "Preview Scores", "Import Scores"]) assert.match(empty, new RegExp(label));
  assert.match(empty, /disabled="">Import Scores/);
  assert.match(empty, /0–10/);
  assert.doesNotMatch(empty, /Oshadi Madushanka|Bimsara Gunawardena|value="8"/);
});

test("worksheet score preview renders the exact eight template columns and uploaded values in order", () => {
  const html = render(WorksheetScorePreview, {
    preview: { valid: 1, invalid: 0, blank: 0, existing: 0 },
    rows: [{ row: 2, studentId: "stu-1", studentName: "Ashen Wijesinghe", grade: "Grade 1", worksheetId: "mat-1", title: "Fruits", subject: "English", scoreValue: "8.5", score: 8.5, feedback: "Good work", status: "VALID" }],
  });
  const headings = [...html.matchAll(/<th[^>]*>([^<]*)<\/th>/g)].map(match => match[1]);
  assert.deepEqual(headings, ["Student ID", "Student Name", "Grade", "Worksheet ID", "Worksheet Title", "Subject", "Score / 10", "Teacher Feedback"]);
  const cells = [...html.matchAll(/<td>(.*?)<\/td>/g)].map(match => match[1]);
  assert.deepEqual(cells, ["stu-1", "Ashen Wijesinghe", "Grade 1", "mat-1", "Fruits", "English", "8.5", "Good work"]);
});

test("worksheet print actions expose original worksheet viewing and printing without an answer key", () => {
  const html = render(WorksheetPrintActions, { materialId: "m1", title: "Fruits Worksheet" });
  assert.match(html, /View Worksheet/);
  assert.match(html, /Print Worksheet/);
  assert.doesNotMatch(html, /Teacher Answer Key|Answer key/);
});

const dashboardProps = {
  sessionUser: { name: "Teacher" }, activeId: "", students: [], records: [],
  profile: null, average: 0, areas: [], recommendations: [], busy: false, saving: false,
};

test("dashboard renders its greeting and empty classroom without crashing", () => {
  const html = render(DashboardPage, dashboardProps);
  assert.match(html, /Teacher!/);
  assert.match(html, /No classroom lessons assigned yet/);
  assert.match(html, /Lessons remaining/);
});

test("dashboard renders populated progress, learning activity and pending reviews", () => {
  const student = { id: "1", name: "Amaya Silva", grade: "Grade 1" };
  const html = render(DashboardPage, {
    ...dashboardProps, activeId: "1", students: [student], activeStudent: student,
    average: 70, profile: { assessmentCount: 1 },
    areas: [
      { domain: "Literacy", averagePercentage: 80 },
      { domain: "Numeracy", averagePercentage: 60 },
    ],
    records: [{ id: "r1", domain: "Literacy", score: 4, maxScore: 5, assessedAt: "2026-10-20" }],
    recommendations: [{ id: "p1", status: "PENDING", domain: "Numeracy", activityTitle: "Count blocks", createdAt: "2026-10-20", confidence: 0.8 }],
  });
  assert.match(html, /Count blocks/);
  assert.match(html, /Strongest area/);
  assert.match(html, /Explore Numeracy/);
  assert.match(html, /Count blocks/);
  assert.match(html, /Lessons advance automatically/);
});

test("material action opens a material-only dialog without learning record tabs or score fields", () => {
  const html = render(MaterialEditor, { defaultGrade: "Grade 1" });
  assert.match(html, /Add learning material/);
  assert.match(html, /material-editor-heading/);
  assert.match(html, /Save Material/);
  assert.match(html, /value="Grade 1"/);
  assert.doesNotMatch(html, /role="tablist"|name="score"|name="maxScore"|name="domain"|Save learning record/);
});

test("material form exposes required metadata, persistent file details and teacher-only visibility", () => {
  const html = render(LearningMaterialForm, { defaultGrade: "Grade 1" });
  for (const field of ["subject", "grade", "materialType", "file"]) assert.ok(html.includes(`name="${field}"`));
  assert.match(html, /value="Grade 1"/);
  assert.doesNotMatch(html, /name="title"|name="tags"|name="visibility"/);
  assert.match(html, /20 MB/);
  const editing = render(LearningMaterialForm, { initialMaterial: { title: "Worksheet", originalFileName: "count.pdf", size: 2048, tags: ["counting"] } });
  assert.match(editing, /count.pdf/);
  assert.match(editing, /2 KB/);
  assert.doesNotMatch(editing, /type="file"/);
  assert.match(editing, /Save changes/);
});

test("material editor provides private, selected students, and grade assignment", () => {
  const html = render(LearningMaterialForm, {});
  assert.match(html, /value="Grade 1"/);
  assert.doesNotMatch(html, /All my students|value="ALL"/);
  assert.match(html, /Student assignment/);
  for (const mode of ["LIBRARY", "STUDENTS", "GRADE"]) assert.ok(html.includes(`value="${mode}"`));
  const selected = render(LearningMaterialForm, { initialMaterial: { title: "Lesson", assignmentMode: "STUDENTS", assignedStudentIds: ["1"], tags: [] }, students: [{ id: "1", name: "Amaya", grade: "Grade 1" }, { id: "2", name: "Nimal", grade: "Grade 1" }] });
  assert.match(selected, /type="checkbox"/);
  assert.match(selected, /Amaya/);
  assert.match(selected, /Nimal/);
});

test("student profile assignments show only materials assigned to the selected student", () => {
  const student = { id: "1", name: "Amaya" };
  const items = [
    { id: "a", title: "Individual worksheet", assignmentMode: "STUDENTS", assignedStudentIds: ["1", "2"], size: 1024 },
    { id: "b", title: "Grade activity", assignmentMode: "GRADE", assignedStudentIds: ["1"], size: 2048 },
    { id: "c", title: "Other learner resource", assignmentMode: "STUDENTS", assignedStudentIds: ["2"], size: 100 },
    { id: "d", title: "Private resource", assignmentMode: "LIBRARY", assignedStudentIds: ["1"], size: 100 },
    { id: "e", title: "Legacy private resource", size: 100 },
  ];
  const html = render(AssignedMaterials, { student, items });
  assert.match(html, /Individual worksheet/);
  assert.match(html, /Grade activity/);
  assert.match(html, /2 assigned/);
  assert.match(html, /Download Individual worksheet/);
  assert.doesNotMatch(html, /Other learner resource|Private resource|Legacy private resource/);
  assert.match(render(AssignedMaterials, { student, items: [] }), /No assigned materials yet/);
  assert.match(render(AssignedMaterials, { student, busy: true }), /Loading assigned materials/);
});

test("dashboard selector lists Teacher first and keeps student choices", () => {
  const student = { id: "1", name: "Amaya", grade: "Grade 1" };
  const html = render(DashboardPage, { ...dashboardProps, students: [student] });
  const options = html.match(/<select[^>]*>(.*?)<\/select>/s)[1];
  assert.match(options, /^<option value="" selected="">Teacher<\/option>/);
  assert.match(options, /value="1">Grade 1 · Amaya/);
  assert.match(html, /No classroom lessons assigned yet/);
  assert.doesNotMatch(html, /Add your first learner/);
});

test("student roster stays available when dashboard Teacher view has no selected learner", () => {
  const html = render(StudentProfilePage, { students: [{ id: "1", name: "Amaya", grade: "Grade 1" }] });
  assert.match(html, /Your students/);
  assert.match(html, /View Amaya/);
  assert.doesNotMatch(html, /No learner selected/);
});

test("live prediction displays loading, insufficient data, actual class probabilities and no-match", () => {
  assert.match(render(LivePredictionState, { state: { loading: true } }), /saved learning data/);
  assert.match(render(LivePredictionState, { state: { error: true, insufficient: true, message: "Missing teacher observation" } }), /Insufficient learning data/);
  const html = render(LivePredictionState, { state: { predictedOutcome: "Waiting", probabilities: { Waiting: 0.7, Successful: 0.2, "Needs Practice": 0.1 }, status: "No suitable validated activity" } });
  assert.match(html, /Random Forest prediction: Waiting/); assert.match(html, /70.0%/);
  assert.match(html, /class probability/); assert.match(html, /No suitable validated activity/);
});

test("approved live recommendations keep teacher assignment controls and class label", () => {
  const html = render(RecommendationCard, { recommendation: { id: "r", source: "RANDOM_FOREST", status: "APPROVED", activityTitle: "Count", domain: "Math", createdAt: "2026-10-20", confidence: 0.8, predictedOutcome: "Successful", predictionProbabilities: { Successful: 0.8, Waiting: 0.1, "Needs Practice": 0.1 } }, materials: [{ id: "m", title: "Chart" }], onAssign() {} });
  assert.match(html, /Predicted class: Successful/); assert.match(html, /Assign approved activity/); assert.match(html, /Chart/);
});

test("recommendation cards show the persisted review and assignment state", () => {
  const base = { id: "r", source: "RANDOM_FOREST", activityTitle: "Count", domain: "Math", createdAt: "2026-10-20", confidence: 0.8 };
  for (const [status, extra, label] of [["PENDING", {}, "Pending Review"], ["APPROVED", {}, "Approved"], ["REJECTED", {}, "Rejected"], ["APPROVED", { assignedAt: "2026-10-20" }, "Assigned"]]) {
    assert.match(render(RecommendationCard, { recommendation: { ...base, status, ...extra }, showPrediction: false }), new RegExp(label));
  }
});
