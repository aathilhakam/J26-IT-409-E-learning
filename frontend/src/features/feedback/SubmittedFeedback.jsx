import React, { useEffect, useRef, useState } from "react";
import { Button, Card, Field } from "../../components";
import { api } from "../../services/api";

export function localDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
export function filterSubmittedFeedback(items, { student = "", lesson = "", date = "", status = "" } = {}) {
  return items.filter(row => row.submissionStatus === "SUBMITTED" && row.submittedAt
    && (!student || row.studentId === student) && (!lesson || row.materialId === lesson) && (!date || localDate(row.submittedAt) === date) && (!status || row.activityStatus === status))
    .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
}
const activityLabel = status => ({ COMPLETED: "Completed", IN_PROGRESS: "In progress", NOT_STARTED: "Not started" })[status] || "Unrecorded";
export function SubmittedFeedbackList({ items = [], loading, error, onRetry, studentView = false }) {
  const [filters, setFilters] = useState({});
  const set = key => event => setFilters(current => ({ ...current, [key]: event.target.value }));
  const submitted = filterSubmittedFeedback(items);
  const visible = filterSubmittedFeedback(items, filters);
  const groups = new Map();
  for (const row of visible) { const key = studentView ? row.materialId : "lesson"; if (!groups.has(key)) groups.set(key, []); groups.get(key).push(row); }
  return <Card as="section" className="student-profile-card submitted-feedback"><details>
    <summary className="submitted-feedback-summary"><div className="student-profile-card-heading"><div><span className="dashboard-section-kicker">LESSON FEEDBACK</span><h2>Teacher Feedback</h2><p>Teacher feedback for this student, grouped by lesson and learning material.</p></div></div></summary>
    {loading ? <p role="status">Loading teacher feedback…</p> : error ? <div role="alert"><p>{error}</p><Button onClick={onRetry}>Retry teacher feedback</Button></div> : <>
      {submitted.length > 0 && <div className={`lesson-submission-filters${studentView ? " student-feedback-filters" : ""}`}>
        {!studentView && <Field label="Student"><select value={filters.student || ""} onChange={set("student")}><option value="">All students</option>{[...new Map(submitted.map(row => [row.studentId, row.studentName])).entries()].map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></Field>}
        <Field label="Lesson / material"><select value={filters.lesson || ""} onChange={set("lesson")}><option value="">All lessons</option>{[...new Map(submitted.map(row => [row.materialId, row.lessonTitle])).entries()].map(([id, title]) => <option key={id} value={id}>{title}</option>)}</select></Field>
        <Field label="Submission date"><input type="date" value={filters.date || ""} onChange={set("date")} /></Field>
        {!studentView && <Field label="Activity status"><select value={filters.status || ""} onChange={set("status")}><option value="">All statuses</option>{["NOT_STARTED", "IN_PROGRESS", "COMPLETED"].map(status => <option key={status} value={status}>{activityLabel(status)}</option>)}</select></Field>}
        <Button variant="quiet" onClick={() => setFilters({})}>Clear filters</Button>
      </div>}
      {!submitted.length ? <p>No submitted feedback yet.</p> : !visible.length ? <p>No submitted feedback matches these filters.</p> : [...groups.entries()].map(([id, records]) => <section key={id} className="lesson-submission-group">
        {studentView && <h3><a href={`/dashboard/lessons/${encodeURIComponent(id)}`}>{records[0].lessonTitle}</a></h3>}
        {records.map((row, index) => <article className="lesson-submission-record" key={row.id}>
          <div className="lesson-feedback-student">{!studentView && <><strong>{row.studentName}</strong><span>{row.grade}</span></>}<span className="dashboard-activity-chip">Submitted</span>{studentView && index === 0 && <span className="dashboard-activity-chip">Latest for this lesson</span>}</div>
          <div className="lesson-submission-meta">{!studentView && <p><strong>{row.lessonTitle}</strong> · {activityLabel(row.activityStatus)}</p>}
          <time dateTime={row.submittedAt}>{new Date(row.submittedAt).toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}</time></div>
          <dl className="lesson-feedback-notes"><div><dt>Improvement observed</dt><dd>{row.improvement || "No improvement note supplied."}</dd></div><div><dt>Feedback / next step</dt><dd>{row.feedback || "No feedback note supplied."}</dd></div></dl>
          <details><summary>View observation details</summary><p>Participation: {row.observation?.participation} · Attention: {row.observation?.attentionLevel} · Confidence: {row.observation?.confidence}</p><p>Collaboration: {row.observation?.collaboration} · Learning style: {row.observation?.learningStyle}</p></details>
        </article>)}
      </section>)}
    </>}
  </details></Card>;
}
export default function SubmittedFeedback({ studentId, refreshKey = 0 }) {
  const [state, setState] = useState({ loading: true, items: [] });
  const [retry, setRetry] = useState(0);
  const generation = useRef(0);
  useEffect(() => {
    const request = ++generation.current; setState({ loading: true, items: [] });
    const load = api.studentFeedback(studentId);
    load.then(items => { if (request === generation.current) setState({ items }); })
      .catch(cause => { if (request === generation.current) setState({ items: [], error: cause.message || "Could not load submitted feedback." }); });
    return () => { generation.current++; };
  }, [studentId, refreshKey, retry]);
  return <SubmittedFeedbackList {...state} studentView={Boolean(studentId)} onRetry={() => setRetry(value => value + 1)} />;
}
