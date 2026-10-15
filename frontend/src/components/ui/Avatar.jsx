import React from "react";

export function Avatar({ label, variant = 0, className = "", ...props }) {
  const colorVariant = Math.abs(Number(variant) || 0) % 5;
  return (
    <span
      className={`ui-avatar avatar avatar-${colorVariant} ${className}`.trim()}
      aria-label={props["aria-label"] ?? label}
      {...props}
    >
      {label}
    </span>
  );
}
