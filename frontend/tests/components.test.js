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
      export { default as TeacherOutcomeReview } from "./features/recommendations/components/TeacherOutcomeReview";
      export { latestPrediction, OutcomeDecision, predictionModelClassStatus } from "./features/recommendations/components/AIRecommendationPanel";
      export { default as StudentPathways } from "./features/recommendations/components/StudentPathways";
      export { default as WorksheetScores } from "./features/worksheets/WorksheetScores";
      export { default as PrototypeFeatureSnapshotImport } from "./features/activities/PrototypeFeatureSnapshotImport";
      export { completedPracticeAreas } from "./features/recommendations/components/PrototypePracticeAlerts";
      export { WorksheetScorePreview } from "./features/worksheets/WorksheetScores";
      export { default as LearningEvidence } from "./features/evidence/LearningEvidence";
      export { default as WorksheetPrintActions } from "./features/worksheets/WorksheetPrintActions";
      export { WorkspaceLayout } from "./components/layout/WorkspaceLayout";`,
    resolveDir: fileURLToPath(new URL("../src", import.meta.url)),
  },
  bundle: true, write: false, platform: "node", format: "cjs",
  external: ["react", "react-dom"], define: { "import.meta.env": "{}" },
});
const compiledModule = { exports: {} };
new Function("require", "module", "exports", compiled.outputFiles[0].text)(createRequire(import.meta.url), compiledModule, compiledModule.exports);
const { LivePredictionState, RecommendationCard, AssignedMaterials, MaterialEditor, LearningMaterialForm, DashboardPage, StudentProfilePage, ArchiveStudentDialog, ContentPage, AIRecommendationPanel, TeacherOutcomeReview, latestPrediction, OutcomeDecision, predictionModelClassStatus, StudentPathways, WorksheetScores, WorksheetScorePreview, PrototypeFeatureSnapshotImport, completedPracticeAreas, LearningEvidence, WorksheetPrintActions, WorkspaceLayout } = compiledModule.exports;
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

test("content page retains upload formats and per-file size limit", () => {
  const html = render(ContentPage, { items: [] });
  assert.match(html, /up to 20 MB per file/);
  assert.match(html, /\.pdf,.jpg,.jpeg,.png,.webp/);
});

test("main workspace navigation no longer includes Worksheet Scores", () => {
  const html = render(WorkspaceLayout, { children: React.createElement("div", null, "Page") });
  assert.match(html, /Upload &amp; Content/);
  assert.doesNotMatch(html, /Worksheet Scores|dashboard\/scores/);
});

test("teacher practice alerts identify only completed saved areas below 75 percent", () => {
  const areas = completedPracticeAreas([
    { materialId: "song", worksheetTitle: "Song Book", subject: "Songs", score: 5, maxScore: 10, completionStatus: "COMPLETED" },
    { materialId: "song", worksheetTitle: "Song Book", subject: "Songs", score: 6, maxScore: 10, completionStatus: "COMPLETED" },
    { materialId: "math", worksheetTitle: "Math", score: 4, maxScore: 10, completionStatus: "IN_PROGRESS" },
    { materialId: "reading", worksheetTitle: "Reading", score: 8, maxScore: 10, completionStatus: "COMPLETED" },
  ]);
  assert.equal(areas.length, 1);
  assert.equal(areas[0].title, "Song Book");
  assert.ok(Math.abs(areas[0].percentage - 55) < 1e-9);
});

test("prototype snapshot panel exposes authenticated lesson batch workflow", () => {
  const html = render(PrototypeFeatureSnapshotImport, { grade: "Grade 1", materials: [{ id: "song-book", title: "Song Book – Grade 1" }] });
  assert.match(html, /Song Book lesson \/ worksheet material/);
  assert.match(html, /Preview Features/);
  assert.match(html, /Import Feature Snapshots/);
  assert.match(html, /Verify Feedback &amp; Run 10-Feature Batch/);
  assert.match(html, /Experimental predictions are not teacher-verified outcomes/);
});

test("AI feature renders separately with an empty student selection", () => {
  const html = render(AIRecommendationPanel, { studentId: "" });
  assert.match(html, /Random Forest prediction/);
  assert.match(html, /Select a student to view their model evidence/);
  assert.doesNotMatch(html, /Personalized activities and teacher decisions appear separately/);
});

test("model status distinguishes the active three-class model from a binary class set", () => {
  assert.match(predictionModelClassStatus({ "Needs Practice": 0.5, Successful: 0.4, Waiting: 0.1 }), /original three-class Random Forest.*No validated binary candidate is deployed/);
  assert.equal(predictionModelClassStatus({ Successful: 0.55, "Needs Practice": 0.45 }), "Active model classes: Needs Practice and Successful.");
});

test("teacher can reach approved-label export even when selected student evidence is still loading", () => {
  const html = render(TeacherOutcomeReview, { student: { id: "s1", name: "Amaya Silva" } });
  assert.match(html, /Download approved labels for training review/);
});

test("student pathway panel uses saved recommendations and does not repeat model predictions", () => {
  const html = render(StudentPathways, { student: { id: "s1", name: "Amaya Silva", grade: "Grade 1" }, initial: [{ id: "r1", status: "PENDING", activityTitle: "Count objects", objective: "Count to ten", reason: "Next curriculum objective", difficulty: "Beginner", createdAt: "2026-10-20T00:00:00Z" }] });
  assert.match(html, /Personalized learning pathways/);
  assert.match(html, /Count objects/);
  assert.match(html, /Next curriculum objective/);
  assert.doesNotMatch(html, /Predicted class:/);
});

test("empty pathway panel explains a persisted curriculum mapping gap", () => {
  const html = render(StudentPathways, { student: { id: "s1", name: "Amaya Silva", grade: "Grade 1" }, initial: [], initialMilestones: [{ status: "NO_MATCH", message: "Fruits: no approved Grade 1 fruit-naming objective." }] });
  assert.match(html, /No pathway saved · curriculum match needed/);
  assert.match(html, /no approved Grade 1 fruit-naming objective/);
  assert.match(html, /Recommendations are saved only when an approved/);
});

test("student evidence shows the three saved evidence views without standalone observation or lesson entry forms", () => {
  const html = render(LearningEvidence, { student: { id: "s1", name: "Amaya Silva" } });
  assert.match(html, /Assessment activity history/);
  assert.match(html, /Classroom activity history/);
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
  for (const label of ["Classroom activity", "Download Score Template", "Upload Completed Score Template", "Preview Scores", "Import Scores"]) assert.match(empty, new RegExp(label));
  assert.match(empty, /disabled="">Import Scores/);
  assert.match(empty, /Scores must be 0–10/);
  assert.doesNotMatch(empty, /Oshadi Madushanka|Bimsara Gunawardena|value="8"/);
});

test("worksheet score preview omits time taken and teacher feedback", () => {
  const html = render(WorksheetScorePreview, {
    preview: { valid: 1, invalid: 0, blank: 0, existing: 0 },
    rows: [{ row: 2, studentId: "stu-1", studentName: "Ashen Wijesinghe", grade: "Grade 1", worksheetId: "mat-1", title: "Fruits", subject: "English", scoreValue: "8.5", score: 8.5, feedback: "Good work", attempts: 2, timeTakenMinutes: 15, completionStatus: "COMPLETED", status: "VALID" }],
  });
  const headings = [...html.matchAll(/<th[^>]*>([^<]*)<\/th>/g)].map(match => match[1]);
  assert.deepEqual(headings, ["Student ID", "Student Name", "Grade", "Worksheet ID", "Worksheet Title", "Score / 10", "Attempts", "Status"]);
  const cells = [...html.matchAll(/<td>(.*?)<\/td>/g)].map(match => match[1]);
  assert.deepEqual(cells, ["stu-1", "Ashen Wijesinghe", "Grade 1", "mat-1", "Fruits", "8.5", "2", "COMPLETED"]);
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
  assert.match(html, /Students needing practice/);
  assert.match(html, /aria-expanded="false"/);
  assert.match(html, /practice-alert-toggle/);
});

test("dashboard renders populated progress and activity without the removed pathway feed", () => {
  const student = { id: "1", name: "Amaya Silva", grade: "Grade 1" };
  const html = render(DashboardPage, {
    ...dashboardProps, activeId: "1", students: [student], activeStudent: student,
    average: 70, profile: { assessmentCount: 1 },
    classroom: {
      lessons: [{ id: "m1", title: "Reading practice", grade: "Grade 1", assigned: 18, completed: 3, uploadedAt: "2026-10-20T00:00:00Z" }],
    },
    areas: [
      { domain: "Literacy", averagePercentage: 80 },
      { domain: "Numeracy", averagePercentage: 60 },
    ],
    records: [{ id: "r1", domain: "Literacy", score: 4, maxScore: 5, assessedAt: "2026-10-20" }],
    recommendations: [{ id: "p1", status: "PENDING", domain: "Numeracy", activityTitle: "Count blocks", createdAt: "2026-10-20", confidence: 0.8 }],
  });
  assert.doesNotMatch(html, /Learning pathways for every student|Random Forest pathways are generated|Personalized practice pathways|Common learning pathways/);
  assert.match(html, /id="pathways"/);
  assert.doesNotMatch(html, /LEARNER PROFILE|NEXT CLASSROOM STEP|Build a learning journey|Add a learner to view their learning areas and progress/);
  assert.match(html, /Reading practice/);
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

test("saved milestone keeps raw Random Forest class separate from score-based final outcome", () => {
  const prediction = latestPrediction([{ source: "RANDOM_FOREST", predictedOutcome: "Needs Practice", predictionProbabilities: { "Needs Practice": 0.505, Successful: 0.39, Waiting: 0.105 }, modelVersion: "rf-v1", featuresGeneratedAt: "2026-10-23" }], { milestones: [{ predictedOutcome: "Needs Practice", applicationOutcome: "Successful", verifiedPerformancePercentage: 82.5, verifiedAssessmentCount: 4, probabilities: { "Needs Practice": 0.505 }, updatedAt: "2026-10-22", status: "ERROR", message: "Configure inference token", assessmentEvidence: [{ assessmentResultId: "a1", title: "Fruits", score: 8, maximumScore: 10, percentage: 80, completionStatus: "COMPLETED" }] }] });
  assert.equal(prediction.label, "Needs Practice");
  assert.equal(prediction.applicationOutcome, "Successful");
  assert.equal(prediction.verifiedPerformancePercentage, 82.5);
  assert.equal(prediction.verifiedAssessmentCount, 4);
  assert.match(prediction.discrepancy, /Random Forest predicted Needs Practice; verified score policy classified Successful/);
  assert.equal(prediction.version, "rf-v1");
  assert.equal(prediction.assessmentEvidence.length, 1);
  const outcomeHtml = render(OutcomeDecision, { prediction });
  assert.match(outcomeHtml, /Final application outcome: Successful/);
  assert.match(outcomeHtml, /82\.50%/);
  assert.match(outcomeHtml, /4 valid completed assessment/);
  assert.match(outcomeHtml, /Fruits: 8 \/ 10 \(80\.00%\)/);
  assert.match(outcomeHtml, /calculated separately from the Random Forest prediction/);
  assert.match(outcomeHtml, /Configure inference token/);
  assert.match(outcomeHtml, /saved Random Forest result was retained/);
});

test("application outcome remains visible without a Random Forest class when features are incomplete", () => {
  const prediction = latestPrediction([], { milestones: [{ applicationOutcome: "INSUFFICIENT_DATA", verifiedPerformancePercentage: 82.5, verifiedAssessmentCount: 4, updatedAt: "2026-10-22" }] });
  assert.equal(prediction.label, undefined);
  const html = render(OutcomeDecision, { prediction });
  assert.match(html, /Final application outcome: INSUFFICIENT_DATA/);
  assert.match(html, /82\.50%/);
  assert.match(html, /required live model features/);
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
