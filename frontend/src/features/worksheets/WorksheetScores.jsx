import React, { useEffect, useRef, useState } from "react";
import { Button, Card, Field } from "../../components";
import { api } from "../../services/api";
import PrototypeFeatureSnapshotImport from "../activities/PrototypeFeatureSnapshotImport";

export const PREVIEW_HEADERS = ["Student ID", "Student Name", "Grade", "Worksheet ID", "Worksheet Title", "Score / 10", "Attempts", "Status"];

export function WorksheetScorePreview({ preview, rows }) {
  return <div className="worksheet-score-preview" aria-label="Uploaded score preview">
    <h3>Preview Scores</h3>
    <p>{preview.valid} valid · {preview.invalid} invalid · {preview.blank} blank · {preview.existing} already recorded</p>
    {rows.length ? <div className="worksheet-results-table-wrap"><table className="worksheet-results-table">
      <thead><tr>{PREVIEW_HEADERS.map(header => <th key={header} scope="col">{header}</th>)}</tr></thead>
      <tbody>{rows.map(row => <tr key={`${row.row}-${row.studentId}`}>
        <td>{row.studentId}</td><td>{row.studentName}</td><td>{row.grade}</td><td>{row.worksheetId}</td><td>{row.title}</td><td>{row.scoreValue ?? (row.score == null ? "" : row.score)}</td><td>{row.attempts ?? ""}</td><td>{row.completionStatus}</td>
      </tr>)}</tbody>
    </table></div> : <p>No assessment records were found in this file.</p>}
    {rows.some(row => ["INVALID", "CONFLICT", "FAILED"].includes(row.status)) && <ul className="teacher-note" aria-label="Rows with errors">
      {rows.filter(row => ["INVALID", "CONFLICT", "FAILED"].includes(row.status)).map(row => <li key={`${row.row}-${row.studentId}-error`}>Excel row {row.row}: {row.message}</li>)}
    </ul>}
  </div>;
}

function validPrototypePrediction(prediction) {
  const probabilities = prediction?.probabilities || {};
  const needsPractice = probabilities["Needs Practice"];
  const successful = probabilities.Successful;
  return ["Needs Practice", "Successful"].includes(prediction?.predictedClass)
    && Number.isFinite(needsPractice) && Number.isFinite(successful)
    && Math.abs(needsPractice + successful - 1) <= 1e-6;
}

export default function WorksheetScores({ materials = [], showClassroomActivity = true }) {
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
  const [completionVerified, setCompletionVerified] = useState(false);
  const [importResult, setImportResult] = useState(null);
  const [prototypePredictions, setPrototypePredictions] = useState(null);
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
  const requiresCompletionVerification = Boolean(preview?.rows?.some(row => row.status === "VALID" && row.completionStatus === "COMPLETED"));

  function resetResult() {
    setError("");
    setNotice("");
    setPreview(null);
    setPreviewToken("");
    setImportResult(null);
    setPrototypePredictions(null);
    setCompletionVerified(false);
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
      anchor.download = "smartplay-worksheet-scores-v3.xlsx";
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
      setCompletionVerified(false);
      if (!result.rows?.length) setNotice("No assessment records were found in this file.");
    });
  }

  async function importFile() {
    if (!previewToken || !hasValidRows) { setError("Preview a valid score template before importing."); return; }
    await run(async () => {
      const result = await api.importScores(form(), previewToken, completionVerified);
      setImportResult(result);
      setPrototypePredictions(null);
      setPreviewToken("");
      setNotice(`${result.valid} scores saved to the database and student profiles; ${result.existing} already recorded; ${result.blank} blank and unchanged; ${result.invalid} rows failed validation or import. Existing scores were not overwritten.`);
      if (result.valid > 0) window.dispatchEvent(new CustomEvent("smartplay-evidence-updated", { detail: { path: "/worksheet-scores/import", method: "POST" } }));
    });
  }

  async function runPrototypePredictions() {
    const eligible = [...new Map((importResult?.rows || [])
      .filter(row => ["IMPORTED", "EXISTING"].includes(row.status) && row.score != null && row.studentId)
      .map(row => [row.studentId, row])).values()];
    if (!eligible.length) { setError("Import at least one saved score before requesting predictions."); return; }
    await run(async () => {
      const rows = await Promise.all(eligible.map(async row => {
        const base = { studentId: row.studentId, studentName: row.studentName, predictedClass: "", needsPracticeProbability: null, successfulProbability: null, modelVersion: "", predictedAt: "", status: "BLOCKED", details: "" };
        try {
          const prediction = await api.predictBinaryPrototype(row.studentId);
          if (!validPrototypePrediction(prediction)) return { ...base, status: "INVALID_MODEL_RESPONSE", details: "The prototype returned unsupported classes or invalid probabilities." };
          return { ...base, predictedClass: prediction.predictedClass, needsPracticeProbability: prediction.probabilities["Needs Practice"], successfulProbability: prediction.probabilities.Successful, modelVersion: prediction.modelVersion || "", predictedAt: prediction.predictedAt || "", status: prediction.status || "EXPLORATORY_PROTOTYPE", details: prediction.warning || "" };
        } catch (cause) {
          return { ...base, status: cause.status === 401 || cause.status === 403 ? "BLOCKED_AUTHORIZATION" : "BLOCKED_INPUT_OR_INFERENCE", details: cause.message || "The authenticated prototype prediction request failed." };
        }
      }));
      setPrototypePredictions(rows);
      const predicted = rows.filter(row => validPrototypePrediction({ predictedClass: row.predictedClass, probabilities: { "Needs Practice": row.needsPracticeProbability, Successful: row.successfulProbability } })).length;
      setNotice(`${predicted} actual experimental predictions returned; ${rows.length - predicted} students were blocked or failed. No prediction was generated for blocked students.`);
    });
  }

  const materialGradeMismatch = Boolean(selected?.grade && grade && selected.grade.trim().toLocaleLowerCase() !== grade.trim().toLocaleLowerCase());
  const materialPrompt = !materialId ? "Please select a worksheet material before downloading the score template." : "";

  return <>
  <Card as="section" className="worksheet-scores prototype-snapshot-card">
    <PrototypeFeatureSnapshotImport grade={grade} materials={materials} />
  </Card>
  {showClassroomActivity && <Card as="section" className="worksheet-scores">
    <span className="dashboard-section-kicker">ASSIGNMENT SCORES</span>
    <h2>Create an assignment and upload scores</h2>
    <p>Select an existing worksheet activity, download its student roster template, then enter each student’s score and upload the completed file. If the activity is not listed, add it to the Teacher Materials Repository above first.</p>
    <div className="worksheet-score-fields">
      <Field label="Grade / class"><select value={grade} disabled={busy} onChange={event => { resetResult(); setGrade(event.target.value); }}>
        <option value="">All grades · whole roster</option>{grades.map(value => <option key={value}>{value}</option>)}
      </select></Field>
      <Field label="Activity / worksheet"><select value={materialId} disabled={busy} onChange={event => {
        resetResult();
        const nextId = event.target.value;
        const nextMaterial = materials.find(material => material.id === nextId);
        setMaterialId(nextId);
        if (nextMaterial?.grade) {
          const matchingGrade = grades.find(value => value.trim().toLocaleLowerCase() === nextMaterial.grade.trim().toLocaleLowerCase());
          setGrade(matchingGrade || nextMaterial.grade.trim());
        }
      }}>
        <option value="">Select an activity or worksheet</option>
        {materials.map(material => <option value={material.id} key={material.id}>{material.title || material.originalFileName}</option>)}
      </select></Field>
      <Field label="Assessment date"><input type="date" value={date} disabled={busy} onChange={event => { resetResult(); setDate(event.target.value); }} /></Field>
    </div>
    <div className="modal-actions">
      <Button variant="secondary" disabled={busy || !materialId || materialGradeMismatch} onClick={download}>Download Student Score Template</Button>
      <label className="worksheet-file">Upload Completed Score Template<input type="file" accept=".xlsx" disabled={busy || !materialId} onChange={event => { resetResult(); setFile(event.target.files?.[0] || null); }} /></label>
      <Button variant="secondary" disabled={busy || !materialId || !file} onClick={previewFile}>Preview Scores</Button>
      <Button disabled={busy || !previewToken || !hasValidRows || (requiresCompletionVerification && !completionVerified)} onClick={importFile}>{busy ? "Working…" : "Import Scores"}</Button>
    </div>
    {!materialId && <p className="teacher-note" role="status">{materialPrompt}</p>}
    {materialGradeMismatch && <p className="teacher-note" role="alert">Choose a class that matches the selected worksheet material.</p>}
    <p className="teacher-note">The template pre-fills Student ID, Student Name, Grade, Worksheet ID, and Worksheet Title. Subject is taken from the selected activity, so it is omitted from the workbook. Enter the actual Score / 10 and Attempts. The sheet is editable; the importer still validates IDs against your roster.</p>
    <p className="teacher-note">Saved activity records can refresh the prototype’s activity counts, attempts, and completion inputs when enough history and teacher feedback are available. The entered Score / 10 itself is not one of the current model’s 10 features. If live records are incomplete, the imported Experimental Model snapshot can be used instead. This exploratory model cannot guarantee stable predictions.</p>
    <p className="teacher-note">Scores must be 0–10. Blank scores remain unchanged. Preview does not save data.</p>
    {requiresCompletionVerification && <label className="teacher-note worksheet-completion-confirm"><input type="checkbox" checked={completionVerified} disabled={busy} onChange={event => setCompletionVerified(event.target.checked)} /> I verified that each row marked COMPLETED was actually completed by the student. Check this to enable Import Scores and save valid rows to each student’s profile.</label>}
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {importResult && <div className="worksheet-prototype-predictions">
      <p className="teacher-note">Run the existing 10-feature binary prototype for students whose score rows were saved. The score itself is not a model input; saved Attempts and completion Status can contribute to its activity features. Predictions also require saved teacher feedback and lesson history, or a saved experimental snapshot. Missing inputs are reported as blocked.</p>
      <Button variant="secondary" disabled={busy || !(importResult.rows || []).some(row => ["IMPORTED", "EXISTING"].includes(row.status) && row.score != null)} onClick={runPrototypePredictions}>{busy ? "Working…" : "Run Experimental 10-Feature Predictions"}</Button>
      {prototypePredictions && <div className="worksheet-score-preview" aria-label="Experimental model predictions">
        <h3>Experimental model predictions</h3>
        <div className="worksheet-results-table-wrap"><table className="worksheet-results-table"><thead><tr>{["Student ID", "Student Name", "Predicted class", "Needs Practice", "Successful", "Model version", "Timestamp", "Status", "Details"].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>
          {prototypePredictions.map(row => <tr key={row.studentId}><td>{row.studentId}</td><td>{row.studentName}</td><td>{row.predictedClass}</td><td>{row.needsPracticeProbability == null ? "" : `${(row.needsPracticeProbability * 100).toFixed(2)}%`}</td><td>{row.successfulProbability == null ? "" : `${(row.successfulProbability * 100).toFixed(2)}%`}</td><td>{row.modelVersion}</td><td>{row.predictedAt}</td><td>{row.status}</td><td>{row.details}</td></tr>)}
        </tbody></table></div>
        <p className="teacher-note">These are experimental prototype results, not teacher-verified student outcomes.</p>
      </div>}
    </div>}
    {preview && <WorksheetScorePreview preview={preview} rows={rows} />}
  </Card>}
  </>;
}
