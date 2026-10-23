import { OBSERVATION_FIELDS } from "../evidence/evidence.js";

export const LESSON_ROUTE = /^\/dashboard\/lessons\/[^/]+$/;
export function isLessonPath(path) {
  if (!LESSON_ROUTE.test(path)) return false;
  try { const id = decodeURIComponent(path.split("/").pop()); return id.length > 0 && id.length <= 180; }
  catch { return false; }
}
export function emptyFeedback() {
  return { ...Object.fromEntries(OBSERVATION_FIELDS.map(([key]) => [key, ""])), demonstratedSkills: "", question: "", studentAnswer: "", improvement: "", feedback: "", status: "" };
}
export const SHARED_KEYS = [...OBSERVATION_FIELDS.map(([key]) => key), "improvement", "feedback"];
export const canEditFeedback = row => row.activeStudent && row.submissionStatus !== "SUBMITTED";
export function applySavedFeedback(row, record) {
  return { ...row, ...record.observation, ...Object.fromEntries(["question", "studentAnswer", "improvement", "feedback"].map(key => [key, record[key] || ""])),
    demonstratedSkills: (record.observation?.demonstratedSkills || []).join("\n"), status: record.activityStatus || "", draftKey: record.draftKey,
    expectedVersion: record.version, submittedAt: record.submittedAt, submissionStatus: record.submissionStatus, dirty: false, individualFields: {}, revision: false, selected: record.submissionStatus !== "SUBMITTED" && row.activeStudent };
}
export function feedbackRows(workspace) {
  return workspace.lesson.recipients.map(recipient => {
    const legacy = workspace.saved.find(row => row.studentId === recipient.studentId)?.observation;
    const record = workspace.drafts?.find(row => row.studentId === recipient.studentId) || workspace.submitted?.find(row => row.studentId === recipient.studentId);
    const row = { ...emptyFeedback(), ...legacy, ...recipient, status: recipient.status === "UNRECORDED" ? "" : recipient.status,
      demonstratedSkills: (legacy?.demonstratedSkills || []).join("\n"), selected: recipient.activeStudent, dirty: false,
      draftKey: crypto.randomUUID(), expectedVersion: null, submissionStatus: "DRAFT" };
    return record ? applySavedFeedback(row, record) : row;
  });
}
export function applySharedFeedback(rows, shared, { replaceExisting = false, preserveIndividualEdits = false } = {}) {
  const values = Object.fromEntries(Object.entries(shared).filter(([key, value]) => SHARED_KEYS.includes(key) && typeof value === "string" && value.trim() !== ""));
  return rows.map(row => {
    if (!row.selected || !canEditFeedback(row)) return row;
    const changes = Object.fromEntries(Object.entries(values).filter(([key]) => !(preserveIndividualEdits && row.individualFields?.[key]) && (replaceExisting || !String(row[key] || "").trim())));
    return Object.keys(changes).length ? { ...row, ...changes, dirty: true } : row;
  });
}
function feedbackObservation(row) {
  const observation = {};
  for (const [key, label, choices] of OBSERVATION_FIELDS) {
    const value = String(row[key] || "").trim();
    if (!value) throw new Error(`Choose ${label.toLowerCase()}.`);
    if (!choices.includes(value)) throw new Error("Choose a supported option.");
    observation[key] = value;
  }
  const skills = (row.demonstratedSkills || "").split("\n").map(value => value.trim()).filter(Boolean);
  if (skills.length > 200 || skills.some(value => value.length > 180)) throw new Error("Use at most 200 skills, each up to 180 characters.");
  return { ...observation, demonstratedSkills: [...new Set(skills)] };
}
export function lessonFeedbackPayload(rows, { draft = false } = {}) {
  const selected = rows.filter(row => row.selected && canEditFeedback(row));
  if (!selected.length) throw new Error("Select at least one editable active student. Create a new revision to change submitted feedback.");
  return { students: selected.map(row => {
    try {
      const observation = draft ? { ...Object.fromEntries(OBSERVATION_FIELDS.map(([key]) => [key, row[key] || ""])), demonstratedSkills: (row.demonstratedSkills || "").split("\n").map(s => s.trim()).filter(Boolean) }
        : feedbackObservation(row);
      const limits = { question: 1000, studentAnswer: 2000, improvement: 2000, feedback: 2000 };
      for (const [key, limit] of Object.entries(limits)) if ((row[key] || "").length > limit) throw new Error(`${key} must be at most ${limit} characters.`);
      return { studentId: row.studentId, observation, draftKey: row.draftKey, expectedVersion: row.expectedVersion,
        ...Object.fromEntries(Object.keys(limits).map(key => [key, (row[key] || "").trim()])) };
    } catch (error) { throw new Error(`${row.name}: ${error.message}`); }
  }) };
}

// The backend derives progression from persisted assignment statuses.
export async function refreshLessonCompletion(api, materialId) {
  return api.lessonWorkspace(materialId);
}
