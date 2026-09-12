"use strict";
(() => {
  const byId = id => document.getElementById(id);
  const form = byId("preview-form");
  const symptom = byId("symptom");
  const log = byId("log");
  const logStatus = byId("log-status");
  let pending = null;
  let revision = 0;
  let previewId = null;
  function discard(id) {
    if (id) fetch(`/api/previews/${encodeURIComponent(id)}`, {
      method: "DELETE", credentials: "same-origin", cache: "no-store", keepalive: true
    }).catch(() => {});
  }
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
    clearAnalysis();
    discard(previewId); previewId = null;
    byId("preview").hidden = true;
    byId("empty").hidden = false;
    for (const id of ["metadata", "masked-symptom", "masked-log", "context", "warnings"]) byId(id).replaceChildren();
  }
  function clearAnalysis() {
    byId("analyze").hidden = true;
    byId("analyze").disabled = true;
    byId("analysis-result").hidden = true;
    byId("result-content").replaceChildren();
    byId("analysis-status").textContent = "";
    byId("analysis-error").textContent = "";
    byId("analysis-error").hidden = true;
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
    if (typeof data.preview_id !== "string" || !/^[0-9a-f-]{36}$/.test(data.preview_id) ||
      typeof data.expires_at !== "string" || !Number.isFinite(Date.parse(data.expires_at)) ||
      !input || !input.context || [input.symptom, input.log, input.log_status, data.destination, data.purpose,
      ...Object.keys(fields).map(key => input.context[key])].some(value => typeof value !== "string") ||
      !Array.isArray(data.warnings) || data.warnings.some(value => typeof value !== "string") || !Array.isArray(data.log_line_ids)) throw new Error();
    const lines = input.log === "" ? [] : input.log.split(/\r\n|\r|\n/);
    if (lines.length !== data.log_line_ids.length || data.log_line_ids.some((id, i) => id !== `log:L${i + 1}`)) throw new Error();
    entry(byId("metadata"), "送信先", data.destination);
    previewId = data.preview_id;
    entry(byId("metadata"), "有効期限", new Date(data.expires_at).toLocaleString("ja-JP"));
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
    byId("analyze").hidden = false; byId("analyze").disabled = false;
  }
  function renderAnalysis(result) {
    const states = { hypotheses_available: "原因候補あり（未確認）", insufficient_information: "情報不足：原因候補の提示を保留", out_of_scope: "対象外：このPoCの分析範囲外" };
    if (!result || !Object.hasOwn(states, result.assessment_status)) throw new Error();
    // Build off-screen so a malformed success response cannot leave partial results.
    const content = document.createDocumentFragment();
    const text = value => { if (typeof value !== "string") throw new Error(); return value; };
    const values = list => { if (!Array.isArray(list)) throw new Error(); return list.map(text).join("、") || "なし"; };
    function section(title) {
      const section = document.createElement("section");
      const heading = document.createElement("h3"); heading.textContent = title;
      section.append(heading); content.append(section); return section;
    }
    function details(parent, pairs) {
      const dl = document.createElement("dl");
      pairs.forEach(([label, value]) => entry(dl, label, text(value)));
      parent.append(dl);
    }
    function items(title, list, renderItem, empty = "なし") {
      if (!Array.isArray(list)) throw new Error();
      const parent = section(title);
      if (!list.length) { const p = document.createElement("p"); p.textContent = empty; parent.append(p); }
      list.forEach(item => renderItem(parent, item));
    }
    details(section("判断状態"), [["状態", states[result.assessment_status]], ["理由", result.assessment_reason]]);
    details(section("状況要約"), [["要約", result.summary]]);
    items("事実（入力に記載された内容）", result.facts, (p, f) => details(p, [["事実ID", f.id], ["内容", f.statement], ["情報源", f.source_type === "log" ? "ログ記載" : "利用者の申告"], ["根拠参照", f.source_ref]]));
    items("原因候補（未確認の仮説）", result.hypotheses, (p, h) => details(p, [["候補ID", h.id], ["候補", h.description], ["根拠の事実ID", values(h.evidence_fact_ids)], ["未確認の前提", values(h.unverified_assumptions)], ["確認事項ID", values(h.check_ids)]]), result.assessment_status === "out_of_scope" ? "対象外のため提示しません。" : "情報不足のため提示を保留しています。");
    const priorities = { high: "高", medium: "中", low: "低" };
    items("確認事項（未実施の提案）", result.checks, (p, c) => details(p, [["確認ID", c.id], ["確認内容", c.action], ["目的", c.purpose], ["優先度", priorities[c.priority]]]));
    items("追加で必要な情報", result.missing_information, (p, m) => details(p, [["項目", m.item], ["必要な理由", m.reason]]));
    const e = result.escalation;
    details(section("エスカレーション情報"), [["要約", e.summary], ["発生日時", e.occurred_at], ["環境", e.environment], ["影響範囲", e.impact], ["継続状況", e.ongoing_status], ["引き継ぎ先", e.destination], ["関連する事実ID", values(e.related_fact_ids)], ["未確認の候補ID", values(e.hypothesis_ids)], ["実施済み確認", values(e.checks_performed)], ["未解決の確認事項", values(e.open_questions)]]);
    byId("result-content").replaceChildren(content);
    byId("analysis-result").hidden = false;
    byId("result-title").focus();
  }
  byId("analyze").addEventListener("click", async () => {
    if (pending || !previewId || byId("analyze").disabled) return;
    const id = previewId;
    // Consume locally before sending. Never automatically retry an uncertain execution.
    previewId = null;
    const current = ++revision;
    const controller = new AbortController(); pending = controller;
    byId("analyze").disabled = true;
    byId("submit").disabled = true; byId("inputs").disabled = true;
    error(""); byId("status").textContent = "";
    byId("analysis-status").textContent = "分析中です…";
    const messages = {
      400: "分析の入力上限または送信条件を満たしていません。入力を抜粋・確認してください。",
      410: "プレビューの有効期限が切れたか、利用できなくなりました。",
      409: "この分析は実行中、または実行済みです。重複実行はできません。",
      429: "同時実行数または利用・費用の上限に達しました。時間を置いてお試しください。",
      502: "分析結果の検証に失敗したため、結果を表示できません。",
      503: "分析サービスを利用できません。時間を置いてお試しください。",
      504: "分析が制限時間内に完了しませんでした。"
    };
    function failed(message) {
      byId("analysis-error").textContent = `${message} 再実行する場合は「送信内容を確認」から新しいプレビューを作成してください。`;
      byId("analysis-error").hidden = false;
      byId("analysis-status").textContent = "分析できませんでした。";
    }
    // Allow the server's 60-second deadline to return its safe 504 response.
    const timeout = setTimeout(() => controller.abort(), 65000);
    try {
      const response = await fetch("/api/analyses", {
        method: "POST", headers: { "Content-Type": "application/json", "Accept": "application/json" },
        body: JSON.stringify({ preview_id: id, execution_id: crypto.randomUUID() }),
        credentials: "same-origin", cache: "no-store", signal: controller.signal
      });
      if (current !== revision) return;
      if (response.status !== 200) { failed(messages[response.status] || "分析できませんでした。"); return; }
      const data = await response.json();
      if (current !== revision) return;
      renderAnalysis(data.result);
      byId("analysis-status").textContent = "分析が完了しました。同じプレビューからは再実行できません。";
    } catch {
      if (current === revision) failed("通信が完了しないか、結果を安全に表示できませんでした。サーバー側では処理が完了している可能性があります。");
    } finally {
      clearTimeout(timeout);
      discard(id);
      if (pending === controller) { pending = null; busy(false); }
    }
  });
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
        cache: "no-store", credentials: "same-origin", signal: controller.signal
      });
      if (current !== revision) return;
      if (!response.ok) {
        // Never display raw response bodies, exception messages, or unknown server fields.
        error(response.status === 400 ? "入力内容を確認してください。障害事象・文字数・ログ取得状況を見直してください。" :
          response.status === 415 ? "送信形式を確認できませんでした。ページを再読み込みしてください。" :
          response.status === 429 ? "プレビューの保持上限に達しました。時間を置いて再度お試しください。" :
          "プレビューを取得できませんでした。時間を置いて再度お試しください。");
        return;
      }
      const data = await response.json();
      if (current !== revision) { discard(data.preview_id); return; }
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
