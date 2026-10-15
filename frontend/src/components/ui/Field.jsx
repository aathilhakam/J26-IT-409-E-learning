import React from "react";

export function Field({ label, hint, className = "", children, ...props }) {
  return (
    <label className={`ui-field ${className}`.trim()} {...props}>
      <span className="ui-field__label">
        {label}
        {hint && <span className="ui-field__hint">{hint}</span>}
      </span>
      {children}
    </label>
  );
}
