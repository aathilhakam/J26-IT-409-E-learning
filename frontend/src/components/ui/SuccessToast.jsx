import React, { useEffect } from "react";
import { Button } from "./Button";

export default function SuccessToast({ message, onDismiss }) {
  useEffect(() => {
    if (!message) return;
    const timeout = window.setTimeout(onDismiss, 4000);
    return () => window.clearTimeout(timeout);
  }, [message, onDismiss]);

  if (!message) return null;

  return (
    <div className="success-toast" role="status" aria-live="polite">
      <span aria-hidden="true">✓</span>
      <span>{message}</span>
      <Button variant="quiet" type="button" onClick={onDismiss} aria-label="Dismiss success message">×</Button>
    </div>
  );
}
