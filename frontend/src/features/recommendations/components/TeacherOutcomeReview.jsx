import React, { useEffect, useState } from "react";
import { Button, Card } from "../../../components";
import { api } from "../../../services/api";

export default function TeacherOutcomeReview({ student }) {
  const [review, setReview] = useState(null);
  const [outcome, setOutcome] = useState("");
  const [rationale, setRationale] = useState("");
  const [busy, setBusy] = useState(false);
  const [exportBusy, setExportBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  useEffect(() => {
    let active = true;
    setReview(null); setError(""); setNotice(""); setOutcome(""); setRationale("");
    api.verifiedOutcome(student.id).then(value => { if (active) { setReview(value); setOutcome(value.verifiedLabel?.revisions?.at(-1)?.outcome || value.suggestedOutcome || ""); } })
      .catch(cause => { if (active) setError(cause.message || "Could not load the verified outcome review."); });
    return () => { active = false; };
  }, [student.id]);
  const current = review?.verifiedLabel?.revisions?.at(-1);
  const conflict = review?.suggestedOutcome && outcome && review.suggestedOutcome !== outcome;
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    try {
      await api.verifyStudentOutcome(student.id, { outcome, rationale });
      const updated = await api.verifiedOutcome(student.id); setReview(updated);
      setOutcome(updated.verifiedLabel?.revisions?.at(-1)?.outcome || outcome);
      setRationale(""); setNotice("Teacher-verified outcome saved with an audit record.");
    } catch (cause) { setError(cause.message || "Could not save the verified outcome."); }
    finally { setBusy(false); }
  }
  async function downloadApprovedLabels() {
    setError(""); setNotice(""); setExportBusy(true);
    try {
      const file = await api.downloadVerifiedOutcomeTrainingExport();
      const payload = JSON.parse(await file.text());
      const url = URL.createObjectURL(file); const anchor = document.createElement("a");
      anchor.href = url; anchor.download = "smartplay-approved-training-labels.json"; anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
      const count = Array.isArray(payload) ? payload.length : 0;
      setNotice(count ? `Downloaded ${count} current approved teacher label${count === 1 ? "" : "s"}.` : "Downloaded an empty export. No approved teacher labels are available for this account yet.");
    } catch (cause) { setError(cause.message || "Could not download approved training labels."); }
    finally { setExportBusy(false); }
  }
  return <Card as="section" className="student-profile-card teacher-outcome-review">
    <span className="dashboard-section-kicker">TEACHER-VERIFIED TRAINING LABEL</span><h2>Review student outcome</h2>
    <p>Review completed assessment evidence, then explicitly confirm the training label. Random Forest predictions are not used as labels.</p>
    <Button type="button" variant="secondary" onClick={downloadApprovedLabels} disabled={exportBusy}>{exportBusy ? "Preparing approved-label export…" : "Download approved labels for training review"}</Button>
    {!review ? <p role={error ? "alert" : "status"}>{error || "Loading completed assessment evidence…"}</p> : <>
      {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
      <p><strong>{review.validAssessmentCount}</strong> valid completed assessments · Verified average <strong>{review.validAssessmentCount ? `${review.averagePercentage.toFixed(2)}%` : "Unavailable"}</strong></p>
      {review.validAssessmentCount ? <ul>{review.assessmentEvidence.map(row => <li key={row.assessmentResultId}>{row.assessmentIdentifier || row.assessmentType || "Assessment"}: {row.score} / {row.maximumScore} ({row.percentage.toFixed(2)}%)</li>)}</ul> : <p>No training label can be confirmed until valid completed assessment evidence exists.</p>}
      {review.suggestedOutcome && <p>80% policy suggestion: <strong>{review.suggestedOutcome}</strong>. This is a reference for your review; you must confirm the label.</p>}
      {current && <p>Current approved label: <strong>{current.outcome}</strong> · revision {current.revision} · verified {current.verifiedAt ? new Date(current.verifiedAt).toLocaleString() : "time unavailable"}</p>}
      {current && <p className="teacher-note">Training feature snapshot: <strong>{current.featureSnapshotStatus || (current.featureSnapshot ? "READY" : "UNAVAILABLE")}</strong>{current.featureSnapshotGeneratedAt ? ` · captured ${new Date(current.featureSnapshotGeneratedAt).toLocaleString()}` : ""}{current.featureSnapshotIssue ? ` · ${current.featureSnapshotIssue}` : ""}{!current.featureSnapshot && " This approved label is retained for review but cannot enter training until a valid snapshot is captured in a new revision."}</p>}
      {!!current && !!review.verifiedLabel?.revisions?.length && <details><summary>Label audit history</summary><ol>{review.verifiedLabel.revisions.map(row => <li key={row.revision}>Revision {row.revision}: {row.outcome} ({row.reviewStatus}) · {row.rationale || "No rationale recorded"}</li>)}</ol></details>}
      {!!review.validAssessmentCount && <form onSubmit={submit}>
        <label>Teacher-confirmed outcome<select value={outcome} onChange={event => setOutcome(event.target.value)} required><option value="">Select outcome</option><option>Needs Practice</option><option>Successful</option></select></label>
        {conflict && <p role="alert">This differs from the 80% suggestion. Enter a rationale to confirm the teacher decision.</p>}
        <label>Rationale{conflict ? " (required for this decision)" : " (optional)"}<textarea value={rationale} maxLength={2000} required={!!conflict} onChange={event => setRationale(event.target.value)} /></label>
        <p className="teacher-note">Policy: {review.policyVersion}. A correction creates a new revision and marks the prior revision SUPERSEDED.</p>
        <Button type="submit" disabled={busy || !outcome}>{busy ? "Saving verified outcome…" : current ? "Confirm or revise label" : "Confirm teacher label"}</Button>
      </form>}
    </>}
  </Card>;
}
