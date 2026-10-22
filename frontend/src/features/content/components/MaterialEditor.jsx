import { api } from "../../../services/api";
import React, { useEffect, useId, useState } from "react";
import { Button } from "../../../components";
import LearningMaterialForm from "./LearningMaterialForm";

export default function MaterialEditor({ material, defaultGrade, onSave, onClose }) {
  const id = useId();
  const [busy, setBusy] = useState(false);
  const [students, setStudents] = useState([]);
  const [rosterError, setRosterError] = useState("");
  useEffect(() => {
    let active = true;
    api.students().then((data) => { if (active) setStudents(data); })
      .catch(() => { if (active) setRosterError("Could not load students. Close and reopen to retry, or save to your private library."); });
    return () => { active = false; };
  }, []);
  return (
    <div className="modal-backdrop material-editor-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose();
    }}>
      <section className="modal learning-entry-modal material-editor" role="dialog" aria-modal="true" aria-labelledby={id}>
        <Button variant="quiet" className="modal-close" onClick={onClose} disabled={busy} aria-label="Close">×</Button>
        <header className="material-editor-heading">
          <span className="material-editor-icon" aria-hidden="true">▤</span>
          <div>
            <span className="material-editor-eyebrow">YOUR TEACHING LIBRARY</span>
            <h2 id={id}>{material ? "Edit learning material" : "Add learning material"}</h2>
            <p>{material ? "Update the details of your classroom resource." : "Give your next lesson a resource worth keeping."}</p>
          </div>
        </header>
        <div className="material-editor-context"><span aria-hidden="true">●</span> Teacher-only <span>·</span> PDF & images <span>·</span> Up to 20 MB</div>
        {rosterError && <p role="alert" className="content-queue-error">{rosterError}</p>}
        <LearningMaterialForm students={students} initialMaterial={material} defaultGrade={defaultGrade} onSave={onSave} onCancel={onClose} onBusyChange={setBusy} />
      </section>
    </div>
  );
}
