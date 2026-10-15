import React from "react";

export function Badge({ tone = "neutral", className = "", children, ...props }) {
  return (
    <span className={`ui-badge ui-badge--${tone} ${className}`.trim()} {...props}>
      {children}
    </span>
  );
}
