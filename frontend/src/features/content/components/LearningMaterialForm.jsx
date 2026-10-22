import React, { useId, useRef, useState } from "react";
import { Button, Field } from "../../../components";
import { FILE_ACCEPT, MATERIAL_TYPES, formatContentSize, materialMetadata, materialError } from "../materials";

export default function LearningMaterialForm({ students = [], initialMaterial, defaultGrade = "Grade 1", onSave, onCancel, onBusyChange }) {
  const editing = Boolean(initialMaterial);
  const id = useId();
  const formRef = useRef(null);
  const submitting = useRef(false);
  const requestKey = useRef(null);
  const lastSubmission = useRef(null);
  const [values, setValues] = useState(() => ({
    description: initialMaterial?.description || "",
    subject: initialMaterial?.subject || "", grade: initialMaterial?.grade || defaultGrade || "Grade 1",
    materialType: initialMaterial?.materialType || ({ Lesson: "Lesson Plan", Reading: "Reading Material", Activity: "Activity Sheet", Other: "Other" }[initialMaterial?.category] || "Worksheet"),
  }));
  const [assignmentMode, setAssignmentMode] = useState(initialMaterial?.assignmentMode === "ALL" ? "STUDENTS" : initialMaterial?.assignmentMode || "LIBRARY");
  const [studentIds, setStudentIds] = useState(initialMaterial?.assignedStudentIds || []);
  const [assignmentGrade, setAssignmentGrade] = useState(initialMaterial?.assignmentGrade || "");
  const grades = [...new Set(students.map((student) => student.grade))].sort();
  const [file, setFile] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const update = (field) => (event) => {
    if (!submitting.current) requestKey.current = null;
    setValues((current) => ({ ...current, [field]: event.target.value }));
    setError(""); setSuccess("");
  };

  async function submit(event) {
    event.preventDefault();
    if (submitting.current) return;
    const metadata = materialMetadata({ ...values, title: initialMaterial?.title || (file?.name || "").trim().slice(0, 120), tags: (initialMaterial?.tags || []).join(", ") });
    Object.assign(metadata, { assignmentMode, studentIds: assignmentMode === "STUDENTS" ? studentIds : [], assignmentGrade: assignmentMode === "GRADE" ? assignmentGrade : "" });
    const validation = materialError(metadata, file, editing)
      || (assignmentMode === "STUDENTS" && !studentIds.length ? "Select at least one student." : "")
      || (assignmentMode === "GRADE" && !assignmentGrade ? "Choose an assignment grade." : "");
    setSuccess("");
    if (validation) { setError(validation); return; }
    submitting.current = true;
    const signature = JSON.stringify(metadata);
    if (lastSubmission.current?.signature !== signature || lastSubmission.current?.file !== file) requestKey.current = null;
    requestKey.current ??= crypto.randomUUID();
    lastSubmission.current = { signature, file };
    setError(""); setSaving(true); onBusyChange?.(true);
    try {
      await onSave(metadata, file, requestKey.current);
      requestKey.current = null;
      setSuccess(editing ? "Material updated successfully." : "Material saved successfully.");
      if (!editing) {
        setValues({ description: "", subject: "", grade: defaultGrade || "Grade 1", materialType: "Worksheet" });
        setAssignmentMode("LIBRARY"); setStudentIds([]); setAssignmentGrade("");
        setFile(null);
        formRef.current?.reset();
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not save the material. Please try again.");
    } finally {
      submitting.current = false;
      setSaving(false); onBusyChange?.(false);
    }
  }

  return (
    <form ref={formRef} onSubmit={submit} aria-label={editing ? "Edit learning material" : "Add learning material"}>
      <fieldset disabled={saving} className="material-form-fields">
        <Field label="Description" className="material-description-field" hint="Optional"><textarea name="description" rows={4} value={values.description} onChange={update("description")} maxLength={1000} /></Field>
        <div className="score-fields material-audience-fields">
          <Field label="Subject" hint="Optional"><input name="subject" value={values.subject} onChange={update("subject")} maxLength={100} /></Field>
          <Field label="Grade or age group"><input name="grade" list={`${id}-grades`} value={values.grade} onChange={update("grade")} maxLength={60} required />
            <datalist id={`${id}-grades`}><option>Preschool</option>{Array.from({ length: 13 }, (_, index) => <option key={index}>Grade {index + 1}</option>)}<option>Ages 3–5</option><option>Ages 6–8</option></datalist>
          </Field>
        </div>
        <Field label="Material type" className="material-type-field"><select name="materialType" value={values.materialType} onChange={update("materialType")} required>{MATERIAL_TYPES.map((type) => <option key={type}>{type}</option>)}</select></Field>
        <div className="material-assignment-fields">
          <Field label="Student assignment">
            <select value={assignmentMode} onChange={(event) => { setAssignmentMode(event.target.value); setError(""); }}>
              <option value="LIBRARY">My private library</option>
              <option value="STUDENTS">Selected students (one or multiple)</option>
              <option value="GRADE">All students in a grade</option>
            </select>
          </Field>
          {assignmentMode === "STUDENTS" && <div className="material-student-choices" role="group" aria-label="Select students">
            {students.map((student) => <label key={student.id}>
              <input type="checkbox" checked={studentIds.includes(student.id)} onChange={(event) => setStudentIds((current) => event.target.checked ? [...current, student.id] : current.filter((id) => id !== student.id))} />
              <span>{student.name} · {student.grade}</span>
            </label>)}
            {!students.length && <p>No active students available.</p>}
          </div>}
          {assignmentMode === "GRADE" && <Field label="Assign to grade"><select value={assignmentGrade} onChange={(event) => setAssignmentGrade(event.target.value)} required>
            <option value="">Choose a grade</option>{grades.map((grade) => <option key={grade}>{grade}</option>)}
          </select></Field>}
          {assignmentMode !== "LIBRARY" && <small>Assigned to current students when saved. New students can be added by editing this material.</small>}
        </div>
        {editing ? <p>{initialMaterial.originalFileName} · {formatContentSize(initialMaterial.size)}</p> : (
          <Field label="Upload your file" className="material-upload-field" hint="PDF, JPG, PNG, WEBP · up to 20 MB">
            <input name="file" type="file" accept={FILE_ACCEPT} required onChange={(event) => { setFile(event.target.files?.[0] || null); setError(""); setSuccess(""); }} />
          </Field>
        )}
        {file && <p className="material-file-details">{file.name} · {formatContentSize(file.size)}</p>}
      </fieldset>
      {saving && <div role="status"><progress aria-label={editing ? "Saving material" : "Uploading material"} /><p>{editing ? "Saving changes…" : "Uploading material…"}</p></div>}
      {error && <p className="content-queue-error" role="alert">{error}</p>}
      {success && <p className="material-success" role="status">{success}</p>}
      <div className="modal-actions">
        <Button variant="quiet" type="button" onClick={onCancel} disabled={saving}>Cancel</Button>
        <Button type="submit" disabled={saving}>{saving ? "Saving…" : editing ? "Save changes" : "Save Material"}</Button>
      </div>
    </form>
  );
}
