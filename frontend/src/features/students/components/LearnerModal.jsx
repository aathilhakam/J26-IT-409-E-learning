import React from "react";
import { Button, Field } from "../../../components";

function LearnerModal({ onClose, onSubmit, saving }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <form className="modal" onSubmit={onSubmit} aria-label="Add learner">
        <Button variant="quiet" className="modal-close" type="button" onClick={onClose} aria-label="Close">×</Button>
        <div className="eyebrow">YOUR CLASSROOM</div>
        <h2>Add a learner</h2>
        <p>Create a learner profile to start tracking classroom progress.</p>
        <Field label="Learner name">
          <input autoFocus name="name" placeholder="e.g. Amaya Silva" required />
        </Field>
        <Field label="Grade">
          <input name="grade" defaultValue="Grade 1" required />
        </Field>
        <div className="modal-actions">
          <Button variant="quiet" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={saving}>{saving ? "Adding…" : "Add learner"}</Button>
        </div>
      </form>
    </div>
  );
}

export default LearnerModal;
