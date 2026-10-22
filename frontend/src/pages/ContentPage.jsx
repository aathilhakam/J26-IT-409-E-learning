import WorksheetScores from "../features/worksheets/WorksheetScores";
import GradeOneBookImport from "../features/content/components/GradeOneBookImport";
import MaterialEditor from "../features/content/components/MaterialEditor";
import { filterMaterials, formatContentSize } from "../features/content/materials";
import React, { useState } from "react";
import { Badge, Button, Card } from "../components";
import { displayDate } from "../utils/format";

function ContentManagementPage({ items, busy, onDownload, onDelete, onSaveMaterial, onUpdate, onImported }) {
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("All categories");
  const [subject, setSubject] = useState("");
  const [grade, setGrade] = useState("");
  const [materialType, setMaterialType] = useState("");
  const [editor, setEditor] = useState(null);
  const visibleItems = filterMaterials(items, { search, subject, grade, materialType, category: category === "All categories" ? "" : category });

  return (
    <div className="content-management-page">
      <div className="content-breadcrumb"><span>Curriculum</span><i>›</i><strong>Teacher Materials Repository</strong></div>
      <header className="content-management-heading">
        <div>
          <h1>Upload &amp; Content Management</h1>
          <p>Upload and organize curriculum resources for your classroom.</p>
          <Button onClick={() => setEditor({ material: null })}>＋ Add learning material</Button>
        </div>
        <span className="content-engine-status"><span /> Secure storage ready</span>
      </header>

      <GradeOneBookImport onImported={onImported} />

      <Card as="section" className="content-library-card">
        <details>
          <summary className="content-library-summary"><div className="content-library-heading">
            <div><span className="dashboard-section-kicker">YOUR PRIVATE LIBRARY</span><h2>Teacher Materials Repository</h2><p>Browse, download, and remove your uploaded classroom resources.</p></div>
            <span className="content-library-count">{items.length}</span>
          </div></summary>
          <div className="content-library-content">
          <div className="content-library-filters">
            <label className="content-search">
              <span aria-hidden="true">⌕</span>
              <span className="sr-only">Search resources</span>
              <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search your resources" />
            </label>
            <label className="sr-only" htmlFor="content-category-filter">Filter by category</label>
            <select id="content-category-filter" value={category} onChange={(event) => setCategory(event.target.value)}>
              <option>All categories</option><option>Lesson</option><option>Worksheet</option><option>Reading</option><option>Activity</option><option>Other</option>
            </select>
          </div>
          <div className="content-library-filters material-library-filters">
            {[
              ["Subject", "subject", subject, setSubject],
              ["Grade or age group", "grade", grade, setGrade],
              ["Material type", "materialType", materialType, setMaterialType],
            ].map(([label, key, value, update]) => (
              <label key={key}>{label}
                <select value={value} onChange={(event) => update(event.target.value)}>
                  <option value="">All {label.toLowerCase()}</option>
                  {[...new Set(items.map((item) => item[key]).filter(Boolean))].sort().map((option) => <option key={option}>{option}</option>)}
                </select>
              </label>
            ))}
          </div>
          {busy ? (
            <div className="content-library-empty"><span>…</span><strong>Loading resources</strong><p>Getting your uploaded classroom files.</p></div>
          ) : visibleItems.length ? (
            <div className="content-library-list">
              {visibleItems.map((item) => (
                <article className="content-library-item" key={item.id}>
                  <span className={`content-file-icon ${item.contentType.startsWith("image/") ? "image" : "document"}`} aria-hidden="true">
                    {item.contentType.startsWith("image/") ? "▧" : "▤"}
                  </span>
                  <div className="content-library-item-info">
                    <div className="content-library-item-title"><strong>{item.title}</strong><Badge tone="neutral">{item.category}</Badge></div>
                    <p>{item.description || item.originalFileName}</p>
                    <p>{!item.assignmentMode || item.assignmentMode === "LIBRARY" ? "Private library" : `${item.assignedStudentIds?.length || 0} students assigned${item.assignmentMode === "GRADE" ? ` · ${item.assignmentGrade}` : ""}`}</p>
                    <p>{[item.subject, item.grade, item.materialType].filter(Boolean).join(" · ")}</p>
                    {item.tags?.length > 0 && <p>{item.tags.join(", ")}</p>}
                    <small>{item.originalFileName} · {formatContentSize(item.size)} · {item.contentType} · Teacher-only <i>·</i> Added {displayDate(item.uploadedAt)}</small>
                  </div>
                  <div className="content-library-item-actions">
                    <button type="button" onClick={() => setEditor({ material: item })} aria-label={`Edit ${item.title}`}>✎</button>
                    <button type="button" onClick={() => void onDownload(item)} aria-label={`Download ${item.title}`}>↓</button>
                    <button type="button" onClick={() => void onDelete(item)} aria-label={`Delete ${item.title}`}>×</button>
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <div className="content-library-empty">
              <span aria-hidden="true">{items.length ? "⌕" : "▤"}</span>
              <strong>{items.length ? "No matching resources" : "Your content library is empty"}</strong>
              <p>{items.length ? "Try another search term or category." : "Add a learning material to build your classroom library."}</p>
            </div>
          )}
          </div>
        </details>
      </Card>
      <WorksheetScores materials={items} />
      {editor && <MaterialEditor material={editor.material} onClose={() => setEditor(null)}
        onSave={(metadata, file) => editor.material ? onUpdate(editor.material, metadata) : onSaveMaterial(metadata, file)} />}
    </div>
  );
}

export default ContentManagementPage;
