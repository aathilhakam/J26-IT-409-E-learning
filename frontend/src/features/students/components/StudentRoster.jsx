import React from "react";
import { Avatar } from "../../../components";
import { initials } from "../../../utils/format";

export default function StudentRoster({ students, setSelectedStudentId, onSelectStudent }) {
  const sortedStudents = [...students].sort((first, second) =>
    String(first.name || "").localeCompare(String(second.name || ""), undefined, { sensitivity: "base" })
  );
  return (
        <div className="student-roster-grid">
          {sortedStudents.map((student, index) => {
            const joinedDate = student.createdAt
              ? new Intl.DateTimeFormat("en", { month: "short", year: "numeric" }).format(new Date(student.createdAt))
              : "";
            return (
              <button
                className="student-roster-card"
                key={student.id}
                type="button"
                onClick={() => {
                  setSelectedStudentId(student.id);
                  onSelectStudent(student.id);
                }}
                aria-label={`View ${student.name}'s profile`}
              >
                <Avatar label={initials(student.name)} variant={index} className="student-roster-avatar" />
                <span className="student-roster-card-copy">
                  <strong>{student.name}</strong>
                  <span>{student.grade}</span>
                  {joinedDate && <small>Added {joinedDate}</small>}
                </span>
                <span className="student-roster-card-arrow" aria-hidden="true">→</span>
              </button>
            );
          })}
        </div>
  );
}
