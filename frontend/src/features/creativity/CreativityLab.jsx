import React, { useEffect, useMemo, useRef, useState } from "react";
import { Avatar, Button, Card } from "../../components";
import { api } from "../../services/api";

const MAX_RECORDING_SECONDS = 60;

function toVoiceFile(blob) {
  if (!(blob instanceof Blob) || blob.size === 0) {
    throw new Error("The voice recording is empty. Record the story again before analyzing it.");
  }
  const mimeType = (blob.type || "audio/webm").split(";")[0].trim().toLowerCase();
  const extensions = {
    "audio/webm": "webm",
    "audio/ogg": "ogg",
    "audio/mp4": "mp4",
    "audio/m4a": "m4a",
    "audio/wav": "wav",
    "audio/x-wav": "wav",
    "audio/mpeg": "mp3",
  };
  const extension = extensions[mimeType];
  if (!extension) {
    throw new Error(`The browser recorded unsupported audio format "${mimeType}". Try recording again.`);
  }
  return new File([blob], `story-${Date.now()}.${extension}`, { type: mimeType });
}

async function getDrawingImageFile(canvas) {
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) {
    throw new Error("Could not prepare the drawing for image analysis.");
  }
  return new File([blob], `drawing-${Date.now()}.png`, { type: "image/png" });
}

const activities = [
  {
    id: "coloring",
    title: "Creative Coloring",
    eyebrow: "DRAW & COLOR",
    icon: "◉",
    tone: "peach",
    description: "Invite bold color choices, playful details, and a story behind every mark.",
    prompt: "Treehouse adventure",
    instruction: "What might happen in your treehouse? Add colors, details, and anything you imagine.",
  },
  {
    id: "picture",
    title: "Picture Completion",
    eyebrow: "SPATIAL & IMAGINATION",
    icon: "✦",
    tone: "mint",
    description: "Turn an unfinished picture into a new idea using shapes, patterns, and details.",
    prompt: "A little more to the picture",
    instruction: "Choose a shape or color and draw what you think belongs in this picture.",
  },
  {
    id: "story",
    title: "Storytelling",
    eyebrow: "NARRATIVE FLUENCY",
    icon: "♫",
    tone: "blue",
    description: "Build a story from a picture prompt, in the learner’s own words and voice.",
    prompt: "The secret in the garden",
    instruction: "Look at the picture. What do you think the friends have found? Tell us what happens next.",
  },
];

const rubric = [
  { id: "originality", label: "Originality", hint: "Fresh ideas & unusual details", icon: "✳" },
  { id: "imagination", label: "Imagination", hint: "Inventive worlds & possibilities", icon: "✧" },
  { id: "elaboration", label: "Elaboration", hint: "Adding detail to an idea", icon: "✎" },
  { id: "storytelling", label: "Storytelling", hint: "Connecting ideas into a story", icon: "♫" },
];

function averageScore(records) {
  const learnerRecords = records.filter(
    (record) => record.domain?.toLowerCase() === "creativity",
  );
  if (!learnerRecords.length) return 0;
  const average = learnerRecords.reduce((sum, record) => sum + (record.score / record.maxScore) * 100, 0) / learnerRecords.length;
  return Math.round(average);
}

function formatDate(value) {
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric" }).format(new Date(value));
}

export function CreativityLab({
  students,
  activeStudentId,
  records,
  assessments,
  onSelectStudent,
  onAddStudent,
  onSaveAssessment,
  onAssessmentComplete,
  busy,
}) {
  const [view, setView] = useState("overview");
  const [activityId, setActivityId] = useState("coloring");
  const [rubricScores, setRubricScores] = useState(() => Object.fromEntries(rubric.map((item) => [item.id, 3])));
  const [drawingColor, setDrawingColor] = useState("#3166c5");
  const [hasDrawing, setHasDrawing] = useState(false);
  const [uploadedFile, setUploadedFile] = useState(null);
  const [filePreviewUrl, setFilePreviewUrl] = useState("");
  const [storyText, setStoryText] = useState("");
  const [audioUrl, setAudioUrl] = useState("");
  const [audioBlob, setAudioBlob] = useState(null);
  const [recording, setRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [analyzingModels, setAnalyzingModels] = useState(false);
  const [imageAnalysis, setImageAnalysis] = useState(null);
  const [voiceAnalysis, setVoiceAnalysis] = useState(null);
  const [voiceAnalysisError, setVoiceAnalysisError] = useState("");
  const [imageAnalysisError, setImageAnalysisError] = useState("");
  const [error, setError] = useState("");
  const [latestAssessment, setLatestAssessment] = useState(null);
  const canvasRef = useRef(null);
  const drawingRef = useRef(false);
  const recorderRef = useRef(null);
  const streamRef = useRef(null);
  const audioRef = useRef(null);

  const activeStudent = students.find((student) => student.id === activeStudentId);
  const selectedActivity = activities.find((activity) => activity.id === activityId) ?? activities[0];
  const activeAssessments = assessments.filter((assessment) => assessment.studentId === activeStudentId);
  const activeRecordIds = new Set(activeAssessments.map((assessment) => assessment.recordId).filter(Boolean));
  const savedAssessments = records
    .filter((record) => record.domain?.toLowerCase() === "creativity" && !activeRecordIds.has(record.id))
    .map((record) => {
      const score = Math.max(1, Math.min(5, Math.round((record.score / record.maxScore) * 5)));
      const savedRubric = Object.fromEntries(rubric.map((item) => {
        const match = record.notes?.match(new RegExp(`${item.label}\\s+(\\d)\\/5`));
        return [item.id, match ? Number(match[1]) : score];
      }));
      return {
        id: record.id,
        studentId: activeStudentId,
        studentName: activeStudent?.name ?? "",
        activity: "Creativity assessment",
        prompt: record.notes || "Creativity observation",
        percentage: Math.round((record.score / record.maxScore) * 100),
        score: record.score,
        rubric: savedRubric,
        notes: record.notes || "Creativity progress recorded.",
        completedAt: record.assessedAt,
      };
    });
  const studentAssessments = [...activeAssessments, ...savedAssessments]
    .sort((first, second) => new Date(second.completedAt) - new Date(first.completedAt));
  const savedScore = averageScore(records);
  const criteriaAverage = useMemo(
    () => rubric.reduce((sum, item) => sum + (Number(rubricScores[item.id]) || 0), 0) / rubric.length,
    [rubricScores],
  );

  useEffect(() => {
    if (!canvasRef.current || view !== "activity" || activityId === "story") return;
    const canvas = canvasRef.current;
    const context = canvas.getContext("2d");
    if (!context) return;
    context.fillStyle = "#fffdf9";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.lineCap = "round";
    context.lineJoin = "round";
  }, [activityId, view]);

  useEffect(() => {
    if (!recording) return undefined;
    const intervalId = window.setInterval(() => {
      setRecordingSeconds((seconds) => {
        const nextSeconds = seconds + 1;
        if (seconds < MAX_RECORDING_SECONDS && nextSeconds >= MAX_RECORDING_SECONDS) {
          window.setTimeout(() => {
            if (recorderRef.current?.state === "recording") recorderRef.current.stop();
            setError("Recording stopped at the 60-second voice analysis limit.");
          }, 0);
        }
        return nextSeconds;
      });
    }, 1000);
    return () => window.clearInterval(intervalId);
  }, [recording]);

  useEffect(() => () => {
    if (recorderRef.current?.state === "recording") recorderRef.current.stop();
    streamRef.current?.getTracks().forEach((track) => track.stop());
  }, []);

  useEffect(() => {
    if (!audioUrl) return undefined;
    return () => URL.revokeObjectURL(audioUrl);
  }, [audioUrl]);

  useEffect(() => {
    if (!uploadedFile) {
      setFilePreviewUrl("");
      return undefined;
    }
    const previewUrl = URL.createObjectURL(uploadedFile);
    setFilePreviewUrl(previewUrl);
    return () => URL.revokeObjectURL(previewUrl);
  }, [uploadedFile]);

  function beginActivity(id) {
    if (recording) stopRecording();
    setActivityId(id);
    setRubricScores(Object.fromEntries(rubric.map((item) => [item.id, 3])));
    setHasDrawing(false);
    setUploadedFile(null);
    setStoryText("");
    setAudioUrl("");
    setAudioBlob(null);
    setRecordingSeconds(0);
    setVoiceAnalysis(null);
    setImageAnalysis(null);
    setVoiceAnalysisError("");
    setImageAnalysisError("");
    setError("");
    setView("activity");
  }

  function clearCanvas() {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "#fffdf9";
    context.fillRect(0, 0, canvas.width, canvas.height);
    setHasDrawing(false);
    setImageAnalysis(null);
    setImageAnalysisError("");
  }

  function draw(event) {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context || !drawingRef.current) return;
    const bounds = canvas.getBoundingClientRect();
    const x = (event.clientX - bounds.left) * (canvas.width / bounds.width);
    const y = (event.clientY - bounds.top) * (canvas.height / bounds.height);
    context.lineWidth = 7;
    context.strokeStyle = drawingColor;
    context.lineTo(x, y);
    context.stroke();
    context.beginPath();
    context.moveTo(x, y);
    setHasDrawing(true);
  }

  function startDrawing(event) {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context) return;
    const bounds = canvas.getBoundingClientRect();
    const x = (event.clientX - bounds.left) * (canvas.width / bounds.width);
    const y = (event.clientY - bounds.top) * (canvas.height / bounds.height);
    drawingRef.current = true;
    setImageAnalysis(null);
    setImageAnalysisError("");
    context.beginPath();
    context.arc(x, y, 3.5, 0, Math.PI * 2);
    context.fillStyle = drawingColor;
    context.fill();
    context.beginPath();
    context.moveTo(x, y);
    setHasDrawing(true);
  }

  function chooseFile(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setError("Choose an image file to add a learner’s drawing.");
      event.target.value = "";
      return;
    }
    if (file.size > 15 * 1024 * 1024) {
      setError("Choose an image smaller than 15 MB.");
      event.target.value = "";
      return;
    }
    setError("");
    setImageAnalysis(null);
    setImageAnalysisError("");
    setUploadedFile(file);
  }

  function removeUploadedFile() {
    setUploadedFile(null);
    setImageAnalysis(null);
    setImageAnalysisError("");
  }

  async function startRecording() {
    setError("");
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      setError("Audio recording is not available in this browser. Type the learner’s story below instead.");
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      const supportedMimeType = [
        "audio/webm;codecs=opus",
        "audio/ogg;codecs=opus",
        "audio/mp4",
      ].find((mimeType) => MediaRecorder.isTypeSupported(mimeType));
      const recorder = supportedMimeType
        ? new MediaRecorder(stream, { mimeType: supportedMimeType })
        : new MediaRecorder(stream);
      const chunks = [];
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunks.push(event.data);
      };
      recorder.onstop = () => {
        if (chunks.length > 0) {
          const blob = new Blob(chunks, { type: recorder.mimeType || "audio/webm" });
          setAudioBlob(blob);
          setAudioUrl(URL.createObjectURL(blob));
        }
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        setRecording(false);
      };
      recorderRef.current = recorder;
      setAudioUrl("");
      setAudioBlob(null);
      setVoiceAnalysis(null);
      setVoiceAnalysisError("");
      setRecordingSeconds(0);
      recorder.start();
      setRecording(true);
    } catch (cause) {
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
      setRecording(false);
      setError(cause instanceof Error ? `Could not start microphone: ${cause.message}` : "Could not start microphone.");
    }
  }

  function stopRecording() {
    if (recorderRef.current?.state === "recording") recorderRef.current.stop();
  }

  function playRecording() {
    const audio = audioRef.current;
    if (!audio) return;
    if (audio.paused) void audio.play();
    else audio.pause();
  }

  async function analyzeLearnerMedia() {
    if (recording) {
      setError("Stop the voice recording before analyzing it.");
      return;
    }
    const hasImage = activityId !== "story" && (uploadedFile || hasDrawing);
    if (activityId !== "story" && !hasImage && !audioBlob) {
      setError("Add a drawing or recording before asking the models to analyze this activity.");
      return;
    }
    if (activityId === "story" && !audioBlob) {
      setError("Record the learner’s voice before running voice analysis.");
      return;
    }

    setAnalyzingModels(true);
    setError("");
    setImageAnalysisError("");
    setVoiceAnalysisError("");
    const analyses = [];
    if (hasImage) {
      analyses.push(
        (async () => {
          try {
            const imageFile = uploadedFile || await getDrawingImageFile(canvasRef.current);
            setImageAnalysis(await api.analyzeImage(imageFile));
          } catch (cause) {
            setImageAnalysis(null);
            setImageAnalysisError(cause instanceof Error ? cause.message : "Image analysis could not be completed.");
          }
        })(),
      );
    }
    if (audioBlob) {
      analyses.push(
        (async () => {
          try {
            setVoiceAnalysis(await api.analyzeVoice(toVoiceFile(audioBlob)));
          } catch (cause) {
            setVoiceAnalysis(null);
            setVoiceAnalysisError(cause instanceof Error ? cause.message : "Voice analysis could not be completed.");
          }
        })(),
      );
    }
    await Promise.all(analyses);
    setAnalyzingModels(false);
  }

  async function submitAssessment() {
    if (!activeStudent) {
      setError("Select a learner before saving this assessment.");
      return;
    }
    if (activityId === "story" && !storyText.trim() && !audioBlob) {
      setError("Add a short story or record the learner’s voice before submitting.");
      return;
    }
    if (activityId !== "story" && !uploadedFile && !hasDrawing) {
      setError("Add a drawing or upload a photo before submitting.");
      return;
    }
    if (recording) {
      setError("Stop the voice recording before submitting.");
      return;
    }

    if (rubric.some((item) => !Number.isInteger(rubricScores[item.id]) || rubricScores[item.id] < 1 || rubricScores[item.id] > 5)) {
      setError("Enter a whole-number teacher observation score from 1 to 5 for every category.");
      return;
    }

    if (analyzingModels) {
      setError("Wait for the media analysis to finish before saving.");
      return;
    }

    setSubmitting(true);
    setError("");
    setVoiceAnalysisError("");
    setImageAnalysisError("");
    const score = Number(criteriaAverage.toFixed(1));
    const rubricSummary = rubric.map((item) => `${item.label} ${rubricScores[item.id]}/5`).join(", ");
    let learnerResponse = storyText.trim() || (
      activityId === "story"
        ? "Voice story submitted."
        : uploadedFile
          ? `Submitted ${uploadedFile.name}`
          : "Completed a drawing activity."
    );
    let voiceSummary = "";
    let imageSummary = "";
    let analysis = voiceAnalysis;
    let analysisError = "";
    let imageAnalysisResult = imageAnalysis;
    let imageAnalysisError = "";
    const assessment = {
      id: globalThis.crypto?.randomUUID?.() ?? `${Date.now()}`,
      studentId: activeStudent.id,
      studentName: activeStudent.name,
      activity: selectedActivity.title,
      prompt: selectedActivity.prompt,
      percentage: Math.round((score / 5) * 100),
      score,
      rubric: { ...rubricScores },
      voiceRecorded: Boolean(audioBlob),
      notes: `${selectedActivity.title} · ${selectedActivity.prompt} · Rubric: ${rubricSummary}. ${learnerResponse.slice(0, 160)}`,
      completedAt: new Date().toISOString(),
    };
    try {
      if (activityId !== "story" && !imageAnalysisResult) {
        try {
          const imageFile = uploadedFile || await getDrawingImageFile(canvasRef.current);
          imageAnalysisResult = await api.analyzeImage(imageFile);
        } catch (cause) {
          imageAnalysisError = cause instanceof Error ? cause.message : "Image analysis could not be completed.";
        }
      }
      if (activityId !== "story" && imageAnalysisResult) {
        imageSummary = `Image estimates: ${Object.entries(imageAnalysisResult.scores).map(([target, value]) => `${target.replaceAll("_", " ")} ${value}/5`).join(", ")}.`;
      }
      if (audioBlob && !analysis) {
        try {
          analysis = await api.analyzeVoice(toVoiceFile(audioBlob));
        } catch (cause) {
          analysisError = cause instanceof Error ? cause.message : "Voice analysis could not be completed.";
        }
      }
      if (analysis) {
        voiceSummary = `Voice classification: ${analysis.creativity_class} (${Math.round(analysis.model_score * 100)}% model confidence).`;
      }
      assessment.notes = `${selectedActivity.title} · ${selectedActivity.prompt} · Rubric: ${rubricSummary}. ${learnerResponse.slice(0, 100)} ${imageSummary} ${voiceSummary}`.trim();
      assessment.imageAnalysis = imageAnalysisResult;
      assessment.imageAnalysisError = imageAnalysisError;
      assessment.voiceAnalysis = analysis;
      assessment.voiceAnalysisError = analysisError;
      const savedRecord = await onSaveAssessment({ score, notes: assessment.notes });
      assessment.recordId = savedRecord?.id;
      onAssessmentComplete(assessment);
      setLatestAssessment(assessment);
      setImageAnalysis(imageAnalysisResult);
      setVoiceAnalysis(analysis);
      setVoiceAnalysisError(analysisError);
      setImageAnalysisError(imageAnalysisError);
      setView("result");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not save the assessment.");
    } finally {
      setSubmitting(false);
    }
  }

  function downloadJourney() {
    const report = {
      student: activeStudent?.name,
      exportedAt: new Date().toISOString(),
      assessments: studentAssessments,
    };
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${(activeStudent?.name || "learner").toLowerCase().replace(/[^a-z0-9]+/g, "-")}-creativity-journey.json`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return (
    <div className="creativity-lab">
      <header className="creativity-heading">
        <div>
          <div className="creativity-eyebrow"><span /> EARLY CHILDHOOD DIAGNOSTIC <i>·</i> PLAY-BASED LEARNING</div>
          <h1>Creativity Lab</h1>
          <p>Notice original ideas, vivid imagination, and the stories learners create.</p>
        </div>
        <label className="creativity-student-picker">
          <span>ASSESSING</span>
          <select value={activeStudentId} onChange={(event) => onSelectStudent(event.target.value)} disabled={!students.length || view === "activity"}>
            {!students.length && <option value="">No learners yet</option>}
            {students.map((student) => <option value={student.id} key={student.id}>{student.name} · {student.grade}</option>)}
          </select>
        </label>
      </header>

      <nav className="creativity-tabs" aria-label="Creativity workspace">
        {[
          ["overview", "Overview"],
          ["assessment", "Assessments"],
          ["journey", "Creativity journey"],
        ].map(([id, label]) => (
          <button key={id} type="button" className={view === id ? "active" : ""} aria-current={view === id ? "page" : undefined} onClick={() => { if (recording) stopRecording(); setError(""); setView(id); }}>
            {label}
            {id === "journey" && studentAssessments.length > 0 && <span>{studentAssessments.length}</span>}
          </button>
        ))}
      </nav>

      {error && <div className="creativity-error" role="alert"><span>{error}</span><button type="button" onClick={() => setError("")} aria-label="Dismiss message">×</button></div>}

      {view === "overview" && (
        <section className="creativity-overview" aria-labelledby="creativity-overview-title">
          <div className="creativity-overview-title">
            <div>
              <span className="creativity-kicker">YOUR CLASSROOM</span>
              <h2 id="creativity-overview-title">{activeStudent ? `Creativity assessment · ${activeStudent.grade}` : "A little room for big ideas"}</h2>
              <p>{activeStudent ? "Choose a learner to see their progress, or start a new play-based assessment." : "Add a learner to your class, then start exploring their creative thinking."}</p>
            </div>
            <span className="creativity-live"><i /> {students.length} {students.length === 1 ? "learner" : "learners"} in class</span>
          </div>

          {busy && students.length === 0 ? (
            <div className="creativity-empty"><span>✧</span><strong>Loading your learners…</strong></div>
          ) : students.length > 0 ? (
            <div className="creativity-student-grid">
              {students.map((student, index) => {
                const savedAverage = student.id === activeStudentId ? averageScore(records) : 0;
                const score = savedAverage || Math.round(assessments.filter((assessment) => assessment.studentId === student.id).reduce((total, item, _index, list) => total + item.percentage / list.length, 0));
                const isSelected = student.id === activeStudentId;
                const recent = assessments.find((assessment) => assessment.studentId === student.id);
                return (
                  <button
                    className={`creativity-student-card${isSelected ? " selected" : ""}`}
                    type="button"
                    key={student.id}
                    aria-pressed={isSelected}
                    onClick={() => onSelectStudent(student.id)}
                  >
                    <span className="creativity-student-identity">
                      <Avatar label={student.name.split(/\s+/).map((part) => part[0]).slice(0, 2).join("").toUpperCase()} variant={index} className="creativity-avatar" />
                      <span><small>{student.grade}</small><strong>{student.name}</strong><span className="creativity-age">Learner profile</span></span>
                    </span>
                    <span className="creativity-score-box">
                      <span><small>CREATIVITY GAUGE</small><strong>{score || "—"}{score > 0 && <i>%</i>}</strong><em>{score > 0 ? "Growing ideas" : "Ready to explore"}</em></span>
                      <span className="creativity-score-ring">{score ? "✧" : "＋"}</span>
                    </span>
                    <span className="creativity-recent-label">RECENT ACTIVITY</span>
                    <span className="creativity-recent">{recent ? `✧ ${recent.activity}` : "✧ No activity yet"}</span>
                    <span className="creativity-student-footer">{isSelected ? "Selected learner" : "View learner"} <b>→</b></span>
                  </button>
                );
              })}
              <button className="creativity-add-student" type="button" onClick={onAddStudent}>
                <span>＋</span><strong>Add a learner</strong><small>Start a creativity journey for someone new.</small>
              </button>
            </div>
          ) : (
            <div className="creativity-empty">
              <span>✧</span><strong>No learners yet</strong>
              <p>Add a student to begin play-based creativity assessments.</p>
              <Button onClick={onAddStudent}>＋ Add learner</Button>
            </div>
          )}

          <Card className="creativity-focus-bar">
            <span className="creativity-focus-icon">✧</span>
            <div className="creativity-focus-copy">
              <span className="creativity-kicker">READY WHEN THEY ARE</span>
              <strong>{activeStudent ? `Selected focus: ${activeStudent.name}` : "Choose a learner to get started"}</strong>
              <p>{activeStudent ? "Pick a playful prompt and capture how they bring an idea to life." : "Assessments are saved to each learner’s classroom profile."}</p>
            </div>
            <Button variant="secondary" onClick={() => setView("journey")}>Student journey</Button>
            <Button onClick={() => setView("assessment")}>Start assessment <span aria-hidden="true">✧ →</span></Button>
          </Card>
          <div className="creativity-research-note"><span>ⓘ</span><strong>Teacher observation tool</strong><span>Image estimates and optional voice classification are shown separately for your review. You remain in control of rubric scores.</span></div>
        </section>
      )}

      {view === "assessment" && (
        <section className="creativity-assessment-page">
          <div className="creativity-page-intro">
            <span className="creativity-step-label">STEP 1 OF 3 · ACTIVITY SELECTION</span>
            <h2>What would you like to explore?</h2>
            <p>Pick a playful activity for {activeStudent?.name || "your learner"}. There are no wrong answers — curiosity comes first.</p>
          </div>
          <div className="creativity-activity-grid">
            {activities.map((activity) => (
              <Card as="article" key={activity.id} className={`creativity-activity-card ${activity.tone}${activityId === activity.id ? " picked" : ""}`}>
                <button className="creativity-activity-select" type="button" aria-pressed={activityId === activity.id} onClick={() => setActivityId(activity.id)}>
                  <span className="creativity-activity-icon">{activity.icon}</span>
                  <span className="creativity-activity-eyebrow">{activity.eyebrow}</span>
                  <strong>{activity.title}</strong>
                  <span className="creativity-activity-desc">{activity.description}</span>
                  <span className="creativity-activity-art" aria-hidden="true">
                    {activity.id === "story" ? <span className="story-garden-art">🌳<i>✿</i><b>✦</b><em>🦋</em></span> : activity.id === "picture" ? <span className="picture-art"><i>◯</i><b>△</b><em>□</em><small>What could it become?</small></span> : <span className="coloring-art"><i>☀</i><b>⌂</b><em>✿</em><small>Make it your own</small></span>}
                  </span>
                  <span className="creativity-activity-bottom"><span>{activity.id === "story" ? "Voice or written story" : "Draw, upload, or take a photo"}</span><b>{activityId === activity.id ? "Selected ✓" : "Choose →"}</b></span>
                </button>
              </Card>
            ))}
          </div>
          <Card className="creativity-accessibility">
            <span>◖</span><div><strong>Every learner can join in</strong><p>Give spoken instructions, take a break whenever needed, and accept drawings or stories in any form.</p></div>
          </Card>
          <div className="creativity-step-actions">
            <Button variant="quiet" onClick={() => setView("overview")}>← Back to overview</Button>
            <Button
              onClick={() => {
                if (!activeStudent) {
                  onAddStudent();
                  return;
                }
                beginActivity(activityId);
              }}
            >
              {activeStudent ? `Open ${selectedActivity.title} →` : "Add a learner to continue →"}
            </Button>
          </div>
        </section>
      )}

      {view === "activity" && (
        <section className="creativity-workspace">
          <div className="creativity-page-intro">
            <button type="button" className="creativity-back-link" onClick={() => { stopRecording(); setView("assessment"); }}>← Choose a different activity</button>
            <span className="creativity-step-label">STEP 2 OF 3 · {selectedActivity.eyebrow}</span>
            <h2>{selectedActivity.title}</h2>
            <p>{selectedActivity.instruction}</p>
          </div>
          <div className={`creativity-work-grid${activityId === "story" ? " story-work-grid" : ""}`}>
            <Card className="creativity-canvas-card">
              <div className="creativity-card-heading"><div><span className="creativity-kicker">TODAY’S PROMPT</span><h3>{selectedActivity.prompt}</h3></div><span className="creativity-printable">✦ Play &amp; explore</span></div>
              {activityId === "story" ? (
                <div className="creativity-story-scene" role="img" aria-label="A whimsical garden with a great tree and a glowing secret">
                  <span className="scene-sun">☀</span><span className="scene-cloud cloud-one">☁</span><span className="scene-cloud cloud-two">☁</span>
                  <span className="scene-tree">🌳</span><span className="scene-flower flower-one">✿</span><span className="scene-flower flower-two">✿</span>
                  <span className="scene-friends">🧒🏽　🧒🏻　🧒🏾</span><span className="scene-treasure">✧ ◇ ✧</span>
                  <span className="scene-prompt">What do you think is hiding in the garden?</span>
                </div>
              ) : (
                <>
                  <div className="creativity-canvas-tools">
                    <span>{activityId === "coloring" ? "Draw your treehouse adventure" : "Make the picture your own"}</span>
                    <div className="creativity-color-options" role="group" aria-label="Choose a drawing color">
                      {["#3166c5", "#f08a56", "#37a77a", "#8b67c8", "#e5b332", "#263f62"].map((color) => (
                        <button key={color} type="button" className={drawingColor === color ? "active" : ""} style={{ "--swatch-color": color }} aria-label={`Use ${color} drawing color`} aria-pressed={drawingColor === color} onClick={() => setDrawingColor(color)} />
                      ))}
                    </div>
                    <Button variant="quiet" size="small" onClick={clearCanvas}>Clear drawing</Button>
                  </div>
                  <canvas
                    ref={canvasRef}
                    className="creativity-drawing-canvas"
                    width="1000"
                    height="540"
                    aria-label="Drawing area. Draw using your mouse, touch screen, or stylus."
                    onPointerDown={startDrawing}
                    onPointerMove={draw}
                    onPointerUp={(event) => { drawingRef.current = false; event.currentTarget.getContext("2d")?.beginPath(); }}
                    onPointerCancel={() => { drawingRef.current = false; }}
                  />
                  {uploadedFile && <div className="creativity-file-preview"><img src={filePreviewUrl} alt={`Uploaded drawing: ${uploadedFile.name}`} /><span>{uploadedFile.name}</span><button type="button" onClick={removeUploadedFile} aria-label="Remove uploaded image">×</button></div>}
                </>
              )}
              <div className="creativity-prompt-foot"><span>ⓘ</span> There is no single right answer. Celebrate their choices and ask what they imagined.</div>
            </Card>

            <div className="creativity-response-column">
              {activityId === "story" ? (
                <Card className="creativity-response-card">
                  <span className="creativity-step-label">LEARNER RESPONSE</span>
                  <h3>Tell your story</h3>
                  <p>Record a voice story or write down their words together.</p>
                  <div className={`creativity-record-status${recording ? " recording" : ""}`}><i />{recording ? "Recording in progress" : audioUrl ? "Recording ready to play" : "Microphone ready"}<span>{String(Math.floor(recordingSeconds / 60)).padStart(2, "0")}:{String(recordingSeconds % 60).padStart(2, "0")}</span></div>
                  <div className="creativity-record-actions">
                    {!recording
                      ? <Button onClick={() => void startRecording()}>{audioUrl ? "● Record again" : "🎙 Start recording"}</Button>
                      : <Button variant="reject" onClick={stopRecording}>■ Stop recording</Button>}
                    {audioUrl && <Button variant="secondary" onClick={playRecording}>▶ Listen</Button>}
                    <audio ref={audioRef} src={audioUrl || undefined} controls={Boolean(audioUrl)} className={audioUrl ? "creativity-audio" : "sr-only"} aria-label="Recorded learner story" />
                  </div>
                  <label className="creativity-story-label" htmlFor="creativity-story-text">Story words <span>Optional if you recorded audio</span></label>
                  <textarea id="creativity-story-text" value={storyText} onChange={(event) => setStoryText(event.target.value)} placeholder="Write what the learner said, in their own words…" rows={5} />
                  <ModelObservationPanel
                    activityId={activityId}
                    imageAnalysis={imageAnalysis}
                    imageAnalysisError={imageAnalysisError}
                    voiceAnalysis={voiceAnalysis}
                    voiceAnalysisError={voiceAnalysisError}
                    analyzing={analyzingModels}
                    canAnalyze={Boolean(audioBlob)}
                    onAnalyze={() => void analyzeLearnerMedia()}
                  />
                  <Card className="creativity-teacher-observation-card">
                    <h3>Teacher observation</h3>
                    <p>Set these rubric scores independently. AI analysis never changes them automatically.</p>
                    <RubricEditor scores={rubricScores} onChange={setRubricScores} />
                  </Card>
                  <Button className="creativity-submit" onClick={() => void submitAssessment()} disabled={submitting || recording}>{submitting ? (audioBlob ? "Analyzing voice & saving…" : "Saving assessment…") : "Save learner’s story ✧"}</Button>
                  <small className="creativity-next-note">Recorded voice gets an experimental classification; typed stories remain teacher-reviewed.</small>
                </Card>
              ) : (
                <>
                  <Card className="creativity-response-card">
                    <span className="creativity-step-label">STEP 2 · COLLECT RESPONSE</span>
                    <h3>Save their creation</h3>
                    <p>Keep a digital sketch or upload a photo of the learner’s paper drawing.</p>
                    <label className="creativity-file-button">↥ Upload drawing<input type="file" accept="image/*" onChange={chooseFile} /></label>
                    <label className="creativity-file-button secondary">▣ Take a photo<input type="file" accept="image/*" capture="environment" onChange={chooseFile} /></label>
                    <div className="creativity-upload-hint"><span>▧</span><strong>PNG, JPG, or WEBP</strong><small>Up to 15 MB. Or draw directly on the canvas.</small></div>
                    <div className="creativity-optional-voice">
                      <span className="creativity-step-label">OPTIONAL VOICE REFLECTION · WAVLM</span>
                      <p>Invite the learner to explain their picture. Image and voice estimates will be shown separately.</p>
                      <div className={`creativity-record-status${recording ? " recording" : ""}`}><i />{recording ? "Recording in progress" : audioUrl ? "Recording ready to play" : "Microphone ready"}<span>{String(Math.floor(recordingSeconds / 60)).padStart(2, "0")}:{String(recordingSeconds % 60).padStart(2, "0")}</span></div>
                      <div className="creativity-record-actions">
                        {!recording
                          ? <Button onClick={() => void startRecording()}>{audioUrl ? "● Record again" : "🎙 Record explanation"}</Button>
                          : <Button variant="reject" onClick={stopRecording}>■ Stop recording</Button>}
                        {audioUrl && <Button variant="secondary" onClick={playRecording}>▶ Listen</Button>}
                        <audio ref={audioRef} src={audioUrl || undefined} controls={Boolean(audioUrl)} className={audioUrl ? "creativity-audio" : "sr-only"} aria-label="Recorded learner explanation" />
                      </div>
                    </div>
                    <ModelObservationPanel
                      activityId={activityId}
                      imageAnalysis={imageAnalysis}
                      imageAnalysisError={imageAnalysisError}
                      voiceAnalysis={voiceAnalysis}
                      voiceAnalysisError={voiceAnalysisError}
                      analyzing={analyzingModels}
                      canAnalyze={Boolean(uploadedFile || hasDrawing || audioBlob)}
                      onAnalyze={() => void analyzeLearnerMedia()}
                    />
                    <Card className="creativity-teacher-observation-card">
                      <h3>Teacher observation</h3>
                      <p>Set these rubric scores independently. AI analysis never changes them automatically.</p>
                      <RubricEditor
                        scores={rubricScores}
                        onChange={setRubricScores}
                        imageSuggestions={imageAnalysis?.scores}
                      />
                    </Card>
                    <Button className="creativity-submit" onClick={() => void submitAssessment()} disabled={submitting || analyzingModels || recording}>{submitting ? (audioBlob ? "Analyzing models & saving…" : "Analyzing image & saving…") : "Save & review ✧"}</Button>
                    <small className="creativity-next-note">AI estimates are experimental, shown separately, and do not replace the teacher’s rubric.</small>
                  </Card>
                  <Card className="creativity-growth-note"><span>✧</span><div><strong>Look for the process, not perfection</strong><p>Notice unusual choices, invented details, and how the learner explains their idea.</p></div></Card>
                </>
              )}
            </div>
          </div>
        </section>
      )}

      {view === "result" && latestAssessment && (
        <section className="creativity-result-page">
          <div className="creativity-result-header">
            <div><span className="creativity-step-label">COMPLETED · {latestAssessment.studentName}</span><h2>Look at those ideas grow!</h2><p>{latestAssessment.activity} added to {latestAssessment.studentName}’s creativity journey.</p></div>
            <Button variant="secondary" onClick={() => window.print()}>▣ Print summary</Button>
          </div>
          <Card className="creativity-result-summary">
            <div className="creativity-result-gauge"><strong>{latestAssessment.percentage}%</strong><span>CREATIVITY<br />OBSERVATION</span></div>
            <div className="creativity-result-rubric">{rubric.map((item) => (
              <div className="creativity-result-score" key={item.id}><span>{item.icon}</span><div><strong>{item.label}</strong><small>{item.hint}</small><i><b style={{ width: `${latestAssessment.rubric[item.id] / 5 * 100}%` }} /></i></div><em>{latestAssessment.rubric[item.id]}/5</em></div>
            ))}</div>
          </Card>
          <div className="creativity-ai-model-grid">
          {latestAssessment.activity !== "Storytelling" && (
            <Card className="creativity-image-model-result" aria-live="polite">
              <div className="creativity-voice-model-heading">
                <span className="creativity-step-label">EXPERIMENTAL IMAGE ESTIMATES</span>
                {latestAssessment.imageAnalysis && <strong>Average {latestAssessment.imageAnalysis.average_score.toFixed(1)}/5</strong>}
              </div>
              {latestAssessment.imageAnalysis ? (
                <>
                  <p>These exploratory image estimates are suggestions only; the teacher’s rubric remains authoritative.</p>
                  <div className="creativity-image-model-scores">
                    {Object.entries(latestAssessment.imageAnalysis.scores).map(([target, value]) => (
                      <span key={target}><b>{target.replaceAll("_", " ")}</b><i><em style={{ width: `${Math.round(value / 5 * 100)}%` }} /></i><small>{value.toFixed(1)}/5</small></span>
                    ))}
                  </div>
                </>
              ) : (
                <p role="status">The assessment was saved, but image analysis was unavailable: {latestAssessment.imageAnalysisError || imageAnalysisError}</p>
              )}
            </Card>
          )}
          {(latestAssessment.activity === "Storytelling" || latestAssessment.voiceRecorded) && (
            <Card className="creativity-voice-model-result" aria-live="polite">
              <div className="creativity-voice-model-heading">
                <span className="creativity-step-label">EXPERIMENTAL VOICE CLASSIFICATION</span>
                {latestAssessment.voiceAnalysis && <strong>{latestAssessment.voiceAnalysis.creativity_class}</strong>}
              </div>
              {latestAssessment.voiceAnalysis ? (
                <>
                  <p>The model predicts a broad voice creativity class; this is not a numeric rubric score or speech transcription. Teacher observation remains separate and authoritative.</p>
                  <div className="creativity-voice-model-scores">
                    {Object.entries(latestAssessment.voiceAnalysis.class_scores).map(([label, probability]) => (
                      <span key={label} className={label === latestAssessment.voiceAnalysis.creativity_class ? "predicted" : ""}>
                        <b>{label}</b><i><em style={{ width: `${Math.round(probability * 100)}%` }} /></i><small>{Math.round(probability * 100)}%</small>
                      </span>
                    ))}
                  </div>
                  <small className="creativity-voice-model-caveat">Model confidence: {Math.round(latestAssessment.voiceAnalysis.model_score * 100)}%. Treat this experimental output as a suggestion; teacher assessment remains authoritative.</small>
                </>
              ) : latestAssessment.voiceAnalysisError ? (
                <p role="status">The assessment was saved, but voice analysis was unavailable: {latestAssessment.voiceAnalysisError}</p>
              ) : (
                <p>No voice recording was analyzed. Typed stories are reviewed using the teacher’s rubric.</p>
              )}
            </Card>
          )}
          {latestAssessment.activity !== "Storytelling" && !latestAssessment.voiceRecorded && (
            <Card className="creativity-voice-model-result">
              <div className="creativity-voice-model-heading"><span className="creativity-step-label">OPTIONAL VOICE REFLECTION</span></div>
              <p>No voice recording was added. The image model and teacher rubric still apply independently.</p>
            </Card>
          )}
          </div>
          <div className="creativity-result-cards">
            <Card className="creativity-strength-card"><span>✧ STRENGTHS TO CELEBRATE</span><h3>Notice what came naturally</h3><p>Use the learner’s highest rubric scores to guide your next conversation.</p><ul>{rubric.filter((item) => latestAssessment.rubric[item.id] >= 4).map((item) => <li key={item.id}><b>✓</b><span><strong>{item.label}</strong><small>{item.hint}</small></span></li>)}</ul></Card>
            <Card className="creativity-next-focus"><span>↗ NEXT STEP</span><h3>Keep the curiosity going</h3><p>Try a different kind of prompt next time and notice how their ideas change.</p><Button onClick={() => setView("journey")}>View creativity journey →</Button></Card>
          </div>
          <div className="creativity-step-actions"><Button variant="quiet" onClick={() => setView("overview")}>← Overview</Button><Button onClick={() => setView("assessment")}>Start another activity ✧</Button></div>
        </section>
      )}

      {view === "journey" && (
        <section className="creativity-journey-page">
          <div className="creativity-journey-header">
            <div><span className="creativity-step-label">LEARNER PORTFOLIO</span><h2>{activeStudent ? `${activeStudent.name}’s creativity journey` : "Creativity journey"}</h2><p>A record of ideas, experiments, and creative progress over time.</p></div>
            <div className="creativity-journey-actions"><Button variant="secondary" onClick={downloadJourney} disabled={!studentAssessments.length}>↓ Export journey</Button><Button variant="secondary" onClick={() => window.print()}>▣ Print</Button></div>
          </div>
          <Card className="creativity-timeline">
            <div className="creativity-card-heading"><div><span className="creativity-kicker">ASSESSMENT PROGRESS</span><h3>Every idea is a milestone</h3></div><span className="creativity-timeline-key"><i /> Observation</span></div>
            {studentAssessments.length ? (
              <div className="creativity-history">
                {studentAssessments.map((assessment, index) => (
                  <article className="creativity-history-item" key={assessment.id}>
                    <span className="creativity-history-marker">{index === 0 ? "✧" : index + 1}</span>
                    <div><span className="creativity-kicker">{formatDate(assessment.completedAt)} · {assessment.activity}</span><h4>{assessment.prompt}</h4><p>{assessment.notes}</p></div>
                    <strong>{assessment.percentage}%</strong>
                  </article>
                ))}
              </div>
            ) : (
              <div className="creativity-empty creativity-journey-empty"><span>✧</span><strong>The first idea is waiting</strong><p>Complete a creativity activity to add the first milestone to this journey.</p><Button onClick={() => setView("assessment")}>Start an activity</Button></div>
            )}
          </Card>
          {studentAssessments.length > 0 && (
            <div className="creativity-journey-bottom">
              <Card className="creativity-journey-focus"><span className="creativity-kicker">KEEP EXPLORING</span><h3>Try a new creative strand</h3><p>Pair a hands-on drawing activity with a story prompt to explore different ways of expressing ideas.</p><Button onClick={() => setView("assessment")}>Choose next activity →</Button></Card>
              <Card className="creativity-journey-strands"><span className="creativity-kicker">STRANDS OBSERVED</span>{rubric.map((item) => {
                const score = studentAssessments.reduce((sum, assessment) => sum + assessment.rubric[item.id] / studentAssessments.length, 0);
                return <div className="creativity-strand" key={item.id}><span>{item.label}</span><strong>{score ? `${Math.round(score / 5 * 100)}%` : "—"}</strong><i><b style={{ width: `${score / 5 * 100}%` }} /></i></div>;
              })}</Card>
            </div>
          )}
        </section>
      )}
    </div>
  );
}

function ModelObservationPanel({
  activityId,
  imageAnalysis,
  imageAnalysisError,
  voiceAnalysis,
  voiceAnalysisError,
  analyzing,
  canAnalyze,
  onAnalyze,
}) {
  const imageScores = imageAnalysis?.scores;
  return (
    <Card as="section" className="creativity-model-observations" aria-live="polite" aria-label="AI model analysis">
      <div className="creativity-model-observations-heading">
        <div>
          <span className="creativity-step-label">AI MODEL ANALYSIS · SEPARATE FROM TEACHER OBSERVATION</span>
          <p>Image and voice analysis are shown separately for review before setting teacher scores.</p>
        </div>
        <Button variant="secondary" size="small" onClick={onAnalyze} disabled={!canAnalyze || analyzing}>
          {analyzing ? "Analyzing…" : "Analyze media"}
        </Button>
      </div>
      <div className="creativity-model-observations-results">
        {activityId !== "story" && (
          <div className="creativity-model-observation">
            <strong>Picture · Image analysis</strong>
            {imageScores ? (
              <div className="creativity-model-observation-scores">
                {Object.entries(imageScores).map(([target, value]) => (
                  <span key={target}>{target.replaceAll("_", " ")} <b>{value.toFixed(1)}/5</b></span>
                ))}
              </div>
            ) : (
              <small>{imageAnalysisError || "Add a drawing, then select Analyze media to get visual score suggestions."}</small>
            )}
          </div>
        )}
        <div className="creativity-model-observation voice">
          <strong>Voice · Classification</strong>
          {voiceAnalysis ? (
            <div className="creativity-model-observation-classification">
              <b>Prediction: {voiceAnalysis.creativity_class}</b>
              <span>{Math.round(voiceAnalysis.model_score * 100)}% model confidence</span>
            </div>
          ) : (
            <small>{voiceAnalysisError || "Record the learner’s voice, then select Analyze media for voice classification."}</small>
          )}
        </div>
      </div>
      <small className="creativity-model-observations-note">AI output is experimental and never changes teacher rubric scores automatically. Image suggestions may be applied manually using the AI buttons in the separate teacher rubric.</small>
    </Card>
  );
}

function RubricEditor({ scores, onChange, imageSuggestions }) {
  return (
    <fieldset className="creativity-rubric">
      <legend>TEACHER OBSERVATION <span>Type a whole number from 1 to 5</span></legend>
      {rubric.map((item) => (
        <div className="creativity-rubric-row" key={item.id}>
          <label htmlFor={`rubric-${item.id}`}><span>{item.icon}</span><span><strong>{item.label}</strong><small>{item.hint}</small></span></label>
          {imageSuggestions?.[item.id] !== undefined && (
            <button
              type="button"
              className="creativity-use-image-suggestion"
              onClick={() => onChange((current) => ({ ...current, [item.id]: Math.max(1, Math.min(5, Math.round(imageSuggestions[item.id]))) }))}
              aria-label={`Use image estimate ${imageSuggestions[item.id].toFixed(1)} for ${item.label}`}
            >
              AI {imageSuggestions[item.id].toFixed(1)}
            </button>
          )}
          <input
            id={`rubric-${item.id}`}
            type="number"
            min="1"
            max="5"
            step="1"
            required
            inputMode="numeric"
            value={scores[item.id] ?? ""}
            onChange={(event) => {
              const value = event.target.value;
              onChange((current) => ({ ...current, [item.id]: value === "" ? "" : Number(value) }));
            }}
            aria-label={`${item.label} score out of five`}
            aria-invalid={!Number.isInteger(scores[item.id]) || scores[item.id] < 1 || scores[item.id] > 5}
          />
        </div>
      ))}
    </fieldset>
  );
}
