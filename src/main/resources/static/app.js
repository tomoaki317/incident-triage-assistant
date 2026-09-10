"use strict";
(() => {
  const byId = id => document.getElementById(id);
  const form = byId("preview-form");
  const symptom = byId("symptom");
  const log = byId("log");
  const logStatus = byId("log-status");
  let pending = null;
  let revision = 0;
  // Match Java Character.isWhitespace/isSpaceChar, including supplementary-safe counting.
  const missing = value => /^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/u.test(value);
  const count = value => Array.from(value).length;
  function counters() {
    for (const [field, max] of [[symptom, 2000], [log, 20000]]) {
      const length = count(field.value);
      const output = byId(`${field.id}-count`);
      output.textContent = `${length.toLocaleString("ja-JP")} / ${max.toLocaleString("ja-JP")}文字`;
      output.classList.toggle("over", length > max);
      field.setAttribute("aria-invalid", String(length > max));
    }
    logStatus.required = missing(log.value);
  }
  function clearPreview() {
    byId("preview").hidden = true;
    byId("empty").hidden = false;
    for (const id of ["metadata", "masked-symptom", "masked-log", "context", "warnings"]) byId(id).replaceChildren();
  }
  function error(message) {
    byId("error").textContent = message;
    byId("error").hidden = !message;
  }
  function busy(value) {
    byId("submit").disabled = value;
    byId("inputs").disabled = value;
    byId("preview-panel").setAttribute("aria-busy", String(value));
    byId("submit").textContent = value ? "確認中…" : "送信内容を確認";
  }
  function reset() {
    revision++;
    pending?.abort();
    pending = null;
    form.reset();
    // Explicit values also clear browser-restored form state.
    symptom.value = log.value = logStatus.value = "";
    clearPreview(); error(""); counters(); busy(false);
    byId("status").textContent = "";
  }
  function entry(target, label, value) {
    const term = document.createElement("dt"); term.textContent = label;
    const detail = document.createElement("dd"); detail.textContent = value;
    target.append(term, detail);
  }
  function render(data) {
    const input = data.masked_input;
    const fields = { occurred_at: "発生日時", environment: "環境", impact: "影響範囲", ongoing_status: "継続状況", recent_changes: "直前の変更", checks_performed: "実施済み確認", destination: "引き継ぎ先" };
    // Do not display malformed responses or arbitrary extra properties.
    if (!input || !input.context || [input.symptom, input.log, input.log_status, data.destination, data.purpose,
      ...Object.keys(fields).map(key => input.context[key])].some(value => typeof value !== "string") ||
      !Array.isArray(data.warnings) || data.warnings.some(value => typeof value !== "string") || !Array.isArray(data.log_line_ids)) throw new Error();
    const lines = input.log === "" ? [] : input.log.split(/\r\n|\r|\n/);
    if (lines.length !== data.log_line_ids.length || data.log_line_ids.some((id, i) => id !== `log:L${i + 1}`)) throw new Error();
    entry(byId("metadata"), "送信先", data.destination);
    entry(byId("metadata"), "利用目的", data.purpose);
    entry(byId("metadata"), "ログ取得状況", input.log_status);
    byId("masked-symptom").textContent = input.symptom;
    if (!lines.length) byId("masked-log").textContent = "ログ未入力";
    lines.forEach((line, i) => {
      const row = document.createElement("div"); row.className = "log-line";
      const id = document.createElement("span"); id.className = "line-id"; id.textContent = data.log_line_ids[i];
      const text = document.createElement("span"); text.textContent = line || "\u200b";
      row.append(id, text); byId("masked-log").append(row);
    });
    for (const [key, label] of Object.entries(fields)) entry(byId("context"), label, input.context[key]);
    data.warnings.forEach(value => { const item = document.createElement("li"); item.textContent = value; byId("warnings").append(item); });
    byId("preview").hidden = false; byId("empty").hidden = true;
  }
  form.addEventListener("input", () => { revision++; clearPreview(); error(""); counters(); byId("status").textContent = ""; });
  byId("clear").addEventListener("click", reset);
  form.addEventListener("submit", async event => {
    event.preventDefault();
    if (pending) return;
    clearPreview(); error(""); byId("status").textContent = "";
    const errors = [];
    if (missing(symptom.value)) errors.push("障害事象を入力してください。");
    if (count(symptom.value) > 2000) errors.push("障害事象は2,000文字以内にしてください。");
    if (count(log.value) > 20000) errors.push("ログは20,000文字以内に抜粋してください。");
    if (missing(log.value) && !logStatus.value) errors.push("ログがない場合はログ取得状況を選択してください。");
    if (errors.length) { error(errors.join("\n")); return; }
    const controller = new AbortController(); pending = controller;
    const current = ++revision;
    busy(true); byId("status").textContent = "マスキング後の内容を取得しています。";
    const timeout = setTimeout(() => controller.abort(), 60000);
    try {
      const response = await fetch("/api/previews", {
        method: "POST", headers: { "Content-Type": "application/json", "Accept": "application/json" },
        body: JSON.stringify({ symptom: symptom.value, log: log.value, log_status: logStatus.value || null }),
        cache: "no-store", credentials: "omit", signal: controller.signal
      });
      if (current !== revision) return;
      if (!response.ok) {
        // Never display raw response bodies, exception messages, or unknown server fields.
        error(response.status === 400 ? "入力内容を確認してください。障害事象・文字数・ログ取得状況を見直してください。" :
          response.status === 415 ? "送信形式を確認できませんでした。ページを再読み込みしてください。" :
          "プレビューを取得できませんでした。時間を置いて再度お試しください。");
        return;
      }
      const data = await response.json();
      if (current !== revision) return;
      render(data);
      byId("status").textContent = "プレビューを更新しました。AIには送信していません。";
    } catch {
      if (current === revision) {
        clearPreview(); error("プレビューを取得できませんでした。接続状態を確認して再度お試しください。");
      }
    } finally {
      clearTimeout(timeout);
      if (pending === controller) {
        pending = null; busy(false);
        if (byId("preview").hidden) byId("status").textContent = "";
      }
    }
  });
  window.addEventListener("pagehide", reset);
  window.addEventListener("pageshow", reset);
  reset();
})();
