import React, { useEffect, useRef, useState } from "react";
import { Button, Card, Field } from "../../components";
import { api } from "../../services/api";

export const PREVIEW_HEADERS = ["Student ID", "Student Name", "Grade", "Worksheet ID", "Worksheet Title", "Subject", "Score / 10", "Teacher Feedback"];

export function WorksheetScorePreview({ preview, rows }) {
  return <div className="worksheet-score-preview" aria-label="Uploaded score preview">
    <h3>Preview Scores</h3>
    <p>{preview.valid} valid · {preview.invalid} invalid · {preview.blank} blank · {preview.existing} already recorded</p>
    {rows.length ? <div className="worksheet-results-table-wrap"><table className="worksheet-results-table">
      <thead><tr>{PREVIEW_HEADERS.map(header => <th key={header} scope="col">{header}</th>)}</tr></thead>
      <tbody>{rows.map(row => <tr key={`${row.row}-${row.studentId}`}>
        <td>{row.studentId}</td><td>{row.studentName}</td><td>{row.grade}</td><td>{row.worksheetId}</td><td>{row.title}</td><td>{row.subject}</td><td>{row.scoreValue ?? (row.score == null ? "" : row.score)}</td><td>{row.feedback}</td>
      </tr>)}</tbody>
    </table></div> : <p>No assessment records were found in this file.</p>}
    {rows.some(row => ["INVALID", "CONFLICT", "FAILED"].includes(row.status)) && <ul className="teacher-note" aria-label="Rows with errors">
      {rows.filter(row => ["INVALID", "CONFLICT", "FAILED"].includes(row.status)).map(row => <li key={`${row.row}-${row.studentId}-error`}>Excel row {row.row}: {row.message}</li>)}
    </ul>}
  </div>;
}

export default function WorksheetScores({ materials = [] }) {
  const [students, setStudents] = useState([]);
  const [grade, setGrade] = useState("Grade 1");
  const [materialId, setMaterialId] = useState("");
  const [date, setDate] = useState(() => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date()));
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [preview, setPreview] = useState(null);
  const [previewToken, setPreviewToken] = useState("");
  const [importResult, setImportResult] = useState(null);
  const lock = useRef(false);
  const selected = materials.find(material => material.id === materialId);

  useEffect(() => {
    let active = true;
    api.students().then(rows => {
      if (!active) return;
      const roster = rows.filter(student => student.active !== false);
      setStudents(roster);
      const availableGrades = [...new Set(roster.map(student => student.grade?.trim()).filter(Boolean))];
      setGrade(current => availableGrades.find(value => value.toLocaleLowerCase() === current.trim().toLocaleLowerCase()) || availableGrades.find(value => value.toLocaleLowerCase() === "grade 1") || availableGrades.sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" }))[0] || "");
    }).catch(reason => { if (active) setError(reason.message); });
    return () => { active = false; };
  }, []);

  const grades = [...new Set(students.map(student => student.grade).filter(Boolean))].sort();
  const rows = importResult?.rows || preview?.rows || [];
  const hasValidRows = (preview?.valid || 0) > 0;

  function resetResult() {
    setError("");
    setNotice("");
    setPreview(null);
    setPreviewToken("");
    setImportResult(null);
  }

  function context() {
    return { materialId, grade, date };
  }

  function form() {
    const data = new FormData();
    for (const [key, value] of Object.entries(context())) data.append(key, value);
    data.append("file", file);
    return data;
  }

  async function run(action) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    try { await action(); }
    catch (reason) { setError(reason.message || "Worksheet score action failed"); }
    finally { setBusy(false); lock.current = false; }
  }

  async function download() {
    if (!materialId) { setError("Please select a worksheet material before downloading the score template."); return; }
    await run(async () => {
      const blob = await api.downloadScoreTemplate(context());
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "smartplay-worksheet-scores.xlsx";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }

  async function previewFile() {
    if (!materialId) { setError("Please select a worksheet material before previewing scores."); return; }
    if (!file) { setError("Choose a completed score template to preview."); return; }
    await run(async () => {
      const result = await api.previewWorksheetScores(form());
      setPreview(result);
      setPreviewToken(result.token || "");
      setImportResult(null);
      if (!result.rows?.length) setNotice("No assessment records were found in this file.");
    });
  }

  async function importFile() {
    if (!previewToken || !hasValidRows) { setError("Preview a valid score template before importing."); return; }
    await run(async () => {
      const result = await api.importScores(form(), previewToken);
      setImportResult(result);
      setPreviewToken("");
      setNotice(`${result.valid} scores imported; ${result.existing} already recorded; ${result.blank} blank; ${result.invalid} rows failed validation or import. Existing scores were not overwritten.`);
    });
  }

  const materialGradeMismatch = Boolean(selected?.grade && grade && selected.grade.trim().toLocaleLowerCase() !== grade.trim().toLocaleLowerCase());
  const materialPrompt = !materialId ? "Please select a worksheet material before downloading the score template." : "";

  return <Card as="section" className="worksheet-scores">
    <span className="dashboard-section-kicker">ASSESSMENT RESULTS</span>
    <h2>Worksheet Assessment Scores</h2>
    <p>Choose a worksheet material and class, download the roster template, then upload, preview and import the completed scores.</p>
    <div className="worksheet-score-fields">
      <Field label="Grade / class"><select value={grade} disabled={busy} onChange={event => { resetResult(); setGrade(event.target.value); }}>
        <option value="">All grades · whole roster</option>{grades.map(value => <option key={value}>{value}</option>)}
      </select></Field>
      <Field label="Worksheet material"><select value={materialId} disabled={busy} onChange={event => {
        resetResult();
        const nextId = event.target.value;
        const nextMaterial = materials.find(material => material.id === nextId);
        setMaterialId(nextId);
        if (nextMaterial?.grade) {
          const matchingGrade = grades.find(value => value.trim().toLocaleLowerCase() === nextMaterial.grade.trim().toLocaleLowerCase());
          setGrade(matchingGrade || nextMaterial.grade.trim());
        }
      }}>
        <option value="">Select a worksheet material</option>
        {materials.map(material => <option value={material.id} key={material.id}>{material.title || material.originalFileName}</option>)}
      </select></Field>
      <Field label="Assessment date"><input type="date" value={date} disabled={busy} onChange={event => { resetResult(); setDate(event.target.value); }} /></Field>
    </div>
    <div className="modal-actions">
      <Button variant="secondary" disabled={busy || !materialId || materialGradeMismatch} onClick={download}>Download Score Template</Button>
      <label className="worksheet-file">Upload Completed Score Template<input type="file" accept=".xlsx" disabled={busy || !materialId} onChange={event => { resetResult(); setFile(event.target.files?.[0] || null); }} /></label>
      <Button variant="secondary" disabled={busy || !materialId || !file} onClick={previewFile}>Preview Scores</Button>
      <Button disabled={busy || !previewToken || !hasValidRows} onClick={importFile}>{busy ? "Working…" : "Import Scores"}</Button>
    </div>
    {!materialId && <p className="teacher-note" role="status">{materialPrompt}</p>}
    {materialGradeMismatch && <p className="teacher-note" role="alert">Choose a class that matches the selected worksheet material.</p>}
    <p className="teacher-note">Student and worksheet identity details are protected. Subject, Score / 10, and Teacher Feedback are editable in the downloaded template.</p>
    <p className="teacher-note">Scores must be 0–10. Blank scores remain unchanged. Preview does not save data; importing happens only after you confirm.</p>
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {preview && <WorksheetScorePreview preview={preview} rows={rows} />}
  </Card>;
}
