import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../services/api";

export async function loadTeacherDashboard(studentId, grade, includePathways = false) {
  const [statistics, queued, reviews, history, pathways] = await Promise.all([
    api.dashboardStatistics(studentId), api.classroomLessons(grade), api.pendingReviews(), api.completedLessons(grade), includePathways ? api.teacherPathways() : Promise.resolve([]),
  ]);
  const completedIds = new Set(history.map(lesson => lesson.id));
  const lessons = queued.filter(lesson => !completedIds.has(lesson.id));
  if (statistics.completedLessons == null || statistics.remainingLessons == null) {
    return { statistics: null, lessons, reviews, history, pathways, statisticsError: "Dashboard totals need the updated backend. Restart the backend, then retry statistics." };
  }
  return { statistics, lessons, reviews, history, pathways };
}
export default function useTeacherDashboard({ enabled, studentId, sessionUser, contentItems, students, recommendations }) {
  const [grade, setGrade] = useState("");
  const [state, setState] = useState({ loading: true, lessons: [], reviews: [] });
  const generation = useRef(0);
  const refresh = useCallback(async (silent = false) => {
    const current = ++generation.current;
    if (!enabled) { setState({ loading: false, lessons: [], reviews: [] }); return; }
    if (!silent) setState(current => ({ ...current, loading: true }));
    try {
      const result = await loadTeacherDashboard(studentId, grade, students.some(student => student.active !== false));
      if (current === generation.current) setState({ ...result, loading: false });
    } catch (error) {
      if (current === generation.current) setState({ loading: false, error: error.message || "Could not load classroom information.", lessons: [], reviews: [] });
    }
  }, [enabled, studentId, sessionUser?.id, grade, students]);
  useEffect(() => { void refresh(); return () => { generation.current++; }; }, [refresh, contentItems, students, recommendations]);
  useEffect(() => {
    if (!enabled) return;
    const onFocus = () => { if (document.visibilityState !== "hidden") void refresh(true); };
    const interval = window.setInterval(onFocus, 15000);
    window.addEventListener("focus", onFocus);
    window.addEventListener("smartplay-evidence-updated", onFocus);
    return () => { window.clearInterval(interval); window.removeEventListener("focus", onFocus); window.removeEventListener("smartplay-evidence-updated", onFocus); };
  }, [enabled, refresh]);
  return { ...state, refresh: () => refresh(), grade, setGrade, grades: [...new Set(students.filter(s => s.active !== false).map(s => s.grade).filter(Boolean))].sort() };
}
