import React from "react";

export function EmptyState({ icon, title, description, action, className = "", ...props }) {
  return (
    <div className={`ui-empty-state ${className}`.trim()} {...props}>
      {icon && <span className="ui-empty-state__icon" aria-hidden="true">{icon}</span>}
      <div>
        {title && <strong>{title}</strong>}
        {description && <p>{description}</p>}
      </div>
      {action}
    </div>
  );
}
