import { materialUploadBody } from "./materials";
import { useCallback, useEffect, useState } from "react";
import { api } from "../../services/api";

export default function useContent({ pathname, sessionChecked, sessionUser, setError }) {
  const [contentItems, setContentItems] = useState([]);
  const [contentBusy, setContentBusy] = useState(false);
  const [contentSaving, setContentSaving] = useState(false);
  const loadContentItems = useCallback(async () => {
    setContentBusy(true);
    setError("");
    try {
      setContentItems(await api.content());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load uploaded content.");
    } finally {
      setContentBusy(false);
    }
  }, []);

  useEffect(() => {
    if ((pathname === "/dashboard/content" || pathname === "/dashboard/profile" || pathname === "/dashboard") && sessionChecked && sessionUser) {
      void loadContentItems();
    }
  }, [loadContentItems, pathname, sessionChecked, sessionUser]);

  useEffect(() => {
    if (!sessionChecked || !sessionUser) return;
    const assigned = event => { if (event.detail?.path?.endsWith("/assign")) void loadContentItems(); };
    window.addEventListener("smartplay-evidence-updated", assigned);
    return () => window.removeEventListener("smartplay-evidence-updated", assigned);
  }, [sessionChecked, sessionUser?.id, loadContentItems]);

  async function handleContentUpload(file, metadata, requestKey) {
    setContentSaving(true);
    setError("");
    try {
      const form = materialUploadBody(file, metadata);
      const uploaded = await api.uploadContent(form, requestKey);
      setContentItems((current) => [uploaded, ...current.filter(item => item.id !== uploaded.id)]);
      return uploaded;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not upload content.");
      throw cause;
    } finally {
      setContentSaving(false);
    }
  }

  async function handleMaterialSave(metadata, file, requestKey) {
    return handleContentUpload(file, metadata, requestKey);
  }

  async function handleContentUpdate(item, metadata) {
    setContentSaving(true);
    setError("");
    try {
      const updated = await api.updateContent(item.id, metadata);
      setContentItems((current) => current.map((content) => content.id === updated.id ? updated : content));
      return updated;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not update the material.");
      throw cause;
    } finally {
      setContentSaving(false);
    }
  }

  async function handleContentDownload(item) {
    setError("");
    try {
      const file = await api.downloadContent(item.id);
      const objectUrl = URL.createObjectURL(file);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = item.originalFileName;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not download this file.");
    }
  }

  async function handleContentDelete(item) {
    if (!window.confirm(`Delete "${item.title}"? This also removes its uploaded file.`)) return;
    setError("");
    try {
      await api.deleteContent(item.id);
      setContentItems((current) => current.filter((content) => content.id !== item.id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not delete this content.");
    }
  }

  return { loadContentItems, contentItems, contentBusy, contentSaving, handleContentUpload, handleContentDownload, handleContentDelete, handleMaterialSave, handleContentUpdate };
}
