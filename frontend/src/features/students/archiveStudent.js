// Apply roster changes only after the server confirms the archive.
export async function archiveStudent({ api, studentId, confirmation, setStudents, setActiveId, setSuccessNotice, loadStudents }) {
  await api.archiveStudent(studentId, confirmation);
  setStudents((current) => current.filter((student) => student.id !== studentId));
  setActiveId((current) => current === studentId ? "" : current);
  setSuccessNotice("Student removed successfully.");
  await loadStudents();
}

export function activeStudents(students) {
  return students.filter((student) => student.active !== false && student.isActive !== false && student.archived !== true);
}
