import React from "react";
import { Field } from "../../components";
import { OBSERVATION_FIELDS } from "../evidence/evidence";

const textFields = [["improvement", "Improvement observed", 2000], ["feedback", "Feedback / next step", 2000]];
export default function FeedbackFields({ values, onChange, prefix, shared = false }) {
  return <div className="lesson-feedback-fields">
    <div className="lesson-observation-grid">
      {OBSERVATION_FIELDS.filter(([key]) => key !== "learningDifficulty").map(([key, label, options]) => <Field key={key} label={`${label}${shared ? "" : " *"}`}><select aria-label={`${prefix}: ${label}`} value={values[key] || ""} onChange={event => onChange(key, event.target.value)}><option value="">{shared ? "Leave unchanged" : "Choose an observation"}</option>{options.map(option => <option key={option}>{option}</option>)}</select></Field>)}
    </div>
    <div className="lesson-notes-grid">{textFields.map(([key, label, max]) => <Field key={key} label={label} hint={`Optional · up to ${max} characters`}><textarea aria-label={`${prefix}: ${label}`} rows={2} maxLength={max} value={values[key] || ""} onChange={event => onChange(key, event.target.value)} /></Field>)}</div>
  </div>;
}
