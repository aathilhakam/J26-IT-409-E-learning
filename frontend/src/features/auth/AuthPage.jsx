import React, { useState } from "react";

function BrandMark() {
  return (
    <span className="auth-brand-mark" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none">
        <path d="M12 5.5c-2.4-1.6-5.1-1.7-8-.8v11.8c2.9-.9 5.6-.8 8 .8m0-11.8c2.4-1.6 5.1-1.7 8-.8v11.8c-2.9-.9-5.6-.8-8 .8m0-11.8v11.8" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M12 3.2v2" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      </svg>
    </span>
  );
}

export function AuthPage({ mode = "register", onModeChange, onSubmit }) {
  const [showPassword, setShowPassword] = useState(false);
  const [notice, setNotice] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const isRegister = mode === "register";

  async function handleSubmit(event) {
    event.preventDefault();
    setNotice("");
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    const account = {
      email: String(form.get("email")).trim(),
      password: String(form.get("password")),
    };
    if (isRegister) account.name = String(form.get("name")).trim();
    try {
      await onSubmit(account);
    } catch (cause) {
      setNotice(cause instanceof Error ? cause.message : "Could not complete your account request.");
    } finally {
      setSubmitting(false);
    }
  }

  function changeMode(nextMode) {
    setNotice("");
    onModeChange(nextMode);
  }

  return (
    <main className="auth-page">
      <header className="auth-header">
        <a className="auth-brand" href="#" onClick={(event) => event.preventDefault()}>
          <BrandMark />
          <span>SmartPlay<span className="auth-brand-dot">.</span><small>AI EDU</small></span>
        </a>
        <span className="auth-brand-caption">Learning Bridge · Teacher Portal</span>
        <div className="auth-header-prompt">
          {isRegister ? "Already have an account?" : "New to SmartPlay?"}
          {" "}
          <button type="button" onClick={() => changeMode(isRegister ? "login" : "register")}>
            {isRegister ? "Log in" : "Create account"}
          </button>
        </div>
      </header>

      <div className="auth-content">
        <section className="auth-showcase" aria-label="About SmartPlay">
          <div className="auth-showcase-copy">
            <span className="auth-eyebrow"><span /> MADE FOR TEACHERS</span>
            <h1>Make room for<br />every <em>kind</em> of progress.</h1>
            <p>A calmer, more personal way to support your learners and celebrate how far they’ve come.</p>
          </div>
          <div className="auth-preview" aria-hidden="true">
            <div className="auth-preview-top">
              <span className="auth-preview-avatar">A</span>
              <span><strong>Amelia’s progress</strong><small>Learning snapshot · This week</small></span>
              <span className="auth-preview-more">•••</span>
            </div>
            <div className="auth-preview-stat">
              <strong>Great momentum!</strong>
              <span>Amelia completed 4 learning activities this week.</span>
            </div>
            <div className="auth-preview-chart">
              <span style={{ height: "35%" }} />
              <span style={{ height: "54%" }} />
              <span style={{ height: "44%" }} />
              <span style={{ height: "74%" }} />
              <span style={{ height: "60%" }} />
              <span style={{ height: "92%" }} />
              <span style={{ height: "78%" }} />
            </div>
            <div className="auth-preview-days"><span>MON</span><span>TUE</span><span>WED</span><span>THU</span><span>FRI</span><span>SAT</span><span>SUN</span></div>
          </div>
          <div className="auth-showcase-note"><span aria-hidden="true">✦</span> Small steps add up to wonderful things.</div>
        </section>

        <section className="auth-panel" aria-labelledby="auth-heading">
          <div className="auth-panel-kicker"><span /> TEACHER WORKSPACE</div>
          <div className="auth-form-heading">
            <h2 id="auth-heading">{isRegister ? "Create your account" : "Welcome back"}</h2>
            <p className="auth-form-subtitle">
              {isRegister
                ? "Get started with a more personal learning journey."
                : "Sign in to continue to your learning workspace."}
            </p>
          </div>

          <div className="auth-mode-switch" aria-label="Choose an account action">
            <button type="button" className={isRegister ? "active" : ""} aria-pressed={isRegister} onClick={() => changeMode("register")}>Create account</button>
            <button type="button" className={!isRegister ? "active" : ""} aria-pressed={!isRegister} onClick={() => changeMode("login")}>Log in</button>
          </div>

          <form className="auth-form" onSubmit={handleSubmit}>
            {isRegister && (
              <label className="auth-field">
                <span>Full name</span>
                <span className="auth-input-wrap">
                  <span className="auth-input-icon" aria-hidden="true">♙</span>
                  <input autoComplete="name" name="name" placeholder="e.g. Alex Morgan" required />
                </span>
              </label>
            )}
            <label className="auth-field">
              <span>Email address</span>
              <span className="auth-input-wrap">
                <span className="auth-input-icon" aria-hidden="true">✉</span>
                <input autoComplete="email" name="email" type="email" placeholder="you@school.com" required />
              </span>
            </label>
            <label className="auth-field">
              <span>Password</span>
              <span className="auth-input-wrap">
                <span className="auth-input-icon auth-lock-icon" aria-hidden="true">⌑</span>
                <input
                  autoComplete={isRegister ? "new-password" : "current-password"}
                  minLength={8}
                  name="password"
                  placeholder={isRegister ? "At least 8 characters" : "Enter your password"}
                  required
                  type={showPassword ? "text" : "password"}
                />
                <button
                  className="auth-show-password"
                  type="button"
                  onClick={() => setShowPassword((visible) => !visible)}
                  aria-label={showPassword ? "Hide password" : "Show password"}
                >{showPassword ? "Hide" : "Show"}</button>
              </span>
            </label>
            {isRegister ? (
              <label className="auth-terms">
                <input name="terms" type="checkbox" required />
                <span>I agree to the <a href="#terms" onClick={(event) => event.preventDefault()}>Terms of Service</a> and <a href="#privacy" onClick={(event) => event.preventDefault()}>Privacy Policy</a>.</span>
              </label>
            ) : (
              <div className="auth-form-options">
                <label><input type="checkbox" name="remember" /> <span>Remember me</span></label>
                <button type="button" onClick={() => setNotice("Password reset is not available yet. Please contact your project administrator.")}>Forgot password?</button>
              </div>
            )}
            {notice && <p className="auth-notice" role="alert">{notice}</p>}
            <button className="auth-submit" type="submit" disabled={submitting}>
              {submitting ? "Please wait…" : isRegister ? "Create my account" : "Log in to SmartPlay"}
              <span aria-hidden="true">→</span>
            </button>
          </form>

          <div className="auth-security-note"><span aria-hidden="true">◇</span> Your classroom and learner information stay private.</div>
        </section>
      </div>
      <footer className="auth-copyright">© {new Date().getFullYear()} SmartPlay <span>·</span> Growing curious minds</footer>
    </main>
  );
}
