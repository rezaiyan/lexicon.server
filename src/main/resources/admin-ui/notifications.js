(() => {
  "use strict";
  const API = "/admin/notifications";
  const SESSION = "/admin/session";
  const $ = (id) => document.getElementById(id);
  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const fmtNum = (n) => Number(n ?? 0).toLocaleString();
  // The API writes Instants as epoch seconds and LocalDates as [year, month, day]
  const toDate = (v) => v == null ? null : new Date(typeof v === "number" ? v * 1000 : v);
  const fmtTime = (v) => v == null ? "—" : toDate(v).toLocaleString();
  const fmtDay = (v) => Array.isArray(v) ? v.map((n, i) => (i ? String(n).padStart(2, "0") : n)).join("-") : String(v ?? "—");

  let signedIn = false;
  let sessionTimer = null;

  function flash(msg, kind = "err", el = $("globalMsg")) {
    el.textContent = msg;
    el.className = "banner " + kind;
    if (kind !== "err") setTimeout(() => el.classList.add("hidden"), 6000);
  }
  function clearFlash() { $("globalMsg").classList.add("hidden"); }

  // Same-origin only: the session cookie is HttpOnly + SameSite=Strict and the custom header
  // is the server's second CSRF check. The admin key itself never touches browser storage.
  async function request(url, opts = {}) {
    const res = await fetch(url, {
      ...opts,
      credentials: "same-origin",
      cache: "no-store",
      headers: { "X-Admin-Request": "1", "Content-Type": "application/json", ...(opts.headers || {}) },
    });
    let json = null;
    try { json = await res.json(); } catch { /* non-JSON error */ }
    return { res, json };
  }

  async function api(path, opts = {}) {
    const { res, json } = await request(API + path, opts);
    if (res.status === 401 || res.status === 403) {
      showSignIn("Your session ended. Sign in again.");
      throw new Error("Not signed in.");
    }
    if (!res.ok || (json && json.success === false)) {
      throw new Error((json && (json.message || json.error)) || `Request failed (${res.status})`);
    }
    return json ? json.data : null;
  }

  async function withBusy(btn, fn) {
    const prev = btn.disabled; btn.disabled = true;
    try { return await fn(); } catch (e) { flash(e.message); } finally { btn.disabled = prev; }
  }

  // ── Tabs ──
  const tabs = document.querySelectorAll("nav [role=tab]");
  function showTab(name) {
    tabs.forEach((t) => t.setAttribute("aria-selected", String(t.dataset.tab === name)));
    document.querySelectorAll("[data-panel]").forEach((p) => p.classList.toggle("hidden", p.dataset.panel !== name));
    try { localStorage.setItem("lexicon.adminTab", name); } catch { /* ignore */ }
    if (name === "history" && !historyLoaded && signedIn) loadHistory(0).catch((e) => flash(e.message));
  }
  tabs.forEach((t) => t.addEventListener("click", () => showTab(t.dataset.tab)));

  // ── Session ──
  function showSignIn(msg) {
    signedIn = false;
    clearTimeout(sessionTimer);
    $("app").classList.add("hidden");
    $("sessionBar").classList.add("hidden");
    $("signIn").classList.remove("hidden");
    if (msg) flash(msg, "warn");
    $("adminKey").focus();
  }
  function showApp(expiresAt) {
    signedIn = true;
    $("signIn").classList.add("hidden");
    $("app").classList.remove("hidden");
    $("sessionBar").classList.remove("hidden");
    const exp = toDate(expiresAt);
    $("sessionInfo").textContent = `Signed in until ${exp.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
    clearTimeout(sessionTimer);
    sessionTimer = setTimeout(() => showSignIn("Your session expired. Sign in again."), Math.max(0, exp - Date.now()));
  }
  $("signInForm").addEventListener("submit", (e) => {
    e.preventDefault();
    const btn = e.submitter || $("signInForm").querySelector("button");
    withBusy(btn, async () => {
      const key = $("adminKey").value;
      $("adminKey").value = "";
      const { res, json } = await request(SESSION, { method: "POST", body: JSON.stringify({ key }) });
      if (!res.ok || !json || !json.success) throw new Error((json && json.message) || `Sign-in failed (${res.status})`);
      clearFlash();
      showApp(json.data.expiresAt);
      await loadAll();
    });
  });
  $("signOut").addEventListener("click", async () => {
    try { await request(SESSION, { method: "DELETE" }); } catch { /* cookie is cleared server-side */ }
    showSignIn("Signed out.");
  });

  // ── Overview ──
  const stat = (label, value, sub) =>
    `<div class="stat"><div class="label">${esc(label)}</div><div class="value">${esc(value)}</div>${sub ? `<div class="small muted">${esc(sub)}</div>` : ""}</div>`;

  async function loadOverview() {
    const [aud, stats] = await Promise.all([api("/audience"), api("/stats")]);
    const plat = Object.entries(aud.activeTokensByPlatform || {}).map(([k, v]) => `${k} ${fmtNum(v)}`).join(" · ");
    $("audienceStats").innerHTML =
      stat("Reachable users", fmtNum(aud.reachableUsers), "active account + active device") +
      stat("Opted out", fmtNum(aud.optedOutUsers), "of reachable users") +
      stat("Active devices", fmtNum(aud.activeTokens), plat || "none") +
      stat("Smart schedules", fmtNum(stats.totalActiveSchedules));
    const w = stats.last7Days;
    $("weekStats").innerHTML =
      stat("Sent", fmtNum(w.totalSent)) + stat("Opened", fmtNum(w.totalOpened)) + stat("Open rate", w.openRatePercent + "%");
    const rows = Object.entries(w.typeBreakdown || {}).sort((a, b) => b[1].sent - a[1].sent);
    $("typeTable").innerHTML = rows.length ? rows.map(([type, s]) =>
      `<tr><td>${esc(type)}</td><td class="num">${fmtNum(s.sent)}</td><td class="num">${fmtNum(s.opened)}</td>
       <td class="num">${s.openRate}%</td><td class="w30"><div class="bar"><span data-width="${Math.min(100, Math.max(0, Number(s.openRate) || 0))}"></span></div></td></tr>`
    ).join("") : `<tr><td colspan="5" class="muted">No notifications in the last 7 days.</td></tr>`;
    // CSP forbids inline style attributes; set widths through the CSSOM instead
    $("typeTable").querySelectorAll("[data-width]").forEach((el) => { el.style.width = el.dataset.width + "%"; });
    const sp = stats.suppressedUsers;
    $("suppressionStats").innerHTML =
      stat("3-day backoff", fmtNum(sp["3day"]), "3–5 ignores") + stat("7-day backoff", fmtNum(sp["7day"]), "6–9 ignores") +
      stat("14-day backoff", fmtNum(sp["14day"]), "10–14 ignores") + stat("30-day backoff", fmtNum(sp["30day"]), "15+ ignores");
  }
  $("refreshOverview").addEventListener("click", (e) => withBusy(e.currentTarget, loadOverview));

  // ── Send ──
  let audience = "USERS";
  document.querySelectorAll("#audienceSeg button").forEach((b) => b.addEventListener("click", () => {
    audience = b.dataset.audience;
    document.querySelectorAll("#audienceSeg button").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
    $("targetsBox").classList.toggle("hidden", audience === "ALL");
  }));
  function addDataRow(k = "", v = "") {
    const row = document.createElement("div");
    row.className = "data-row";
    row.innerHTML = `<input placeholder="key" class="dk"><input placeholder="value" class="dv"><button type="button" aria-label="Remove">✕</button>`;
    row.querySelector(".dk").value = k; row.querySelector(".dv").value = v;
    row.querySelector("button").addEventListener("click", () => row.remove());
    $("dataRows").appendChild(row);
  }
  $("addData").addEventListener("click", () => addDataRow());

  // Cheat sheet: set a key in the data payload, reusing its row if present or an empty one
  function setDataKey(key, value) {
    const rows = [...document.querySelectorAll("#dataRows .data-row")];
    const row = rows.find((r) => r.querySelector(".dk").value.trim() === key)
      || rows.find((r) => !r.querySelector(".dk").value.trim() && !r.querySelector(".dv").value.trim());
    if (row) {
      row.querySelector(".dk").value = key;
      row.querySelector(".dv").value = value;
      return row;
    }
    addDataRow(key, value);
    return $("dataRows").lastElementChild;
  }
  const PRESETS = {
    review: { type: "review_reminder" },
    add: { type: "announcement", deep_link: "vokab://words/add" },
    insights: { type: "announcement", deep_link: "vokab://insights" },
    plain: { type: "announcement" },
  };
  $("cheatsheet").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-use-key], button[data-preset]");
    if (!btn) return;
    let row;
    if (btn.dataset.preset) {
      document.querySelectorAll("#dataRows .data-row").forEach((r) => {
        if (r.querySelector(".dk").value.trim() === "deep_link") r.remove();
      });
      Object.entries(PRESETS[btn.dataset.preset]).forEach(([k, v]) => { row = setDataKey(k, v); });
    } else {
      row = setDataKey(btn.dataset.useKey, btn.dataset.useValue);
    }
    $("dataRows").scrollIntoView({ behavior: "smooth", block: "center" });
    const input = row && row.querySelector(".dv");
    if (input) { input.focus(); input.setSelectionRange(input.value.length, input.value.length); }
    flash(`Data payload updated: ${btn.dataset.useKey || btn.dataset.preset}`, "ok");
  });
  function updatePreview() {
    $("pvTitle").textContent = $("title").value || "Title";
    $("pvBody").textContent = $("body").value || "Body";
    $("bodyCount").textContent = $("body").value.length;
  }
  $("title").addEventListener("input", updatePreview);
  $("body").addEventListener("input", updatePreview);

  function buildRequest(dryRun) {
    const title = $("title").value.trim(), body = $("body").value.trim();
    if (!title || !body) throw new Error("Title and body are required.");
    const data = {};
    document.querySelectorAll("#dataRows .data-row").forEach((r) => {
      const k = r.querySelector(".dk").value.trim();
      if (k) data[k] = r.querySelector(".dv").value;
    });
    const req = { audience, title, body, data, respectOptOut: $("respectOptOut").checked, dryRun,
      imageUrl: $("imageUrl").value.trim() || null, userIds: [], emails: [] };
    if (audience === "USERS") {
      const tokens = $("targets").value.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean);
      for (const t of tokens) {
        if (/^\d+$/.test(t)) req.userIds.push(Number(t));
        else if (t.includes("@")) req.emails.push(t);
        else throw new Error(`"${t}" is neither a user id nor an email.`);
      }
      if (!req.userIds.length && !req.emails.length) throw new Error("Add at least one user id or email.");
    }
    return req;
  }

  const outcomePill = (o) => {
    const cls = { SENT: "ok", WOULD_SEND: "ok", FAILED: "err", NOT_FOUND: "err", NO_TOKENS: "warn", OPTED_OUT: "warn" }[o] || "";
    return `<span class="pill ${cls}">${esc(o.replace("_", " ").toLowerCase())}</span>`;
  };
  function renderSendResult(r) {
    $("sendResultCard").classList.remove("hidden");
    $("sendResultTitle").textContent = r.dryRun ? "Dry run — nothing was sent" : "Sent";
    $("sendResultStats").innerHTML =
      stat("Targeted", fmtNum(r.targeted)) + stat(r.dryRun ? "Would receive" : "Delivered", fmtNum(r.sent)) +
      stat("Failed", fmtNum(r.failed)) + stat("Skipped", fmtNum(r.skipped), "no device, opted out, unknown");
    $("sendResultTable").innerHTML = r.recipients.length ? r.recipients.map((x) =>
      `<tr><td>${esc(x.target)}</td><td>${x.userId != null ? `<a href="#" data-user="${esc(x.userId)}">${esc(x.userId)}</a>` : "—"}</td>
       <td>${outcomePill(x.outcome)}</td><td class="num">${x.devicesReached || ""}</td><td class="muted">${esc(x.error || "")}</td></tr>`
    ).join("") : `<tr><td colspan="5" class="muted">Nobody to send to.</td></tr>`;
    $("sendResultTrunc").classList.toggle("hidden", !r.recipientsTruncated);
    $("sendResultCard").scrollIntoView({ behavior: "smooth", block: "start" });
  }

  $("dryRunBtn").addEventListener("click", (e) => withBusy(e.currentTarget, async () => {
    renderSendResult(await api("/send", { method: "POST", body: JSON.stringify(buildRequest(true)) }));
  }));
  $("sendForm").addEventListener("submit", (e) => {
    e.preventDefault();
    withBusy($("sendBtn"), async () => {
      const req = buildRequest(false);
      // Always show the real recipient count before a send goes out
      const preview = await api("/send", { method: "POST", body: JSON.stringify({ ...req, dryRun: true }) });
      if (preview.sent === 0) { renderSendResult(preview); throw new Error("No recipient can receive this push."); }
      const who = audience === "ALL" ? "EVERY reachable user" : "the selected users";
      if (!confirm(`Send "${req.title}" to ${who}?\n\n${preview.sent} user(s) will receive it, ${preview.skipped} skipped.`)) return;
      const result = await api("/send", { method: "POST", body: JSON.stringify(req) });
      renderSendResult(result);
      flash(`Push delivered to ${result.sent} user(s)${result.failed ? `, ${result.failed} failed` : ""}.`, result.failed ? "warn" : "ok");
    });
  });

  // ── History ──
  let historyPage = 0, historyLoaded = false;
  async function loadTypes() {
    const types = await api("/logs/types");
    const sel = $("hType"), cur = sel.value;
    sel.innerHTML = `<option value="">Any</option>` + types.map((t) => `<option>${esc(t)}</option>`).join("");
    sel.value = cur;
  }
  const opened = (iso) => iso ? `<span class="pill ok" title="${esc(fmtTime(iso))}">opened</span>` : `<span class="muted">—</span>`;
  const msgCell = (l) => `<div><strong>${esc(l.title || "")}</strong></div><div class="muted">${esc(l.body || "")}</div>`;
  async function loadHistory(page) {
    const params = new URLSearchParams({ page, size: $("hSize").value });
    const uid = $("hUser").value.trim();
    if (uid) params.set("userId", uid);
    if ($("hType").value) params.set("type", $("hType").value);
    const r = await api("/logs?" + params);
    historyPage = r.page; historyLoaded = true;
    $("historyTable").innerHTML = r.items.length ? r.items.map((l) =>
      `<tr><td class="nowrap">${esc(fmtTime(l.sentAt))}</td>
       <td><a href="#" data-user="${esc(l.userId)}">${esc(l.userId)}</a></td>
       <td><span class="pill">${esc(l.notificationType)}</span></td><td>${msgCell(l)}</td><td>${opened(l.openedAt)}</td></tr>`
    ).join("") : `<tr><td colspan="5" class="muted">No notifications match.</td></tr>`;
    $("hPageInfo").textContent = `Page ${r.totalPages ? r.page + 1 : 0} of ${r.totalPages} · ${fmtNum(r.totalItems)} total`;
    $("hPrev").disabled = r.page <= 0;
    $("hNext").disabled = r.page + 1 >= r.totalPages;
  }
  $("historyForm").addEventListener("submit", (e) => { e.preventDefault(); withBusy(e.submitter || $("hPrev"), () => loadHistory(0)); });
  $("hPrev").addEventListener("click", (e) => withBusy(e.currentTarget, () => loadHistory(historyPage - 1)));
  $("hNext").addEventListener("click", (e) => withBusy(e.currentTarget, () => loadHistory(historyPage + 1)));

  // ── User ──
  let currentUser = null;
  function renderUser(u) {
    currentUser = u;
    $("userDetail").classList.remove("hidden");
    $("uHeading").textContent = `${u.name} · #${u.userId} · ${u.email}`;
    const activeTokens = u.tokens.filter((t) => t.active).length;
    const rate = u.last30DaysSent ? Math.round((u.last30DaysOpened / u.last30DaysSent) * 100) : 0;
    $("uStats").innerHTML =
      stat("Account", u.active ? "active" : "inactive") + stat("Active devices", fmtNum(activeTokens)) +
      stat("Sent (30d)", fmtNum(u.last30DaysSent)) + stat("Open rate (30d)", rate + "%") + stat("Streak", fmtNum(u.currentStreak));
    const s = u.settings, sc = u.schedule;
    const kv = [
      ["Notifications", s ? (s.notificationsEnabled ? "on" : "off") : "no settings row"],
      ["Frequency", s ? s.notificationFrequency : "—"],
      ["Review reminders", s ? (s.reviewRemindersEnabled ? "on" : "off") : "—"],
      ["Timezone", s && s.timezone ? s.timezone : "—"],
      ["Send hour (UTC)", sc ? `${sc.optimalSendHour}:00 (offset ${sc.timezoneOffsetHrs >= 0 ? "+" : ""}${sc.timezoneOffsetHrs}h)` : "no schedule yet"],
      ["Segment", sc ? sc.engagementSegment : "—"],
      ["Consecutive ignores", sc ? sc.consecutiveIgnores : "—"],
      ["Suppressed until", sc && sc.suppressedUntil ? fmtDay(sc.suppressedUntil) : "not suppressed"],
      ["Last sent", sc && sc.lastSentDate ? `${fmtDay(sc.lastSentDate)} (${sc.lastSentType || "?"})` : "—"],
      ["AI advice", sc && sc.aiAction ? `${sc.aiAction}${sc.aiIntervalDays ? `, every ${sc.aiIntervalDays}d` : ""}${sc.aiContentHint ? ` — ${sc.aiContentHint}` : ""}` : "—"],
      ["Last login", fmtTime(u.lastLoginAt)],
    ];
    $("uKv").innerHTML = kv.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join("");
    $("uClear").disabled = !(sc && (sc.suppressedUntil || sc.consecutiveIgnores));
    $("uTokens").innerHTML = u.tokens.length ? u.tokens.map((t) =>
      `<tr><td>${esc(t.platform)}</td><td>${t.active ? '<span class="pill ok">active</span>' : '<span class="pill">inactive</span>'}</td>
       <td class="mono">${esc(t.tokenPreview)}</td><td class="mono">${esc(t.deviceId || "—")}</td><td>${esc(fmtTime(t.updatedAt))}</td></tr>`
    ).join("") : `<tr><td colspan="5" class="muted">No devices registered.</td></tr>`;
    $("uLogs").innerHTML = u.recentLogs.length ? u.recentLogs.map((l) =>
      `<tr><td class="nowrap">${esc(fmtTime(l.sentAt))}</td><td><span class="pill">${esc(l.notificationType)}</span></td>
       <td>${msgCell(l)}</td><td>${opened(l.openedAt)}</td></tr>`
    ).join("") : `<tr><td colspan="4" class="muted">Nothing sent in the last 30 days.</td></tr>`;
  }
  async function lookupUser(q) {
    renderUser(await api("/users/lookup?query=" + encodeURIComponent(q)));
  }
  $("userForm").addEventListener("submit", (e) => { e.preventDefault(); withBusy(e.submitter || $("uClear"), () => lookupUser($("uQuery").value)); });
  $("uClear").addEventListener("click", (e) => {
    if (!currentUser || !confirm(`Clear backoff for user #${currentUser.userId}? The smart dispatcher will consider them again from their next send hour.`)) return;
    withBusy(e.currentTarget, async () => {
      renderUser(await api(`/users/${currentUser.userId}/clear-suppression`, { method: "POST" }));
      flash("Suppression cleared.", "ok");
    });
  });
  $("uTestPush").addEventListener("click", () => {
    if (!currentUser) return;
    document.querySelector('#audienceSeg [data-audience="USERS"]').click();
    $("targets").value = String(currentUser.userId);
    showTab("send");
    $("title").focus();
  });
  $("uHistory").addEventListener("click", () => {
    if (!currentUser) return;
    $("hUser").value = currentUser.userId; $("hType").value = "";
    showTab("history");
    withBusy($("hPrev"), () => loadHistory(0));
  });
  document.addEventListener("click", (e) => {
    const a = e.target.closest("a[data-user]");
    if (!a) return;
    e.preventDefault();
    $("uQuery").value = a.dataset.user;
    showTab("user");
    withBusy($("uClear"), () => lookupUser(a.dataset.user));
  });

  // ── Jobs ──
  const JOBS = [
    ["SMART_DISPATCH", "every 30 min", "Sends the daily smart notification to users whose send hour is now."],
    ["STREAK_SAVERS", "every 30 min", "Evening streak-at-risk push for streak holders who haven't studied today."],
    ["REVIEW_REMINDERS", "every 30 min", "Review reminders for users who turned smart notifications off."],
    ["REFRESH_SCHEDULES", "00:30 UTC", "Recomputes each user's best send hour and timezone offset."],
    ["REFRESH_SEGMENTS", "01:00 UTC", "Recomputes engagement segments and AI advice for cold users."],
  ];
  $("jobsTable").innerHTML = JOBS.map(([id, when, what]) =>
    `<tr><td class="mono">${id}</td><td class="nowrap">${when}</td><td>${what}</td>
     <td><button data-job="${id}">Run now</button></td></tr>`).join("");
  $("jobsTable").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-job]");
    if (!b || !confirm(`Run ${b.dataset.job} now?`)) return;
    withBusy(b, async () => {
      const r = await api(`/jobs/${b.dataset.job}/run`, { method: "POST" });
      flash(`${r.job} finished in ${fmtNum(r.durationMs)} ms. Check the server log for its counts.`, "ok", $("jobMsg"));
    });
  });

  // ── Boot ──
  async function loadAll() {
    try {
      await Promise.all([loadOverview(), loadTypes()]);
      if (!document.querySelector('[data-panel="history"]').classList.contains("hidden")) await loadHistory(0);
      else historyLoaded = false;
    } catch (e) { if (signedIn) flash(e.message); }
  }
  let startTab = "overview";
  try { startTab = localStorage.getItem("lexicon.adminTab") || "overview"; } catch { /* ignore */ }
  if (![...tabs].some((t) => t.dataset.tab === startTab)) startTab = "overview";
  updatePreview();
  addDataRow();
  (async () => {
    try {
      const { json } = await request(SESSION);
      if (json && json.success && json.data.authenticated) {
        showApp(json.data.expiresAt);
        showTab(startTab);
        await loadAll();
        return;
      }
    } catch { /* fall through to sign-in */ }
    showTab(startTab);
    showSignIn();
  })();
})();
