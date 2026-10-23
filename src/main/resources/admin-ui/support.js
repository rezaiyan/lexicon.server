(() => {
  "use strict";
  const API = "/admin/support";
  const SESSION = "/admin/session";
  const REFRESH_MS = 20000;
  const $ = (id) => document.getElementById(id);
  const esc = (v) =>
    String(v ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
  // Support DTOs carry epoch millis; session expiry is epoch seconds
  const fmtMillis = (ms) => new Date(ms).toLocaleString();

  let signedIn = false;
  let sessionTimer = null;
  let refreshTimer = null;
  let filter = "OPEN";
  let threads = [];
  let current = null;

  function flash(msg, kind = "err") {
    const el = $("globalMsg");
    el.textContent = msg;
    el.className = "banner " + kind;
    if (kind !== "err") setTimeout(() => el.classList.add("hidden"), 6000);
  }

  // Same-origin only: HttpOnly SameSite=Strict session cookie plus the custom CSRF header.
  async function request(url, opts = {}) {
    const res = await fetch(url, {
      ...opts,
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "X-Admin-Request": "1",
        "Content-Type": "application/json",
        ...(opts.headers || {}),
      },
    });
    let json = null;
    try {
      json = await res.json();
    } catch {
      /* non-JSON error */
    }
    return { res, json };
  }

  async function api(path, opts = {}) {
    const { res, json } = await request(API + path, opts);
    if (res.status === 401 || res.status === 403) {
      showSignIn("Your session ended. Sign in again.");
      throw new Error("Not signed in.");
    }
    if (!res.ok || (json && json.success === false)) {
      throw new Error(
        (json && (json.message || json.error)) ||
          `Request failed (${res.status})`,
      );
    }
    return json ? json.data : null;
  }

  async function withBusy(btn, fn) {
    const prev = btn.disabled;
    btn.disabled = true;
    try {
      return await fn();
    } catch (e) {
      flash(e.message);
    } finally {
      btn.disabled = prev;
    }
  }

  // ── Session ──
  function showSignIn(msg) {
    signedIn = false;
    clearTimeout(sessionTimer);
    clearInterval(refreshTimer);
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
    const exp = new Date(typeof expiresAt === "number" ? expiresAt * 1000 : expiresAt);
    $("sessionInfo").textContent =
      `Signed in until ${exp.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
    clearTimeout(sessionTimer);
    sessionTimer = setTimeout(
      () => showSignIn("Your session expired. Sign in again."),
      Math.max(0, exp - Date.now()),
    );
    clearInterval(refreshTimer);
    refreshTimer = setInterval(
      () =>
        refresh().catch(() => {
          /* next tick retries */
        }),
      REFRESH_MS,
    );
  }
  $("signInForm").addEventListener("submit", (e) => {
    e.preventDefault();
    const btn = e.submitter || $("signInForm").querySelector("button");
    withBusy(btn, async () => {
      const key = $("adminKey").value;
      $("adminKey").value = "";
      const { res, json } = await request(SESSION, {
        method: "POST",
        body: JSON.stringify({ key }),
      });
      if (!res.ok || !json || !json.success)
        throw new Error(
          (json && json.message) || `Sign-in failed (${res.status})`,
        );
      $("globalMsg").classList.add("hidden");
      showApp(json.data.expiresAt);
      await refresh();
    });
  });
  $("signOut").addEventListener("click", async () => {
    try {
      await request(SESSION, { method: "DELETE" });
    } catch {
      /* cookie is cleared server-side */
    }
    showSignIn("Signed out.");
  });

  // ── Threads ──
  document.querySelectorAll("[data-filter]").forEach((b) =>
    b.addEventListener("click", () => {
      filter = b.dataset.filter;
      document
        .querySelectorAll("[data-filter]")
        .forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
      renderThreads();
    }),
  );

  function renderThreads() {
    const shown = threads.filter(
      (t) => filter === "ALL" || t.status === "OPEN",
    );
    $("noThreads").classList.toggle("hidden", shown.length > 0);
    $("threads").innerHTML = shown
      .map((t) => {
        const waiting = t.lastSender === "USER" && t.status === "OPEN";
        return `<li><button type="button" data-id="${t.id}" aria-current="${current === t.id}">
        <div class="row between"><span class="who grow">${esc(t.userLabel)}</span>
          ${waiting ? '<span class="pill warn fixed">awaiting reply</span>' : ""}
          ${t.status === "CLOSED" ? '<span class="pill fixed">closed</span>' : ""}</div>
        <div class="snip">${esc(t.lastMessagePreview || "")}</div>
        <div class="small muted">#${t.id} · ${esc(fmtMillis(t.lastMessageAt))}</div>
      </button></li>`;
      })
      .join("");
    $("threads")
      .querySelectorAll("button[data-id]")
      .forEach((b) =>
        b.addEventListener("click", () =>
          openThread(Number(b.dataset.id)).catch((e) => flash(e.message)),
        ),
      );
  }

  async function openThread(id) {
    current = id;
    const detail = await api(`/threads/${id}`);
    const t = detail.thread;
    $("emptyThread").classList.add("hidden");
    $("thread").classList.remove("hidden");
    $("threadTitle").textContent = `${t.userLabel}`;
    $("threadMeta").textContent =
      `Thread #${t.id} · user ${t.userId} · ${t.status.toLowerCase()}`;
    $("toggleStatus").textContent =
      t.status === "OPEN" ? "Close thread" : "Reopen thread";
    $("toggleStatus").dataset.next = t.status === "OPEN" ? "CLOSED" : "OPEN";
    const chat = $("chat");
    const atBottom =
      chat.scrollHeight - chat.scrollTop - chat.clientHeight < 40;
    chat.innerHTML = detail.messages
      .map(
        (m) =>
          `<div class="bubble ${m.sender === "ADMIN" ? "admin" : "user"}">${esc(m.body)}<time>${esc(fmtMillis(m.createdAt))}</time></div>`,
      )
      .join("");
    if (atBottom || chat.dataset.thread !== String(id))
      chat.scrollTop = chat.scrollHeight;
    chat.dataset.thread = String(id);
    renderThreads();
  }

  async function refresh() {
    threads = await api("/threads");
    renderThreads();
    if (current != null) await openThread(current);
  }

  $("replyForm").addEventListener("submit", (e) => {
    e.preventDefault();
    if (current == null) return;
    const btn = e.submitter || $("replyForm").querySelector("button");
    withBusy(btn, async () => {
      const body = $("replyBody").value.trim();
      if (!body) return;
      await api(`/threads/${current}/messages`, {
        method: "POST",
        body: JSON.stringify({ body }),
      });
      $("replyBody").value = "";
      await refresh();
      $("chat").scrollTop = $("chat").scrollHeight;
    });
  });

  $("toggleStatus").addEventListener("click", (e) =>
    withBusy(e.currentTarget, async () => {
      await api(`/threads/${current}/status`, {
        method: "POST",
        body: JSON.stringify({ status: $("toggleStatus").dataset.next }),
      });
      await refresh();
    }),
  );

  // ── Boot ──
  (async () => {
    try {
      const { json } = await request(SESSION);
      if (json && json.success && json.data.authenticated) {
        showApp(json.data.expiresAt);
        await refresh();
        return;
      }
    } catch {
      /* fall through to sign-in */
    }
    showSignIn();
  })();
})();
