import React from "react";
import { Card } from "../../../components";

function SummaryCard({ label, value, icon, tone, foot, href, onNavigate }) {
  return (
    <Card as="article" className="summary-card">
      <div className="summary-top"><span>{href ? <a href={href} onClick={event => { if (onNavigate && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) { event.preventDefault(); onNavigate(); } }}>{label}</a> : label}</span><span className={`summary-icon ${tone}`}>{icon}</span></div>
      <strong className="summary-value">{value}</strong>
      <span className="summary-foot">{foot}</span>
    </Card>
  );
}

export default SummaryCard;
