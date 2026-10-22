import React from "react";

export default function LivePredictionState({ state }) {
  if (!state) return null;
  if (state.loading) return <div className="live-prediction" role="status">Generating a pathway from this student’s saved learning data…</div>;
  if (state.error) return <div className="live-prediction live-prediction-error" role="alert">
    <strong>{state.insufficient ? "Insufficient learning data" : "Could not generate a pathway"}</strong>
    <p>{state.message}</p>
  </div>;
  return (
    <div className="live-prediction" role="status">
      <strong>Random Forest prediction: {state.predictedOutcome}</strong>
      <div className="live-probabilities" aria-label="Class probabilities">
        {Object.entries(state.probabilities || {}).map(([label, value]) => <span key={label}>{label}: {(value * 100).toFixed(1)}% <small>class probability</small></span>)}
      </div>
      <p>{state.status === "No suitable validated activity" ? "No suitable validated activity matches the current evidence, difficulty, and confirmed prerequisites. Previous suggestions remain below for review." : "Suggestions are saved for your review. Approve, edit, or reject before assigning."}</p>
    </div>
  );
}
