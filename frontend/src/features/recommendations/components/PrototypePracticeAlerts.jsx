import React, { useEffect, useMemo, useState } from "react";
import { Card, Button } from "../../../components";
import { api } from "../../../services/api";
import { meetsPrototypeSuccessThreshold, PROTOTYPE_SUCCESS_THRESHOLD } from "../prototypePolicy";

export function completedPracticeAreas(scores = [], threshold = 75) {
  const groups = new Map();
  for (const row of scores) {
    const score = Number(row.score), max = Number(row.maxScore);
    if (!Number.isFinite(score) || !Number.isFinite(max) || max <= 0 || score < 0 || score > max) continue;
    if (row.completionStatus && !["COMPLETED", "Completed"].includes(row.completionStatus)) continue;
    const key = row.materialId || (row.assessmentId ? `assessment:${row.assessmentId}` : row.worksheetTitle || row.subject || row.assessmentType);
    if (!key) continue;
    const group = groups.get(key) || { id: key, title: row.worksheetTitle || row.assessmentId || row.assessmentType || row.subject || "Learning area", subject: row.subject, score: 0, maxScore: 0 };
    group.score += score;
    group.maxScore += max;
    groups.set(key, group);
  }
  return [...groups.values()].map(group => ({ ...group, percentage: group.score / group.maxScore * 100 }))
    .filter(group => group.percentage < threshold).sort((a, b) => a.percentage - b.percentage);
}

export function prototypeFeedbackPracticeAreas(features = {}) {
  const areas = [];
  if (features.Participation === "Low") areas.push({ category: "Participation", value: features.Participation });
  if (features.Attention_Level === "Low") areas.push({ category: "Attention Level", value: features.Attention_Level });
  if (["Fair", "Needs improvement"].includes(features.Collaboration)) areas.push({ category: "Collaboration", value: features.Collaboration });
  if (features.Learning_Difficulty && features.Learning_Difficulty !== "None identified") {
    areas.push({ category: "Learning Difficulty", value: features.Learning_Difficulty });
  }
  return areas;
}

export function prototypePracticePathway(features = {}) {
  const areas = prototypeFeedbackPracticeAreas(features);
  const exercises = {
    Participation: [
      "Begin with a brief, choice-based task and invite the learner to take one small turn.",
      "Repeat the activity with the learner leading one step, then review participation with the teacher.",
    ],
    "Attention Level": [
      "Present one short instruction at a time with a clear visual cue and a brief check-in between steps.",
      "Gradually join two short steps together when the learner is ready, then review how they went.",
    ],
    Collaboration: [
      "Use a paired activity with simple alternating roles so each learner has a clear turn.",
      "Complete one short shared task and review turn-taking and cooperation with the teacher.",
    ],
    "Pencil control": [
      "Trace large straight and curved paths, then practice tracing simple shapes.",
      "Copy a short line or shape pattern, pausing when needed for a comfortable pencil grip.",
    ],
    "Letter formation": [
      "Trace a small set of familiar letters using a clear starting point and direction.",
      "Copy those letters independently on a guide line and review their shape together.",
    ],
    "Vocabulary development": [
      "Name familiar pictures and match each picture to its spoken or printed word.",
      "Practice using a few of those words to describe a picture or answer a simple question.",
    ],
    "Listening difficulty": [
      "Practice following one short spoken instruction, supported by a gesture or picture cue.",
      "When ready, try two short instructions in sequence and ask the learner to repeat the steps.",
    ],
    "Addition difficulty": [
      "Use counters or small objects to model one addition problem at a time.",
      "Practice a few similar problems with objects, then let the learner explain one answer.",
    ],
    "Number recognition": [
      "Match a small set of printed numerals to groups of objects showing the same quantity.",
      "Practice identifying those numerals in a short mixed order activity.",
    ],
    "Phonics difficulty": [
      "Practice hearing and matching one familiar letter sound with picture examples.",
      "Review the same sound in a short set of familiar words with teacher support.",
    ],
    "Reading difficulty": [
      "Read a short, familiar line together, pausing to identify each word or picture cue.",
      "Repeat the line and invite the learner to read familiar words independently.",
    ],
    "Shape recognition": [
      "Match familiar shapes by outline, first with identical examples and then mixed examples.",
      "Find and name those shapes in a simple picture or classroom-object activity.",
    ],
    "Slow writing": [
      "Complete a very short writing task with enough time and a clear example to follow.",
      "Repeat a similar short task, focusing on comfortable pacing and legibility rather than speed.",
    ],
  };
  return areas.map(area => ({ ...area, steps: exercises[area.value] || [
    `Choose a short, age-appropriate activity that practices “${area.value}”.`,
    "Review the activity with the learner and adjust the next step based on observed work.",
  ] }));
}

export default function PrototypePracticeAlerts({ students = [], onCreatePracticeModule }) {
  const roster = useMemo(() => students.filter(student => student?.id && student.active !== false), [students]);
  const rosterKey = roster.map(student => student.id).sort().join("|");
  const [results, setResults] = useState([]);
  const [busy, setBusy] = useState(roster.length > 0);
  const [error, setError] = useState("");
  const [approvalReasons, setApprovalReasons] = useState({});
  const [approvalFormStudentId, setApprovalFormStudentId] = useState("");
  const [approvalBusyStudentId, setApprovalBusyStudentId] = useState("");
  const [approvalError, setApprovalError] = useState("");
  const [expanded, setExpanded] = useState(false);
  const [approvedExpanded, setApprovedExpanded] = useState(false);
  const [expandedStudentIds, setExpandedStudentIds] = useState(() => new Set());

  function toggleStudent(studentId) {
    setExpandedStudentIds(current => {
      const next = new Set(current);
      if (next.has(studentId)) next.delete(studentId); else next.add(studentId);
      return next;
    });
  }

  async function refresh() {
    setBusy(true); setError("");
    let savedApprovals = [];
    try {
      const response = await api.listPrototypeProgressionApprovals();
      savedApprovals = Array.isArray(response) ? response : [];
    } catch (cause) {
      setError(`Could not load saved teacher approvals: ${cause.message || "request failed"}`);
    }
    const evaluated = await Promise.all(roster.map(async student => {
      try {
        const prediction = await api.predictBinaryPrototype(student.id);
        const probabilities = prediction?.probabilities || {};
        const needs = probabilities["Needs Practice"], successful = probabilities.Successful;
        if (!["Needs Practice", "Successful"].includes(prediction?.predictedClass)
          || !Number.isFinite(needs) || !Number.isFinite(successful)
          || needs < 0 || needs > 1 || successful < 0 || successful > 1
          || Math.abs(needs + successful - 1) > 1e-6) {
          return { student, status: "UNAVAILABLE", reason: "Prototype returned invalid binary class probabilities." };
        }
        const approval = savedApprovals.find(item => item.studentId === student.id);
        if (approval) return { student, prediction, approval, status: "APPROVED" };
        if (meetsPrototypeSuccessThreshold(successful)) return { student, prediction, status: "READY" };
        return { student, prediction, status: "PRACTICE" };
      } catch (cause) {
        const approval = savedApprovals.find(item => item.studentId === student.id);
        if (approval) return { student, approval, status: "APPROVED" };
        return { student, status: "UNAVAILABLE", reason: cause.message || "Prototype prediction unavailable." };
      }
    }));
    setResults(evaluated);
    const failures = evaluated.filter(row => row.status === "UNAVAILABLE");
    if (roster.length && failures.length === roster.length) setError(previous => [previous, "No students could be evaluated. Check that prototype snapshots and saved feedback are available."].filter(Boolean).join(" "));
    setBusy(false);
  }

  async function submitApproval(studentId) {
    const rationale = approvalReasons[studentId] || "";
    if (!rationale.trim()) {
      setApprovalError("Enter the teacher’s reason before approving progression.");
      return;
    }
    setApprovalBusyStudentId(studentId);
    setApprovalError("");
    try {
      const approval = await api.approvePrototypeProgression(studentId, rationale.trim());
      setResults(current => current.map(row => row.student.id === studentId
        ? { ...row, status: "APPROVED", approval }
        : row));
      setApprovalFormStudentId("");
      setApprovalReasons(current => ({ ...current, [studentId]: "" }));
    } catch (cause) {
      setApprovalError(cause.message || "Could not save the teacher approval.");
    } finally {
      setApprovalBusyStudentId("");
    }
  }

  useEffect(() => { setResults([]); if (roster.length) void refresh(); else setBusy(false); }, [rosterKey]);

  const practice = results.filter(row => row.status === "PRACTICE");
  const teacherApproved = results.filter(row => row.status === "APPROVED");
  const unavailable = results.filter(row => row.status === "UNAVAILABLE");
  return <Card as="section" className="prototype-practice-alerts" aria-label="Students needing practice">
    <div className="dashboard-section-head">
      <div><span className="dashboard-section-kicker purple">EXPERIMENTAL MODEL ALERTS</span><h2><button type="button" className="practice-alert-toggle" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}><span aria-hidden="true">{expanded ? "▾" : "▸"}</span> Students needing practice <span className="practice-alert-count">{busy ? "…" : practice.length}</span></button></h2><p>Click the arrow to view students with a valid 10-feature prototype Successful probability below {PROTOTYPE_SUCCESS_THRESHOLD * 100}%. Experimental guidance for teacher review.</p></div>
      <Button type="button" variant="secondary" onClick={() => void refresh()} disabled={busy || !roster.length}>{busy ? "Checking…" : "Refresh alerts"}</Button>
    </div>
    {busy && !results.length ? <p role="status">Checking saved prototype features for {roster.length} active students…</p> : null}
    {error && <p role="alert">{error}</p>}
    {!busy && results.length === roster.length && !practice.length && !teacherApproved.length && !unavailable.length && <p role="status">No students currently meet the below-{PROTOTYPE_SUCCESS_THRESHOLD * 100}% alert rule.</p>}
    {expanded && practice.length > 0 && <div className="prototype-practice-alert-list">{practice.map(({ student, prediction }) => {
      const categoryAreas = prototypeFeedbackPracticeAreas(prediction.features);
      const pathway = prototypePracticePathway(prediction.features);
      const studentExpanded = expandedStudentIds.has(student.id);
      return <article className="prototype-practice-alert" key={student.id}>
        <div className="prototype-practice-alert-header">
          <button type="button" className="prototype-practice-alert-summary" aria-expanded={studentExpanded} onClick={() => toggleStudent(student.id)}>
            <span className={`prototype-practice-alert-arrow${studentExpanded ? " is-open" : ""}`} aria-hidden="true">▸</span>
            <span className="prototype-practice-alert-identity"><strong>{student.name || "Student"}</strong><small>Student ID: {student.id} · {student.grade || "Grade not recorded"}</small></span>
          </button>
          <div className="prototype-practice-alert-actions">
            <strong className="prototype-practice-alert-probability">{(prediction.probabilities.Successful * 100).toFixed(1)}% Successful</strong>
            <Button type="button" variant="secondary" className="practice-module-create-button" onClick={() => onCreatePracticeModule?.(student, categoryAreas, pathway)} disabled={!onCreatePracticeModule}>Create practice module</Button>
            <Button type="button" variant="secondary" onClick={() => { setApprovalError(""); setApprovalFormStudentId(current => current === student.id ? "" : student.id); }}>Approve for next lesson</Button>
          </div>
        </div>
        {approvalFormStudentId === student.id && <form className="prototype-progression-approval-form" onSubmit={event => { event.preventDefault(); void submitApproval(student.id); }}>
          <label htmlFor={`progression-reason-${student.id}`}>Reason for approving this student to progress</label>
          <textarea id={`progression-reason-${student.id}`} value={approvalReasons[student.id] || ""} maxLength={2000} required rows={3} onChange={event => setApprovalReasons(current => ({ ...current, [student.id]: event.target.value }))} placeholder="Enter the teacher’s reason" />
          <div><Button type="submit" disabled={approvalBusyStudentId === student.id || !approvalReasons[student.id]?.trim()}>{approvalBusyStudentId === student.id ? "Saving approval…" : "Confirm teacher approval"}</Button><Button type="button" variant="secondary" onClick={() => setApprovalFormStudentId("")}>Cancel</Button></div>
          {approvalError && <p role="alert">{approvalError}</p>}
        </form>}
        {studentExpanded && <div className="prototype-practice-alert-details">
          <p>Prototype class: <strong>{prediction.predictedClass}</strong> · Practice review suggested because Successful probability is below {PROTOTYPE_SUCCESS_THRESHOLD * 100}%.</p>
          <section className="prototype-improvement-pathway" aria-label={`Teacher feedback practice categories for ${student.name || "student"}`}>
            <h4>Feedback categories to review for practice</h4>
            <p>Review cues from the prototype’s four categorical inputs—not explanations of the model’s probability.</p>
            {categoryAreas.length ? <ul>
              {categoryAreas.map(area => <li key={area.category}><strong>{area.category}:</strong> {area.value}</li>)}
            </ul> : <p>No low or difficulty value is present in these prototype inputs. The model returns an overall probability and does not attribute it to a specific category.</p>}
          </section>
          {pathway.length > 0 && <section className="prototype-suggested-learning-path" aria-label={`Suggested practice path for ${student.name || "student"}`}>
            <h4>Suggested practice path · teacher review</h4>
            <p>These are rule-based activity ideas linked to saved feedback. They are not explanations of the model’s probability or an automatically assigned lesson.</p>
            <ol>{pathway.map(area => <li key={`${area.category}-${area.value}`}><strong>{area.category}: {area.value}</strong><ol>{area.steps.map((step, stepIndex) => <li key={stepIndex}>{step}</li>)}</ol></li>)}</ol>
          </section>}
          <small>Model version: {prediction.modelVersion || "not recorded"} · {prediction.predictedAt ? new Date(prediction.predictedAt).toLocaleString() : "timestamp unavailable"} · {prediction.warning || "Experimental only; teacher review required."}</small>
        </div>}
      </article>;
    })}</div>}
    {expanded && teacherApproved.length > 0 && <section className="prototype-teacher-approved-list" aria-label="Teacher approved students">
      <h3><button type="button" className="practice-alert-toggle" aria-expanded={approvedExpanded} onClick={() => setApprovedExpanded(value => !value)}><span aria-hidden="true">{approvedExpanded ? "▾" : "▸"}</span> Teacher-approved for next lesson <span className="practice-alert-count">{teacherApproved.length}</span></button></h3>
      {approvedExpanded && <><p>These students remain below the model threshold. A teacher recorded a separate progression decision; their model prediction was not changed.</p>
      <div className="prototype-practice-alert-list">{teacherApproved.map(({ student, prediction, approval }) => <article className="prototype-practice-alert is-teacher-approved" key={student.id}>
        <div className="prototype-practice-alert-header"><div className="prototype-practice-alert-identity"><strong>{student.name || approval?.studentName || "Student"}</strong><small>Student ID: {student.id} · {student.grade || approval?.studentGrade || "Grade not recorded"}</small></div><strong className="prototype-practice-alert-probability">{approval ? (approval.successfulProbability * 100).toFixed(1) : prediction ? (prediction.probabilities.Successful * 100).toFixed(1) : "—"}% model probability · Teacher approved</strong></div>
        <p><strong>Teacher reason:</strong> {approval?.rationale || "Reason unavailable"}</p>
        <small>Approved {approval?.approvedAt ? new Date(approval.approvedAt).toLocaleString() : "date unavailable"}. This records teacher approval for progression; it does not assign a lesson or alter the model output.</small>
      </article>)}</div>
      </>}
    </section>}
    {expanded && !busy && results.length === roster.length && practice.length === 0 && !teacherApproved.length && !error && <p role="status">No students currently meet the below-{PROTOTYPE_SUCCESS_THRESHOLD * 100}% alert rule.</p>}
    {unavailable.length > 0 && <details className="prototype-practice-unavailable"><summary>{unavailable.length} student{unavailable.length === 1 ? "" : "s"} could not be evaluated</summary><ul>{unavailable.map(row => <li key={row.student.id}>{row.student.name} ({row.student.id}): {row.reason}</li>)}</ul></details>}
  </Card>;
}
