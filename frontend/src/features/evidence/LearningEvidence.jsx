import React, { useCallback, useEffect, useRef, useState } from "react";
import { Card, Button } from "../../components";
import { api } from "../../services/api";
import { displayDate } from "../../utils/format";

const kinds = ["history", "assessment"];
const labels = { history: "Historical lessons", assessment: "Assessment results" };
const loaders = { history: api.lessonHistory, assessment: api.assessments };
export default function LearningEvidence({ student }) {
  const [kind, setKind] = useState("assessment");
  const [data, setData] = useState({});
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const generation = useRef(0);
  const load = useCallback(async () => {
    const current = ++generation.current; setBusy(true); setError("");
    const results = await Promise.allSettled(kinds.map(key => loaders[key](student.id)));
    if (current !== generation.current) return;
    setData(Object.fromEntries(results.map((result, index) => [kinds[index], result.status === "fulfilled" ? result.value : null])));
    if (results.some(result => result.status === "rejected")) setError("Some evidence could not be loaded. Check your access and retry.");
    setBusy(false);
  }, [student.id]);
  useEffect(() => { const changed=()=>{void load();};window.addEventListener("smartplay-evidence-updated",changed);return()=>window.removeEventListener("smartplay-evidence-updated",changed); },[load]);
  useEffect(() => { void load(); return () => { generation.current++; }; }, [load]);
  const worksheetResults = (data.assessment || []).filter(row => row.worksheet);
  return <Card as="section" className="student-profile-card learning-evidence">
    <details>
      <summary className="learning-evidence-summary"><div className="student-profile-card-heading"><div><span className="dashboard-section-kicker">LEARNING EVIDENCE</span><h2>Learning evidence</h2><p>Worksheet results and completed lessons for {student.name}.</p></div></div></summary>
      <div className="learning-evidence-content">
        <div className="evidence-actions" role="group" aria-label="Saved evidence views">{kinds.map(key => <Button key={key} variant={kind === key ? "primary" : "secondary"} aria-pressed={kind === key} onClick={() => setKind(key)}>{labels[key]}</Button>)}</div>
        {kind === "history" && <h3>Completed lesson history</h3>}
        {error && <div role="alert"><p>{error}</p><Button onClick={load} disabled={busy}>Retry loading evidence</Button></div>}
        {busy ? <p role="status">Loading evidence…</p> : kind === "assessment" ? worksheetResults.length ? <ul className="evidence-list">{worksheetResults.map(row => <li key={row.id}>
          <strong>{row.worksheetTitle || "Untitled worksheet"}</strong>
          <p>{row.score} / {row.maxScore}{row.percentage != null ? ` · ${row.percentage}%` : ""} · {row.subject || "Subject not specified"} · {row.grade || "Grade not specified"}</p>
          {row.notes && <p>Teacher feedback: {row.notes}</p>}
          <small>{row.assessmentDate || displayDate(row.assessedAt)}</small>
        </li>)}</ul> : <p>No worksheet results recorded yet.</p> : data.history?.length ? <ul className="evidence-list">{data.history.map(row => <li key={row.id}>
          <strong>{row.lessonId}</strong>{row.materialId && <p><a href={`/dashboard/lessons/${encodeURIComponent(row.materialId)}`}>Open lesson</a></p>}<p>Sequence {row.sequenceNumber} · {row.completionStatus}</p>
          <small>{row.assessmentDate || displayDate(row.occurredAt || row.assessedAt)}</small>
        </li>)}</ul> : data.history && <p>No completed lesson history recorded yet.</p>}
      </div>
    </details>
  </Card>;
}
