import React, { useRef, useState } from "react";
import { Button, Field } from "../../components";
import { api } from "../../services/api";

const FEATURES = ["Average_Attempts", "Total_Activities", "Completion_Rate", "Historical_Lessons", "Completed_Historical_Lessons", "Historical_Completion_Rate"];
const isPrediction = row => ["Needs Practice", "Successful"].includes(row.predictedClass)
  && Number.isFinite(row.needsPracticeProbability) && Number.isFinite(row.successfulProbability)
  && Math.abs(row.needsPracticeProbability + row.successfulProbability - 1) <= 1e-6;

export default function PrototypeFeatureSnapshotImport({ grade, materials = [], includeDownload = true }) {
  const input = useRef(null);
  const [worksheetId, setWorksheetId] = useState("");
  const [file, setFile] = useState(null), [preview, setPreview] = useState(null), [token, setToken] = useState("");
  const [result, setResult] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState(""), [notice, setNotice] = useState("");
  const [batchRows, setBatchRows] = useState(null);
  async function run(action) { setBusy(true); setError(""); setNotice(""); try { await action(); } catch (cause) { setError(cause.message || "Prototype feature workbook request failed."); } finally { setBusy(false); } }
  function formData() { const form = new FormData(); form.append("file", file); return form; }
  function selectFile(event) {
    const selected = event.target.files?.[0];
    if (!selected) return;
    setFile(selected); setPreview(null); setToken(""); setResult(null); setBatchRows(null); setError(""); setNotice("");
  }
  function clearFile() {
    setFile(null); setPreview(null); setToken(""); setResult(null); setBatchRows(null); setError(""); setNotice("");
    if (input.current) input.current.value = "";
  }
  async function download() { if (!worksheetId) { setError("Select a worksheet material before downloading the experimental model template."); return; } await run(async () => { const blob = await api.downloadPrototypeFeatureTemplate(grade, worksheetId); const url = URL.createObjectURL(blob); const anchor = document.createElement("a"); anchor.href = url; anchor.download = "SmartPlay_Prototype_Features.xlsx"; document.body.appendChild(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000); }); }
  async function requireTeacher() {
    const account = await api.currentUser();
    if (!account) throw new Error("No authenticated teacher session. Sign in through SmartPlay and try again.");
    if (account.role && account.role !== "TEACHER") throw new Error("This workflow is available to teacher accounts only.");
    return account;
  }
  async function previewFile() { if (!file) { setError("Choose the supplied Prototype Features workbook first."); return; } await run(async () => { await requireTeacher(); const data = await api.previewPrototypeFeatures(formData()); setPreview(data); setToken(data.token || ""); setResult(null); setBatchRows(null); }); }
  async function importFile() { if (!token || !preview?.valid) { setError("Preview at least one valid student row before importing."); return; } await run(async () => { await requireTeacher(); const data = await api.importPrototypeFeatures(formData(), token); setResult(data); setToken(""); setBatchRows(null); setNotice(`${data.imported} snapshots saved · ${data.existing} already saved · ${data.blank} blank rows · ${data.invalid} rows failed.`); window.dispatchEvent(new CustomEvent("smartplay-evidence-updated", { detail: { path: "/classroom-activities/prototype-import", method: "POST" } })); }); }
  async function runBatch() {
    const sourceRows = result?.rows || preview?.rows || [];
    if (!worksheetId) { setError("Select the assigned Song Book lesson / worksheet before running predictions."); return; }
    if (sourceRows.length !== 20 || new Set(sourceRows.map(row => row.studentId).filter(Boolean)).size !== 20) { setError("The batch requires exactly 20 unique student rows. Preview the supplied Grade 1 workbook."); return; }
    if (sourceRows.some(row => row.grade?.toLocaleLowerCase() !== "grade 1" || row.worksheetId !== worksheetId)) { setError("Every row must be Grade 1 and its exact Worksheet ID must match the selected Song Book lesson."); return; }
    const notPersisted = sourceRows.filter(row => !["IMPORTED", "EXISTING"].includes(row.status));
    if (notPersisted.length) { setError("Import the valid feature snapshots first. No predictions were requested."); return; }
    await run(async () => {
      await requireTeacher();
      const workspace = await api.lessonWorkspace(worksheetId);
      const lesson = workspace?.lesson;
      if (!lesson || lesson.id !== worksheetId) throw new Error("The selected Song Book lesson is not available to this teacher.");
      const recipients = new Map((lesson.recipients || []).filter(row => row.activeStudent).map(row => [row.studentId, row]));
      const output = await Promise.all(sourceRows.map(async row => {
        const base = { studentId: row.studentId, studentName: row.studentName, grade: row.grade, lessonId: lesson.id, worksheetId, modelName: "10-feature binary Random Forest prototype", modelVersion: "", predictedClass: "", needsPracticeProbability: null, successfulProbability: null, predictedAt: null, status: "BLOCKED", details: "" };
        if (!recipients.has(row.studentId)) return { ...base, details: "Student is not an active recipient of the selected lesson." };
        try {
          const feedback = await api.studentFeedback(row.studentId);
          const saved = (Array.isArray(feedback) ? feedback : []).find(item => item.materialId === lesson.id && item.studentId === row.studentId && item.submissionStatus === "SUBMITTED");
          if (!saved) return { ...base, details: "No submitted feedback record for this student and lesson." };
          const observation = saved.observation || {};
          const missing = [["Participation", observation.participation], ["Attention Level", observation.attentionLevel], ["Collaboration", observation.collaboration], ["Learning Difficulty", observation.learningDifficulty]].filter(([, value]) => !value).map(([name]) => name);
          if (missing.length) return { ...base, details: `Saved feedback is missing required categories: ${missing.join(", ")}.` };
          const prediction = await api.predictBinaryPrototype(row.studentId);
          const probabilities = prediction.probabilities || {};
          const np = probabilities["Needs Practice"], successful = probabilities.Successful;
          if (!["Needs Practice", "Successful"].includes(prediction.predictedClass) || !Number.isFinite(np) || !Number.isFinite(successful) || Math.abs(np + successful - 1) > 1e-6) {
            return { ...base, modelVersion: prediction.modelVersion || "", predictedAt: prediction.predictedAt || null, status: "INFERENCE_INVALID_RESPONSE", details: "Prototype endpoint returned unsupported classes or invalid probabilities." };
          }
          return { ...base, modelVersion: prediction.modelVersion || "", predictedClass: prediction.predictedClass, needsPracticeProbability: np, successfulProbability: successful, predictedAt: prediction.predictedAt || null, status: prediction.status || "PREDICTED", details: "" };
        } catch (cause) { return { ...base, status: cause.status === 401 || cause.status === 403 ? "BLOCKED_AUTHORIZATION" : "INFERENCE_FAILED", details: cause.message || "Authenticated prediction request failed." }; }
      }));
      setBatchRows(output);
      const predicted = output.filter(isPrediction);
      const { blob } = await api.downloadBinaryPrototypeBatchReport(output);
      const url = URL.createObjectURL(blob), anchor = document.createElement("a");
      anchor.href = url; anchor.download = "Grade_1_Binary_Prototype_Predictions.xlsx"; document.body.appendChild(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      setNotice(`${predicted.length} actual predictions returned; ${output.length - predicted.length} students blocked or failed. Downloaded the experimental prototype report.`);
    });
  }
  const rows = result?.rows || preview?.rows || [];
  const noNewSnapshots = Boolean(preview && preview.valid === 0 && preview.existing > 0);
  return <section className="prototype-feature-import" aria-label="Experimental model student workbook">
    <h3>Experimental model: student template</h3>
    <p>Upload the supplied six-numeric-feature workbook, preview it, then import snapshots through the authenticated workflow. After import, select its assigned Song Book lesson and run the 10-feature prototype batch. The model reads four categories from saved feedback. Experimental predictions are not teacher-verified outcomes.</p>
    <details><summary>Show the six required columns</summary><ol>{FEATURES.map(feature => <li key={feature}><code>{feature}</code></li>)}</ol></details>
    <p className="teacher-note">Rates are 0–100; activity and lesson counts must be whole numbers. Imported snapshots become the prototype’s inputs for that student.</p>
    {includeDownload && <div className="worksheet-score-fields">
      <Field label="Song Book lesson / worksheet material"><select value={worksheetId} disabled={busy} onChange={event => { setWorksheetId(event.target.value); setError(""); setPreview(null); setToken(""); setResult(null); setBatchRows(null); setNotice(""); }}>
        <option value="">Select a worksheet material</option>
        {materials.map(material => <option value={material.id} key={material.id}>{material.title || material.originalFileName}</option>)}
      </select></Field>
    </div>}
    <div className="modal-actions">
      {includeDownload && <Button variant="secondary" disabled={busy || !grade || !worksheetId} onClick={download}>Download Template</Button>}
      <label className="worksheet-file">Upload completed template<input ref={input} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" disabled={busy} onChange={selectFile} /></label>
      {file && <Button variant="secondary" disabled={busy} onClick={clearFile}>Clear selected file</Button>}
      <Button variant="secondary" disabled={busy || !file} onClick={previewFile}>Preview Features</Button>
      <Button disabled={busy || !token || !preview?.valid || (preview?.sampleOnly || 0) > 0} onClick={importFile}>{busy ? "Working…" : noNewSnapshots ? "Snapshots Already Saved" : "Import Feature Snapshots"}</Button>
      <Button variant="secondary" disabled={busy || !worksheetId || !(result?.rows || preview?.rows)?.length} onClick={runBatch}>{busy ? "Working…" : "Verify Feedback & Run 10-Feature Batch"}</Button>
    </div>
    {noNewSnapshots && <p className="teacher-note" role="status">These exact feature snapshots are already stored for the listed students. Open a student profile to view the latest experimental model snapshot; no duplicate import is needed.</p>}
    {includeDownload && !worksheetId && <p className="teacher-note" role="status">Select a worksheet material above to download this template with its worksheet ID.</p>}
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {batchRows && <section className="worksheet-score-preview" aria-label="Prototype batch prediction results"><h4>Experimental prototype batch results</h4><p>{batchRows.filter(isPrediction).length} predictions returned · {batchRows.filter(row => !isPrediction(row)).length} blocked or failed</p><div className="worksheet-results-table-wrap"><table className="worksheet-results-table"><thead><tr>{["Student ID", "Student Name", "Predicted class", "Needs Practice", "Successful", "Model version", "Timestamp", "Status", "Details"].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{batchRows.map(row => <tr key={row.studentId}><td>{row.studentId}</td><td>{row.studentName}</td><td>{row.predictedClass}</td><td>{row.needsPracticeProbability ?? ""}</td><td>{row.successfulProbability ?? ""}</td><td>{row.modelVersion}</td><td>{row.predictedAt || ""}</td><td>{row.status}</td><td>{row.details}</td></tr>)}</tbody></table></div><p className="teacher-note">Save the downloaded file as <code>reports/Grade_1_Binary_Prototype_Predictions.xlsx</code> to update the repository report.</p></section>}
    {preview && <section className="worksheet-score-preview" aria-label="Feature snapshot preview">
      <h4>Preview Student Data</h4>
      <p>{preview.valid} valid · {preview.invalid} invalid · {preview.blank} blank · {preview.existing} already saved · {preview.sampleOnly || 0} synthetic sample rows</p>
      {!!preview.sampleOnly && <p className="teacher-note" role="status">This is a synthetic demo workbook. Its values are shown for preview only and cannot be imported into student records. Download a fresh template to use your active roster.</p>}
      {rows.length ? <div className="worksheet-results-table-wrap"><table className="worksheet-results-table">
        <thead><tr><th scope="col">Excel row</th><th scope="col">Student ID</th><th scope="col">Student Name</th><th scope="col">Grade</th>{FEATURES.map(feature => <th scope="col" key={feature}>{feature}</th>)}<th scope="col">Preview status</th><th scope="col">Details</th></tr></thead>
        <tbody>{rows.map(row => <tr key={`${row.row}-${row.studentId}`}>
          <td>{row.row}</td><td>{row.studentId}</td><td>{row.studentName}</td><td>{row.grade || ""}</td>
          {FEATURES.map(feature => <td key={feature}>{row.features?.[feature] ?? ""}</td>)}
          <td>{row.status}</td><td>{row.message}</td>
        </tr>)}</tbody>
      </table></div> : <p role="status">No student rows were found in this workbook. Download a fresh experimental model template and try again.</p>}
    </section>}
  </section>;
}
