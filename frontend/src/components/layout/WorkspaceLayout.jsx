import React from "react";
import { Avatar } from "../ui";

const defaultNavigationItems = [
  { label: "Dashboard", href: "/dashboard", icon: "▦" },
  { label: "Student Profile", href: "/dashboard/profile", icon: "▤" },
  { label: "Upload & Content", href: "/dashboard/content", icon: "↥" },
  { label: "Weekly Review", href: "/dashboard#pathways", icon: "✓" },
];

export function WorkspaceLayout({
  children,
  sidebarContent,
  navigationItems = defaultNavigationItems,
  activePage = "Dashboard",
  onNavigate,
  teacherInitial = "T",
  onLogout,
}) {
  const today = new Intl.DateTimeFormat("en", {
    weekday: "long",
    month: "long",
    day: "numeric",
  }).format(new Date());

  return (
    <div className="app-shell">
      <header className="dashboard-header">
        <a className="brand" href="/" aria-label="SmartPlay home">
          <span className="brand-mark">S</span>
          <span>smartplay<span className="brand-dot">.</span></span>
        </a>
        <div className="dashboard-brand-caption">Learning Bridge · Teacher Portal</div>
        <nav className="dashboard-nav" aria-label="Main navigation">
          {navigationItems.map((item) => (
            <a
              className={`dashboard-nav-item ${activePage === item.label ? "active" : ""}`}
              href={item.href}
              key={item.label}
              onClick={(event) => {
                if (!onNavigate || !["Dashboard", "Student Profile", "Upload & Content", "Weekly Review"].includes(item.label)) return;
                event.preventDefault();
                onNavigate(item.label);
              }}
            >
              <span aria-hidden="true">{item.icon}</span> {item.label}
            </a>
          ))}
        </nav>
        <div className="dashboard-account">
          <span className="today-label"><span className="calendar-mark">▦</span>{today}</span>
          <span className="workspace-status"><span /> Workspace ready</span>
          {onLogout && <button className="logout-button" type="button" onClick={onLogout}>Log out <span aria-hidden="true">↗</span></button>}
          <Avatar label={teacherInitial} className="top-avatar" />
        </div>
      </header>

      {sidebarContent && <section className="roster-bar" aria-label="Class roster">{sidebarContent}</section>}

      <main className="main-content" id="overview">
        <div className="page-content">{children}</div>
      </main>
    </div>
  );
}
