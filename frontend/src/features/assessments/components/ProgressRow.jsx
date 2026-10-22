import React from "react";

function ProgressRow({ area }) {
  const tone = area.averagePercentage >= 75 ? "green" : area.averagePercentage < 60 ? "orange" : "purple";
  return (
    <div className="progress-row">
      <div className="progress-label"><strong>{area.domain}</strong><span className={`level-tag ${tone}`}>{area.level}</span></div>
      <div className="progress-track"><span className={`progress-fill ${tone}`} style={{ width: `${Math.min(100, area.averagePercentage)}%` }} /></div>
      <div className="progress-meta"><span>{area.progress} <i>·</i> {area.assessmentCount} {area.assessmentCount === 1 ? "record" : "records"}</span><strong>{area.averagePercentage.toFixed(0)}%</strong></div>
    </div>
  );
}

export default ProgressRow;
