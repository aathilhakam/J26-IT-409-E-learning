import React, { useRef, useState } from "react";
import { Button, Card, Field } from "../../../components";
import { api } from "../../../services/api";
import { MATERIAL_TYPES } from "../materials";

export default function GradeOneBookImport({ onImported }) {
  const [books, setBooks] = useState(null);
  const [results, setResults] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const lock = useRef(false);
  async function open() {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError(""); setResults(null);
    try { setBooks(await api.gradeOneBooks()); }
    catch (cause) { setError(cause.message || "Could not inspect books. Try again."); }
    finally { lock.current = false; setBusy(false); }
  }
  function edit(index, field, value) {
    setBooks(current => current.map((row, i) => i === index ? { ...row, book: { ...row.book, [field]: value } } : row));
  }
  async function save(event) {
    event.preventDefault();
    if (lock.current) return;
    lock.current = true; setBusy(true); setError("");
    try {
      setResults(await api.importGradeOneBooks(books.map(row => row.book)));
      setBooks(null);
      try { await onImported?.(); }
      catch { setError("Import finished, but the library could not refresh. Reload the page to see saved books."); }
    } catch (cause) { setError(cause.message || "Import failed. Retry safely; existing imports will be skipped."); }
    finally { lock.current = false; setBusy(false); }
  }
  return <section aria-label="Grade 1 book import">
    <Button variant="secondary" onClick={open} disabled={busy}>Import Grade 1 Books</Button>
    {busy && <p role="status">{books ? "Importing Grade 1 books…" : "Checking Grade 1 books…"}</p>}
    {error && <p role="alert">{error}</p>}
    {books && <Card><form onSubmit={save}>
      <h2>Review Grade 1 books</h2><p>All books save to My private library in Grade 1. No student assignments are created. Edit metadata before importing. Unavailable books will be included in the result summary.</p>
      <fieldset disabled={busy} className="material-form-fields">
        {books.map((row, index) => <div key={row.book.filename}>
          <h3>{row.book.filename}</h3><p>{row.status}: {row.message}</p>
          <Field label={`Title for ${row.book.filename}`}><input value={row.book.title} maxLength={120} required onChange={event => edit(index, "title", event.target.value)} /></Field>
          <Field label={`Description for ${row.book.filename}`}><textarea value={row.book.description} maxLength={1000} onChange={event => edit(index, "description", event.target.value)} /></Field>
          <Field label={`Subject for ${row.book.filename}`} hint="Optional"><input value={row.book.subject} maxLength={100} onChange={event => edit(index, "subject", event.target.value)} /></Field>
          <Field label={`Material type for ${row.book.filename}`}><select value={row.book.materialType} onChange={event => edit(index, "materialType", event.target.value)}>{MATERIAL_TYPES.map(type => <option key={type}>{type}</option>)}</select></Field>
        </div>)}
        <div className="modal-actions"><Button type="button" variant="quiet" onClick={() => setBooks(null)}>Cancel</Button><Button type="submit">Import reviewed books</Button></div>
      </fieldset>
    </form></Card>}
    {results && <Card><h2>Grade 1 import summary</h2><p role="status">{["IMPORTED", "SKIPPED", "MISSING", "FAILED"].map(status => `${results.filter(row => row.status === status).length} ${status.toLowerCase()}`).join(" · ")}</p><ul>{results.map(row => <li key={row.filename}><strong>{row.filename}</strong> — {row.status}: {row.message}</li>)}</ul></Card>}
  </section>;
}
