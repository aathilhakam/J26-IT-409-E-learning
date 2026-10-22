import React from "react";
import { Badge, Button, Card } from "../../../components";
import { formatContentSize } from "../materials";

export default function AssignedMaterials({ student, items = [], busy = false, onDownload }) {
  const assigned = items.filter((item) => item.assignmentMode !== "LIBRARY" && item.assignedStudentIds?.includes(student.id));
  return (
    <Card as="section" className="student-profile-card assigned-materials" aria-busy={busy}>
      <details>
        <summary className="assigned-materials-summary">
          <div className="student-profile-card-heading">
            <div><span className="dashboard-section-kicker">ASSIGNED LEARNING</span>
              <h2>Assigned activities &amp; materials</h2>
              <p>Resources assigned to {student.name} individually or with their grade.</p>
            </div>
            <Badge tone="neutral">{assigned.length} assigned</Badge>
          </div>
        </summary>
        {busy ? <p role="status">Loading assigned materials…</p> : assigned.length ? (
          <div className="assigned-materials-list">
            {assigned.map((item) => (
              <article key={item.id} className="assigned-material-item">
                <span className="assigned-material-icon" aria-hidden="true">▤</span>
                <div className="assigned-material-copy">
                  <strong>{item.title || item.originalFileName}</strong>
                  {item.description && <p>{item.description}</p>}
                  <small>{[item.materialType || item.category, item.grade, formatContentSize(item.size)].filter(Boolean).join(" · ")}</small>
                  <small>{item.originalFileName}</small>
                </div>
                <Button variant="secondary" size="small" onClick={() => onDownload(item)} aria-label={`Download ${item.title || item.originalFileName}`}>↓ Download</Button>
              </article>
            ))}
          </div>
        ) : <div className="student-profile-blank"><span aria-hidden="true">▤</span><div><strong>No assigned materials yet</strong><p>Assign a material to this student or their grade from Upload &amp; Content.</p></div></div>}
      </details>
    </Card>
  );
}
