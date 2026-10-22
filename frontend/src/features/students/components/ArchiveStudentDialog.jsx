import React from "react";
import { Button, Field } from "../../../components";

export default function ArchiveStudentDialog({ activeStudent, archiveBusy, archiveFullName, archiveError, setArchiveFullName, setArchiveError, setShowArchiveForm, handleArchiveSubmit }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !archiveBusy) setShowArchiveForm(false);
    }}>
      <form className="modal" onSubmit={handleArchiveSubmit} aria-label="Remove student">
        <Button variant="quiet" className="modal-close" type="button" onClick={() => setShowArchiveForm(false)} disabled={archiveBusy} aria-label="Close">×</Button>
        <div className="eyebrow">REMOVE FROM CLASSROOM</div>
        <h2>Remove {activeStudent.name}?</h2>
        <p>This removes the student from your dashboard. Their learning records are retained.</p>
        <Field label="Student full name">
          <input value={archiveFullName} onChange={(event) => { setArchiveFullName(event.target.value); setArchiveError(""); }} placeholder="Enter the student's full name" autoComplete="off" required />
        </Field>
        {archiveError && <div className="alert" role="alert">{archiveError}</div>}
        <div className="modal-actions">
          <Button variant="quiet" type="button" onClick={() => setShowArchiveForm(false)} disabled={archiveBusy}>Cancel</Button>
          <Button variant="reject" type="submit" disabled={archiveBusy}>{archiveBusy ? "Removing…" : "Confirm removal"}</Button>
        </div>
      </form>
    </div>
  );
}
