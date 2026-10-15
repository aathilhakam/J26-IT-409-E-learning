import React from "react";

const variants = new Set(["primary", "secondary", "quiet", "reject"]);
const sizes = new Set(["small"]);

export function Button({
  variant = "primary",
  size = "default",
  className = "",
  type = "button",
  children,
  ...props
}) {
  const selectedVariant = variants.has(variant) ? variant : "primary";
  const selectedSize = sizes.has(size) ? "button-small" : "";
  const classes = ["button", `button-${selectedVariant}`, selectedSize, className]
    .filter(Boolean)
    .join(" ");

  return <button className={classes} type={type} {...props}>{children}</button>;
}
