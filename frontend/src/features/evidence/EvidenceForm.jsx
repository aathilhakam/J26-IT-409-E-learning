import React, { useRef, useState } from "react";
import { Button, Field } from "../../components";
import { OBSERVATION_FIELDS, MEASURES, STATUSES, COMPONENTS, createEvidenceSubmission } from "./evidence";

export default function EvidenceForm({ kind, student, onSave, onSaved }) {
  const [state, setState] = useState({});
  const submitter = useRef(null);
  submitter.current ??= createEvidenceSubmission();
  const titles = { observation: "Teacher observation", history: "Historical lesson", assessment: "Assessment result" };
  const select = (name, label, choices) => <Field key={name} label={label}><select name={name} required defaultValue=""><option value="">Choose an option</option>{choices.map(value => <option key={value} value={value}>{value.replaceAll("_", " ")}</option>)}</select></Field>;
  const input = (name, label, props = {}) => <Field label={label}><input name={name} {...props} /></Field>;
  return <form className="evidence-form" aria-label={titles[kind]} aria-busy={Boolean(state.busy)} onSubmit={async event => {
    event.preventDefault(); const form = event.currentTarget;
    const values = Object.fromEntries(new FormData(form));
    await submitter.current({ kind, values, student, save: onSave, onState: next => { setState(next); if (next.success) form.reset(); }, onSaved });
  }}>
    <h3>{titles[kind]}</h3><p>For {student.name}</p>
    <fieldset disabled={state.busy || student.active === false}>
      <div className="evidence-fields">
        {kind === "observation" && <>{OBSERVATION_FIELDS.map(([name, label, choices]) => select(name, label, choices))}
          <Field label="Demonstrated skills" hint="Optional; one confirmed skill per line"><textarea name="demonstratedSkills" rows={3} /></Field>
          {input("observedAt", "Observation date/time (optional)", { type: "datetime-local" })}</>}
        {kind === "history" && <>{input("lessonId", "Lesson identifier", { required: true, maxLength: 180 })}
          {input("sequenceNumber", "Sequence number", { type: "number", min: 1, max: 2147483647, step: 1, required: true })}
          {select("completionStatus", "Completion status", STATUSES)}{input("occurredAt", "Occurrence date/time (optional)", { type: "datetime-local" })}</>}
        {kind === "assessment" && <>{select("sourceComponent", "Source component", COMPONENTS)}
          {input("assessmentId", "Assessment identifier", { required: true })}{input("assessmentType", "Assessment type (optional)")}
          {input("score", "Score", { type: "number", min: 0, step: "any", required: true })}{input("maxScore", "Maximum score", { type: "number", min: 0.1, step: "any", required: true })}
          {select("completionStatus", "Completion status", ["COMPLETED", "IN_PROGRESS", "NOT_COMPLETED"])}
          {input("attempts", "Attempts (optional)", { type: "number", min: 0, step: 1 })}{input("timeTakenMinutes", "Time taken in minutes (optional)", { type: "number", min: 0, step: "any" })}
          <Field label="Notes (optional)"><textarea name="notes" rows={2} /></Field>
          <p className="evidence-wide">Enter only measured values below (0–100). Leave unavailable measurements blank. Record pre-test and post-test together when both belong to this assessment.</p>
          {MEASURES.map(name => <Field key={name} label={name.replaceAll("_", " ")} hint="Optional · 0–100"><input name={name} type="number" min="0" max="100" step="any" /></Field>)}</>}
      </div>
      <Button type="submit" disabled={state.busy}>{state.busy ? "Saving…" : `Save ${titles[kind].toLowerCase()}`}</Button>
    </fieldset>
    {student.active === false && <p role="alert">This student is archived. Evidence cannot be added.</p>}
    {state.error && <p role="alert" className="alert">{state.error}</p>}
    {state.success && <p role="status">{state.success}</p>}
  </form>;
}
