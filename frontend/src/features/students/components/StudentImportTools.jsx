import React, { useRef } from "react";
import { Button } from "../../../components";

export default function StudentImportTools({ onImportExcel, onDownloadTemplate, onExportStudentDetails, importingStudents, onAddStudent, studentCount = 0 }) {
  const excelInputRef = useRef(null);
  return (
    <div style={{ display: "flex", gap: "0.65rem", flexWrap: "wrap", alignItems: "center" }}>
      <input
        type="file"
        accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ref={excelInputRef}
        className="sr-only"
        aria-label="Choose student Excel workbook"
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = "";
          if (file) void onImportExcel(file);
        }}
      />
      <Button variant="secondary" type="button" onClick={() => void onDownloadTemplate()}>
        ↓ Download template
      </Button>
      <Button variant="secondary" type="button" onClick={() => void onExportStudentDetails()} disabled={!studentCount}>
        ↓ Export current student details
      </Button>
      <Button variant="secondary" type="button" onClick={() => excelInputRef.current?.click()} disabled={importingStudents}>
        {importingStudents ? "Importing…" : "↑ Import Excel"}
      </Button>
      <Button type="button" onClick={onAddStudent}>＋ Add student</Button>
    </div>
  );
}
