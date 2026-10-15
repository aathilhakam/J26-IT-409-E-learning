import React from "react";

export function Card({ as: Element = "div", className = "", children, ...props }) {
  return <Element className={`ui-card ${className}`.trim()} {...props}>{children}</Element>;
}
