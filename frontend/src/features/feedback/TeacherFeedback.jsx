import React, { useCallback, useRef, useState } from "react";
import { Button, Card } from "../../components";
import { api } from "../../services/api";
import FeedbackFields from "./FeedbackFields";
import SuccessToast from "../../components/ui/SuccessToast";
import { applySavedFeedback, applySharedFeedback, canEditFeedback, emptyFeedback, feedbackRows, lessonFeedbackPayload } from "../dashboard/lessonFeedback";

export default function TeacherFeedback({ workspace, materialId, onRefresh }) {
  const [rows, setRows] = useState(() => feedbackRows(workspace));
  const [shared, setShared] = useState(emptyFeedback);
  const [replaceExisting, setReplaceExisting] = useState(false);
  const [expanded, setExpanded] = useState("");
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [submissionNotice, setSubmissionNotice] = useState("");
  const dismissSubmission = useCallback(() => setSubmissionNotice(""), []);
  const lock = useRef(false);
  const editable = workspace.weekPosition === 1 && !workspace.completed;
  const canEditRow = row => row.activeStudent && (editable ? canEditFeedback(row) : workspace.completed && (row.revision === true || row.submissionStatus !== "SUBMITTED"));
  const eligible = rows.filter(canEditRow);
  const selected = eligible.filter(row => row.selected).length;
  function edit(id, key, value) {
    if (lock.current) return;
    setRows(current => current.map(row => row.studentId === id && canEditFeedback(row) ? { ...row, [key]: value, dirty: key === "selected" ? row.dirty : true, individualFields: key === "selected" ? row.individualFields : { ...row.individualFields, [key]: true } } : row));
    setNotice("");
  }
  function revision(id) {
    if (lock.current) return;
    setRows(current => current.map(row => row.studentId === id ? { ...row, draftKey: crypto.randomUUID(), expectedVersion: null, submissionStatus: "DRAFT", revision: true, selected: true, dirty: true, individualFields: {} } : row));
    setExpanded(id); setNotice("A new feedback revision is ready to edit. The previous submission will be preserved.");
  }
  const completedLessonEntriesSelected = workspace.completed && rows.some(row => row.selected && canEditRow(row));
  const canSubmit = editable || completedLessonEntriesSelected;
  async function save(draft = false) {
    if (lock.current) return;
    const submissionRows = applySharedFeedback(rows, shared, { replaceExisting, preserveIndividualEdits: true });
    setRows(submissionRows);
    let payload;
    try { payload = lessonFeedbackPayload(submissionRows, { draft }); }
    catch (cause) { setError(cause.message); return; }
    lock.current = true; setBusy(draft ? "draft" : "submit"); setError(""); setNotice(""); setSubmissionNotice("");
    try {
      const results = await (draft ? api.saveLessonFeedbackDrafts(materialId, payload) : api.saveLessonFeedback(materialId, payload));
      setRows(current => current.map(row => {
        const saved = results.find(result => result.studentId === row.studentId && result.status === "SAVED")?.record;
        return saved ? applySavedFeedback(row, saved) : row;
      }));
      const saved = results.filter(result => result.status === "SAVED");
      const failed = results.filter(result => result.status !== "SAVED");
      setNotice(draft ? `${saved.length} student drafts saved.` : `Feedback submitted for ${saved.length} students. It is saved separately in each student's profile under ${workspace.lesson.title}.`);
      if (saved.length) setSubmissionNotice(draft ? `Drafts saved for ${saved.length} ${saved.length === 1 ? "student" : "students"}.` : `Feedback submitted for ${saved.length} ${saved.length === 1 ? "student" : "students"}.`);
      if (failed.length) setError(`Not saved: ${failed.map(result => rows.find(row => row.studentId === result.studentId)?.name || result.studentId).join(", ")}. Your entries remain available. Retry without duplicating successful submissions.`);
      try { await onRefresh(); }
      catch { setError("Saving finished, but the lesson summary could not refresh. Reload the page before completing the lesson."); }
    } catch (cause) {
      setError(cause.status === 405 ? "The running backend does not support feedback submission yet. Restart the backend with the updated code, then retry; your entries are preserved." : cause.message || "Could not save feedback. Your student selection and entries are preserved; retry safely.");
    } finally { lock.current = false; setBusy(""); }
  }
  return <Card as="section" className="teacher-feedback-editor" aria-busy={Boolean(busy)}>
    <SuccessToast message={submissionNotice} onDismiss={dismissSubmission} />
    <h2>Teacher Feedback</h2><p>Select assigned students, enter common feedback, and submit. You can review and adjust individual entries before submitting. Each student receives a separate saved record for this lesson.</p>
    {!editable && <p className="teacher-note">{workspace.completed ? "This lesson is complete. Use Update feedback on a submitted entry to add a revision; the original submission is preserved." : "You can preview this lesson now. Feedback opens when it becomes the current week's lesson."}</p>}
    {error && <p className="alert" role="alert">{error}</p>}{notice && <p className="material-success" role="status">{notice}</p>}
    {canSubmit && <fieldset className="material-form-fields" disabled={Boolean(busy)}>
      <details className="lesson-shared-feedback" open><summary>Common feedback for selected students</summary><p>Submit applies common feedback to the selected students automatically. Blank fields and individual adjustments are preserved. Use Apply if you want to preview common values before submitting.</p>
        <FeedbackFields values={shared} prefix="Common feedback" shared onChange={(key, value) => { setShared(current => ({ ...current, [key]: value })); setError(""); setNotice(""); }} />
        <div className="feedback-action-row"><label><input type="checkbox" checked={replaceExisting} onChange={event => setReplaceExisting(event.target.checked)} /> Replace existing values for selected students</label>
        <Button variant="secondary" disabled={!selected} onClick={() => { setRows(current => applySharedFeedback(current, shared, { replaceExisting })); setNotice(`Common feedback applied to ${selected} selected students. Review before submitting.`); }}>Apply to {selected} selected students</Button></div>
      </details>
      <div className="lesson-feedback-toolbar"><p>Only selected students will be saved. Submitted feedback is preserved when you create an update.</p><div className="feedback-action-row"><Button variant="secondary" disabled={Boolean(busy) || !selected} onClick={() => void save(true)}>{busy === "draft" ? "Saving drafts…" : "Save selected drafts"}</Button><Button disabled={Boolean(busy) || !selected} onClick={() => void save(false)}>{busy === "submit" ? "Submitting…" : `Submit feedback for ${selected} students`}</Button></div></div>
      {!workspace.completed && <div className="lesson-feedback-toolbar"><label><input type="checkbox" checked={eligible.length > 0 && eligible.every(row => row.selected)} onChange={event => setRows(current => current.map(row => ({ ...row, selected: canEditRow(row) && event.target.checked })))} /> Select all eligible students</label><span>{selected} selected</span></div>}
    </fieldset>}
    {!rows.length ? <p>No students are assigned to this lesson. Assign students through Upload &amp; Content Management first.</p> : <ul className="lesson-feedback-roster">{rows.map(row => <li key={row.studentId}>
      <div className="lesson-feedback-student"><label>{canEditRow(row) && <input type="checkbox" aria-label={`Select ${row.name}`} checked={row.selected} disabled={Boolean(busy)} onChange={event => edit(row.studentId, "selected", event.target.checked)} />}<strong>{row.name}</strong></label><span>{row.grade}{!row.activeStudent && " · Archived"}</span><span>{({ COMPLETED: "Completed", IN_PROGRESS: "In progress", NOT_STARTED: "Not started" })[row.status] || "Status unrecorded"}</span><span className="dashboard-activity-chip">{row.submissionStatus === "SUBMITTED" ? "Submitted" : row.expectedVersion != null ? "Saved draft" : "Not submitted"}</span>
      <Button variant="quiet" disabled={Boolean(busy)} aria-expanded={expanded === row.studentId} onClick={() => setExpanded(expanded === row.studentId ? "" : row.studentId)}>{expanded === row.studentId ? "Close feedback" : "Review feedback"}<span className="sr-only"> for {row.name}</span></Button>
      {(editable || workspace.completed) && row.activeStudent && row.submissionStatus === "SUBMITTED" && <Button variant="secondary" disabled={Boolean(busy)} onClick={() => revision(row.studentId)}>Update feedback<span className="sr-only"> for {row.name}</span></Button>}{row.dirty && <small>Unsaved changes</small>}</div>
      {row.submissionStatus === "SUBMITTED" && <p className="feedback-individual-summary">{row.submittedAt && `${new Date(row.submittedAt).toLocaleString()} · `}{row.feedback || "No written feedback supplied."}{row.improvement && ` · Improvement: ${row.improvement}`}</p>}
      {expanded === row.studentId && <fieldset className="material-form-fields" disabled={Boolean(busy) || !canEditRow(row)}><FeedbackFields values={row} prefix={row.name} onChange={(key, value) => edit(row.studentId, key, value)} /></fieldset>}
    </li>)}</ul>}

  </Card>;
}
