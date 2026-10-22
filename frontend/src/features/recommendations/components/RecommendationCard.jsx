import React, { useState } from "react";
import { Badge, Button, Card, Field } from "../../../components";
import { displayDate } from "../../../utils/format";
import ThreeLessonPerformance from "./ThreeLessonPerformance";

function RecommendationCard({
  recommendation,
  showPrediction = true,
  materials = [],
  onAssign,
  saving,
  editing,
  onEdit,
  onCancelEdit,
  onReview,
}) {
  const [teacherFeedback, setTeacherFeedback] = useState(recommendation.teacherFeedback);
  const [materialId, setMaterialId] = useState(recommendation.materialId || "");
  const [decision, setDecision] = useState("APPROVED");
  const statusLabel = recommendation.assignedAt ? "Assigned" : recommendation.status === "PENDING" ? "Pending Review" : recommendation.status === "APPROVED" ? "Approved" : "Rejected";

  function submitEdit(event) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    onReview(decision, {
      activityTitle: String(form.get("activityTitle")),
      description: String(form.get("description")),
      teacherFeedback,
    });
  }

  function beginReview(status) {
    setDecision(status);
    onEdit();
  }

  return (
    <Card as="article" className={`recommendation-card ${recommendation.status.toLowerCase()}`}>
      <div className="recommendation-symbol">✳</div>
      <div className="recommendation-body">
        <div className="recommendation-meta">
          <Badge tone="neutral" className="area-chip">{recommendation.domain}</Badge>
          <Badge tone={recommendation.status.toLowerCase()} className={`status-pill ${recommendation.status.toLowerCase()}`}>
            {statusLabel}
          </Badge>
          <span className="recommendation-date">{displayDate(recommendation.createdAt)}</span>
        </div>
        {editing ? (
          <form className="edit-recommendation" onSubmit={submitEdit}>
            <Field label="Activity title"><input name="activityTitle" defaultValue={recommendation.activityTitle} required /></Field>
            <Field label="Activity details"><textarea name="description" defaultValue={recommendation.description} rows={3} required /></Field>
            <Field label="Teacher note"><textarea value={teacherFeedback} onChange={(event) => setTeacherFeedback(event.target.value)} rows={2} placeholder="Add context for this decision (optional)" /></Field>
            <Field label="Decision">
              <select value={decision} onChange={(event) => setDecision(event.target.value === "REJECTED" ? "REJECTED" : "APPROVED")}>
                <option value="APPROVED">Approve this activity</option>
                <option value="REJECTED">Not suitable for this learner</option>
              </select>
            </Field>
            <div className="edit-actions">
              <Button variant="quiet" type="button" onClick={onCancelEdit}>Cancel</Button>
              <Button type="submit" disabled={saving}>Save teacher review</Button>
            </div>
          </form>
        ) : (
          <>
            {showPrediction && recommendation.source === "RANDOM_FOREST" && <div className="recommendation-prediction">
              <strong>Predicted class: {recommendation.predictedOutcome}</strong>
              <div aria-label="Class probabilities">{Object.entries(recommendation.predictionProbabilities || {}).map(([label, probability]) => <small key={label}>{label}: {(probability * 100).toFixed(1)}% class probability </small>)}</div>
            </div>}
            <h3>{recommendation.activityTitle}</h3>
            {recommendation.completionMilestone && <p className="teacher-note">After {recommendation.completionMilestone} completed lessons · {recommendation.curriculumDifficulty || "Curriculum activity"}</p>}
            {recommendation.threeLessonPerformance && <ThreeLessonPerformance performance={recommendation.threeLessonPerformance} compact />}
            {recommendation.learningObjective && <p><strong>Learning objective:</strong> {recommendation.learningObjective}</p>}
            <p className="recommendation-description">{recommendation.description}</p>
            <div className="reason-note"><span>Why this suggestion</span>{recommendation.reason}</div>
            {recommendation.teacherFeedback && <div className="teacher-feedback">Teacher note: {recommendation.teacherFeedback}</div>}
            {recommendation.source === "RANDOM_FOREST" && recommendation.status === "APPROVED" && onAssign && (
              <div className="recommendation-assignment">
                <Field label="Learning material"><select value={materialId} onChange={(event) => setMaterialId(event.target.value)} disabled={saving}>
                  <option value="">Choose a material to assign</option>
                  {materials.map((material) => <option key={material.id} value={material.id}>{material.title || material.originalFileName}</option>)}
                </select></Field>
                <Button size="small" disabled={saving || !materialId} onClick={() => onAssign(materialId)}>Assign approved activity</Button>
              </div>
            )}
            {recommendation.status === "PENDING" && (
              <div className="recommendation-actions">
                <Button size="small" disabled={saving} onClick={() => onReview("APPROVED")}>Approve activity</Button>
                <Button variant="quiet" size="small" disabled={saving} onClick={() => beginReview("APPROVED")}>Edit & review</Button>
                <Button variant="reject" size="small" disabled={saving} onClick={() => beginReview("REJECTED")}>Not suitable</Button>
              </div>
            )}
          </>
        )}
      </div>
      <div className="confidence"><strong>{Math.round(recommendation.confidence * 100)}%</strong><span>{recommendation.source === "RANDOM_FOREST" ? "class probability" : "match"}</span></div>
    </Card>
  );
}

export default RecommendationCard;
