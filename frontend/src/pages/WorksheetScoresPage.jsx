import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Field } from "../components";
import { api } from "../services/api";
import WorksheetCorrection from "../features/worksheets/WorksheetCorrection";

const dateLabel = value => value ? new Intl.DateTimeFormat("en", { dateStyle: "medium" }).format(new Date(value)) : "—";

export default function WorksheetScoresPage() {
  const [rows, setRows] = useState([]);
  const [search, setSearch] = useState("");
  const [grade, setGrade] = useState("");
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setBusy(true);
    setError("");
    try { setRows(await api.worksheetScores()); }
    catch (cause) { setError(cause.message || "Could not load worksheet scores."); }
    finally { setBusy(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const grades = useMemo(() => [...new Set(rows.map(row => row.grade).filter(Boolean))].sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" })), [rows]);
  const visibleRows = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    return rows.filter(row => (!grade || row.grade === grade) && (!query || [row.worksheetTitle, row.studentName, row.grade, row.subject, row.notes].some(value => String(value || "").toLocaleLowerCase().includes(query))));
  }, [rows, search, grade]);
  const worksheetGroups = useMemo(() => {
    const groups = new Map();
    visibleRows.forEach(row => {
      const title = row.worksheetTitle || "Untitled worksheet";
      const key = row.worksheetId ? `${row.worksheetId}:${title}` : title;
      if (!groups.has(key)) groups.set(key, { key, title, rows: [] });
      groups.get(key).rows.push(row);
    });
    return [...groups.values()];
  }, [visibleRows]);

  return <section className="page-section worksheet-scores-page">
    <header className="page-heading"><div><span className="dashboard-section-kicker">ASSESSMENT RESULTS</span><h1>Worksheet Scores</h1><p>View saved worksheet scores by student and worksheet, and record audited corrections.</p></div><span className="workspace-status"><span /> {rows.length} saved results</span></header>
    <Card as="section" className="worksheet-results-card">
      <div className="worksheet-results-filters">
        <Field label="Search worksheets and students"><input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Worksheet, student, subject, or feedback" /></Field>
        <Field label="Grade"><select value={grade} onChange={event => setGrade(event.target.value)}><option value="">All grades</option>{grades.map(value => <option key={value}>{value}</option>)}</select></Field>
      </div>
      {error && <p role="alert">{error}</p>}
      {busy ? <p className="teacher-note">Loading worksheet scores…</p> : worksheetGroups.length === 0 ? <p className="teacher-note">{rows.length ? "No worksheet scores match these filters." : "No worksheet scores have been imported yet."}</p> : <div className="worksheet-score-groups">{worksheetGroups.map(group => <section className="worksheet-score-group" key={group.key}>
        <details>
          <summary className="worksheet-score-group-heading"><h2>{group.title}</h2><span>{group.rows.length} {group.rows.length === 1 ? "score" : "scores"}</span></summary>
          <div className="score-table-scroll"><table className="worksheet-results-table"><thead><tr><th>Student ID</th><th>Student</th><th>Grade</th><th>Subject</th><th>Score</th><th>Attempts</th><th>Time Taken (Minutes)</th><th>Status</th><th>Assessment date</th><th>Teacher feedback</th><th>Update</th></tr></thead><tbody>{group.rows.map(row => <tr key={row.id}><td>{row.studentId || "—"}</td><td><strong>{row.studentName || "Unknown student"}</strong></td><td>{row.grade || "—"}</td><td>{row.subject || "—"}</td><td>{row.score} / {row.maxScore}</td><td>{row.attempts ?? "—"}</td><td>{row.timeTakenMinutes ?? "—"}</td><td>{row.completionStatus || "—"}</td><td>{dateLabel(row.assessmentDate || row.assessedAt)}</td><td>{row.notes || "—"}</td><td><WorksheetCorrection result={row} onSaved={load} /></td></tr>)}</tbody></table></div>
        </details>
      </section>)}</div>}
    </Card>
  </section>;
}
