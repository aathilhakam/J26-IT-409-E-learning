import React, { useEffect, useRef, useState } from "react";
import { Button, Card, Field } from "../../components";
import { api } from "../../services/api";
import PrototypeFeatureSnapshotImport from "./PrototypeFeatureSnapshotImport";

export const ACTIVITY_HEADERS = ["Student ID", "Student Name", "Activity Name", "Score", "Maximum Score", "Attempts", "Time Taken (Minutes)", "Completion Status"];

export default function ClassroomActivityImport({ materials = [] }) {
  const [students, setStudents] = useState([]);
  const [grade, setGrade] = useState("Grade 1");
  const [activityName, setActivityName] = useState("");
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [token, setToken] = useState("");
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const input = useRef(null);

  useEffect(() => {
    let active = true;
    api.students().then(rows => {
      if (!active) return;
      const activeStudents = rows.filter(student => student.active !== false);
      setStudents(activeStudents);
      const grades = [...new Set(activeStudents.map(student => student.grade).filter(Boolean))];
      setGrade(grades.find(value => value.toLocaleLowerCase() === "grade 1") || grades[0] || "");
    }).catch(reason => { if (active) setError(reason.message); });
    return () => { active = false; };
  }, []);

  const grades = [...new Set(students.map(student => student.grade).filter(Boolean))].sort();
  const selectedRows = result?.rows || preview?.rows || [];

  function reset(message = "") {
    setPreview(null);
    setToken("");
    setResult(null);
    setError("");
    setNotice(message);
  }

  async function run(action) {
    setBusy(true);
    setError("");
    setNotice("");
    try { await action(); }
    catch (reason) { setError(reason.message || "Classroom activity import failed."); }
    finally { setBusy(false); }
  }

  function formData() {
    const form = new FormData();
    form.append("file", file);
    return form;
  }

  async function download() {
    await run(async () => {
      const blob = await api.downloadClassroomActivityTemplate(grade, activityName);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "SmartPlay_Simple_Assessment_Template.xlsx";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }

  async function previewFile() {
    if (!file) { setError("Choose a completed classroom activity template first."); return; }
    await run(async () => {
      const data = await api.previewClassroomActivities(formData());
      setPreview(data);
      setToken(data.token || "");
      setResult(null);
      if (!data.rows?.length) setNotice("No activity rows were found in this workbook.");
    });
  }

  async function importFile() {
    if (!token || !preview?.valid) { setError("Preview at least one valid row before importing."); return; }
    await run(async () => {
      const data = await api.importClassroomActivities(formData(), token);
      setResult(data);
      setToken("");
      setNotice(`${data.imported} activities saved · ${data.existing} already recorded · ${data.blank} blank rows · ${data.invalid} rows failed.`);
    });
  }

  return <Card as="section" className="worksheet-scores classroom-activity-import">
    <span className="dashboard-section-kicker">CLASSROOM ACTIVITY</span>
    <h2>Classroom Activity Records</h2>
    <p>Download the SmartPlay assessment workbook, enter completed classroom activity data, preview it, then confirm the import. Records are saved as learning activity evidence.</p>
    <div className="worksheet-score-fields">
      <Field label="Grade / class"><select value={grade} disabled={busy} onChange={event => { setGrade(event.target.value); reset(); }}>
        {grades.map(value => <option key={value} value={value}>{value}</option>)}
      </select></Field>
      <Field label="Prefill activity / material (optional)"><select value={activityName} disabled={busy} onChange={event => { setActivityName(event.target.value); reset(); }}>
        <option value="">Enter activity name in Excel</option>
        {materials.map(material => <option key={material.id} value={material.title || material.originalFileName}>{material.title || material.originalFileName}</option>)}
      </select></Field>
      <div className="teacher-note">Student ID and name are prefilled. Choose a material to prefill Activity Name, or enter it in Excel.</div>
    </div>
    <div className="modal-actions">
      <Button variant="secondary" disabled={busy || !grade} onClick={download}>Download Assessment Template</Button>
      <label className="worksheet-file">Upload Completed Assessment Template<input ref={input} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" disabled={busy} onChange={event => { const selected = event.target.files?.[0] || null; setFile(selected); reset(); event.target.value = ""; }} /></label>
      <Button variant="secondary" disabled={busy || !file} onClick={previewFile}>Preview Activities</Button>
      <Button disabled={busy || !token || !preview?.valid} onClick={importFile}>{busy ? "Working…" : "Import Activities"}</Button>
    </div>
    <p className="teacher-note">Attempts and time may be zero, but must be entered. Score must be between 0 and Maximum Score. Completion Status must be COMPLETED or IN_PROGRESS. Blank rows are skipped; invalid rows are never saved.</p>
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {preview && <section className="worksheet-score-preview" aria-label="Classroom activity preview">
      <h3>Preview Classroom Activities</h3>
      <p>{preview.valid} valid · {preview.invalid} invalid · {preview.blank} blank · {preview.existing} already recorded</p>
      {selectedRows.length ? <div className="worksheet-results-table-wrap"><table className="worksheet-results-table">
        <thead><tr>{ACTIVITY_HEADERS.map(header => <th scope="col" key={header}>{header}</th>)}<th scope="col">Status</th><th scope="col">Details</th></tr></thead>
        <tbody>{selectedRows.map(row => <tr key={`${row.row}-${row.studentId}-${row.activityName}`}>
          <td>{row.studentId}</td><td>{row.studentName}</td><td>{row.activityName}</td><td>{row.score ?? ""}</td><td>{row.maximumScore ?? ""}</td><td>{row.attempts ?? ""}</td><td>{row.timeTakenMinutes ?? ""}</td><td>{row.completionStatus}</td><td>{row.status}</td><td>{row.message}</td>
        </tr>)}</tbody>
      </table></div> : <p>No activity rows were found in this file.</p>}
      {selectedRows.some(row => ["INVALID", "CONFLICT", "FAILED"].includes(row.status)) && <ul className="teacher-note" aria-label="Activity rows with errors">
        {selectedRows.filter(row => ["INVALID", "CONFLICT", "FAILED"].includes(row.status)).map(row => <li key={`${row.row}-${row.studentId}-error`}>Excel row {row.row}: {row.message}</li>)}
      </ul>}
    </section>}
    <PrototypeFeatureSnapshotImport grade={grade} materials={materials} />
  </Card>;
}
