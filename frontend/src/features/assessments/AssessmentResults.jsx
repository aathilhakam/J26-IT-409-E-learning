import React, { useEffect, useRef, useState } from "react";
import { Button, Card, Field } from "../../components";
import { api } from "../../services/api";

const MEASURE_HEADERS = ["Pre_Test", "Post_Test", "Unit_Test", "Monthly_Test", "Term_Result", "Motor_Skills_Score", "Creativity_Score", "Interactive_Engagement"];

export default function AssessmentResults() {
  const [grades, setGrades] = useState([]);
  const [grade, setGrade] = useState("");
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [token, setToken] = useState("");
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const lock = useRef(false);

  useEffect(() => {
    let active = true;
    api.students().then(students => {
      if (!active) return;
      const values = [...new Set(students.filter(student => student.active !== false).map(student => student.grade?.trim()).filter(Boolean))]
        .sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" }));
      setGrades(values);
      setGrade(values.find(value => value.toLowerCase() === "grade 1") || values[0] || "");
    }).catch(reason => { if (active) setError(reason.message || "Could not load classes."); });
    return () => { active = false; };
  }, []);

  function reset() { setPreview(null); setToken(""); setResult(null); setError(""); setNotice(""); }
  function workbookForm() { const form = new FormData(); if (file) form.append("file", file); return form; }

  async function run(action) {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError(""); setNotice("");
    try { await action(); }
    catch (reason) { setError(reason.message || "Assessment results request failed."); }
    finally { setBusy(false); lock.current = false; }
  }

  async function download() {
    await run(async () => {
      const blob = await api.downloadAssessmentResultsTemplate(grade);
      const url = URL.createObjectURL(blob); const anchor = document.createElement("a");
      anchor.href = url; anchor.download = "smartplay-assessment-results.xlsx"; document.body.appendChild(anchor); anchor.click(); anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }

  async function previewFile() {
    if (!file) { setError("Choose a completed Assessment Results template first."); return; }
    await run(async () => {
      const data = await api.previewAssessmentResults(workbookForm());
      setPreview(data); setToken(data.token || ""); setResult(null);
      if (!data.rows?.length) setNotice("No assessment result rows were found in this workbook.");
    });
  }

  async function importFile() {
    if (!file || !token || !(preview?.valid > 0)) { setError("Preview at least one valid row before importing."); return; }
    await run(async () => {
      const data = await api.importAssessmentResults(workbookForm(), token);
      setResult(data); setToken("");
      setNotice(`${data.imported} assessment results imported · ${data.existing} already recorded · ${data.invalid} invalid or failed.`);
    });
  }

  const rows = result?.rows || preview?.rows || [];
  return <Card as="section" className="worksheet-scores assessment-results-entry">
    <span className="dashboard-section-kicker">ASSESSMENT RESULTS</span>
    <h2>Assessment Results</h2>
    <p>Download a roster workbook, enter the assessment details in Excel, then upload and preview it before importing results.</p>
    <div className="worksheet-score-fields assessment-import-fields">
      <Field label="Grade / class"><select value={grade} disabled={busy} onChange={event => { reset(); setGrade(event.target.value); }}>
        {grades.map(value => <option key={value} value={value}>{value}</option>)}
      </select></Field>
      <div className="assessment-template-help"><strong>Excel columns</strong><span>Student ID, Student Name, Grade, Assessment Identifier, Assessment Type, Source Component, Score, Maximum Score, Completion Status, Attempts, Time Taken, and the 8 measured features.</span></div>
    </div>
    <div className="modal-actions">
      <Button variant="secondary" disabled={busy || !grade} onClick={download}>Download Assessment Results Template</Button>
      <label className="worksheet-file">Upload completed template<input type="file" accept=".xlsx" disabled={busy} onChange={event => { reset(); setFile(event.target.files?.[0] || null); }} /></label>
      <Button variant="secondary" disabled={busy || !file} onClick={previewFile}>Preview Results</Button>
      <Button disabled={busy || !token || !(preview?.valid > 0)} onClick={importFile}>{busy ? "Working…" : "Import Results"}</Button>
    </div>
    <p className="teacher-note">Student ID, name, and grade are prefilled from your active class roster. Enter only measured values. Assessment Identifier and Score are required; measured feature values must be 0–100. Blank measurement cells stay blank.</p>
    {error && <p role="alert" className="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {preview && <div className="worksheet-score-preview" aria-label="Assessment results preview">
      <h3>Preview Assessment Results</h3>
      <p>{preview.valid} valid · {preview.invalid} invalid · {preview.blank} blank · {preview.existing} already recorded</p>
      {rows.length > 0 && <div className="worksheet-results-table-wrap"><table className="worksheet-results-table">
        <thead><tr><th>Row</th><th>Student ID</th><th>Student Name</th><th>Grade</th><th>Assessment Identifier</th><th>Assessment Type</th><th>Source Component</th><th>Score</th><th>Maximum Score</th><th>Completion Status</th><th>Attempts</th><th>Time Taken (Minutes)</th>{MEASURE_HEADERS.map(name => <th key={name}>{name.replaceAll("_", " ")}</th>)}<th>Import status</th><th>Message</th></tr></thead>
        <tbody>{rows.map(row => <tr key={`${row.row}-${row.studentId}`}><td>{row.row}</td><td>{row.studentId}</td><td>{row.studentName}</td><td>{row.grade}</td><td>{row.assessmentId}</td><td>{row.assessmentType}</td><td>{row.sourceComponent}</td><td>{row.score ?? ""}</td><td>{row.maxScore ?? ""}</td><td>{row.status}</td><td>{row.attempts ?? ""}</td><td>{row.timeTakenMinutes ?? ""}</td>{MEASURE_HEADERS.map(name => <td key={name}>{row.measures?.[name] ?? ""}</td>)}<td>{row.resultStatus}</td><td>{row.message}</td></tr>)}</tbody>
      </table></div>}
    </div>}
  </Card>;
}
