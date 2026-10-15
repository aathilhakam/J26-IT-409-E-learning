import React from "react";

export function SectionHeading({ title, description, action, className = "", ...props }) {
  return (
    <div className={`section-heading ${className}`.trim()} {...props}>
      <div>
        <h2>{title}</h2>
        {description && <p>{description}</p>}
      </div>
      {action}
    </div>
  );
}
