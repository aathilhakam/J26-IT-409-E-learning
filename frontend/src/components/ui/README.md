# Shared React UI

This folder is the common frontend UI kit for the project. Team members can clone the repository, create a branch for their assigned component, and import these building blocks instead of defining another version of the same buttons, cards, or form fields.

The UI kit is plain React JavaScript. Shared visual tokens are in `../../styles/tokens.css` and are loaded by `src/main.jsx`.

## Components

Import from the shared entry point:

```jsx
import { Avatar, Badge, Button, Card, EmptyState, Field, SectionHeading } from "./components/ui";
```

Import the shared page shell and UI primitives together from `src/components`:

```jsx
import { Button, Card, SectionHeading, WorkspaceLayout } from "./components";
```

| Component | Props | Use |
| --- | --- | --- |
| `WorkspaceLayout` | `children`, optional `sidebarContent`, `navigationItems`, `activePage`, `teacherInitial` | Consistent navigation, teacher header, sidebar, and responsive page shell |
| `Button` | `variant`: `primary`, `secondary`, `quiet`, `reject`; `size`: `default`, `small`; standard button props | Actions and form submission |
| `Card` | `as`, `className`, standard element props | Shared card surface; use `as="article"` for semantic sections |
| `Badge` | `tone`: `neutral`, `pending`, `approved`, `rejected`; `className` | Status, category, and metadata labels |
| `Avatar` | `label`, `variant` (0–4), `className` | Initials or short labels for people |
| `Field` | `label`, optional `hint`, input children | Consistent, accessible label wrapper for form controls |
| `SectionHeading` | `title`, optional `description`, `action`, `className` | Consistent page and section headings |
| `EmptyState` | optional `icon`, `title`, `description`, `action` | Empty lists and first-use guidance |

## Example

```jsx
import { Button, Card, EmptyState, Field, SectionHeading } from "./components/ui";

export default function ComponentPage() {
  return (
    <WorkspaceLayout activePage="Overview">
      <SectionHeading
        title="Creativity activities"
        description="A short explanation of this part of the learning journey."
        action={<Button onClick={() => {}}>Add activity</Button>}
      />
      <Card className="panel">
        <Field label="Activity name" hint="Required">
          <input name="activityName" required />
        </Field>
      </Card>
      <EmptyState
        title="No activities yet"
        description="Add an activity to start building this component."
      />
    </WorkspaceLayout>
  );
}
```

To define navigation for a team component, pass `navigationItems` (an array of `{ label, href, icon }`) and a matching `activePage`. Use `sidebarContent` for component-specific sidebar items. Keep the common brand and workspace header intact so the team apps feel like one product.

## Team conventions

- Import primitives from `./components/ui`; add reusable primitives here and export them from `index.js`.
- Use the CSS custom properties in `src/styles/tokens.css` for shared colors, spacing, radii, and shadows. Shared palette tokens cover primary, secondary, AI, success, warning, danger, background, surface, text, and border colors.
- Keep feature-specific logic in its feature folder; don't put API calls or business rules into visual primitives.
- Prefer semantic HTML (`main`, `section`, `article`, labelled form controls) and keep keyboard focus visible.
- Avoid editing another member's feature files directly; integrate shared changes through the team's branch and review process.

Start the app from `frontend/` with `npm install` and `npm run dev`; verify changes with `npm run build`.
