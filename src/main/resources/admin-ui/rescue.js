(() => {
  "use strict";
  const API = "/admin/rescue";
  const SESSION = "/admin/session";
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
  const pct = (rate) => (rate == null ? "–" : `${Math.round(rate * 100)}%`);
  const ratio = (n, of) =>
    of ? ` <span class="muted small">${pct(n / of)}</span>` : "";
  const fmtTime = (iso) => new Date(iso).toLocaleString();

  let sessionTimer = null;
  let previews = [];
  let show = "ALL";

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
      json = await res.clone().json();
    } catch {
      /* non-JSON body */
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
    clearTimeout(sessionTimer);
    $("app").classList.add("hidden");
    $("sessionBar").classList.add("hidden");
    $("signIn").classList.remove("hidden");
    if (msg) flash(msg, "warn");
    $("adminKey").focus();
  }
  function showApp(expiresAt) {
    $("signIn").classList.add("hidden");
    $("app").classList.remove("hidden");
    $("sessionBar").classList.remove("hidden");
    const exp = new Date(
      typeof expiresAt === "number" ? expiresAt * 1000 : expiresAt,
    );
    $("sessionInfo").textContent =
      `Signed in until ${exp.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
    clearTimeout(sessionTimer);
    sessionTimer = setTimeout(
      () => showSignIn("Your session expired. Sign in again."),
      Math.max(0, exp - Date.now()),
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

  // ── Taste score ──
  function renderTaste(t) {
    $("tasteRate").textContent = pct(t.upRate);
    $("tasteRated").textContent = String(t.rated);
    $("tasteAllRate").textContent = pct(t.allUpRate);
    $("tasteTarget").textContent = pct(t.target);
    $("tasteBar").style.width = `${Math.round((t.upRate || 0) * 100)}%`;
    const verdict = $("tasteVerdict");
    if (t.upRate == null) {
      verdict.textContent = "nothing rated yet";
      verdict.className = "pill fixed";
    } else if (t.upRate >= t.target) {
      verdict.textContent = "target met: sends can be enabled";
      verdict.className = "pill ok fixed";
    } else {
      verdict.textContent = "below target: keep sends off";
      verdict.className = "pill warn fixed";
    }
  }

  // ── Previews ──
  document.querySelectorAll("[data-show]").forEach((b) =>
    b.addEventListener("click", () => {
      show = b.dataset.show;
      document
        .querySelectorAll("[data-show]")
        .forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
      renderPreviews();
    }),
  );

  const sourcePill = (source) =>
    `<span class="pill ${source === "AI" ? "ok" : "warn"}">${esc(source)}</span>`;

  function candidateRow(p, c) {
    const rateBtn = (rating, label) =>
      `<button type="button" data-preview="${esc(p.id)}" data-index="${esc(c.index)}" data-rating="${rating}"
        aria-pressed="${c.rating === rating}" aria-label="${rating === "UP" ? "Like" : "Dislike"} candidate ${esc(c.index + 1)}">${label}</button>`;
    return `<tr>
      <td class="nowrap">${esc(c.index + 1)} ${c.chosen ? '<span class="pill ok">chosen</span>' : ""}
        <div class="small muted">${esc(c.angle)} · rank ${esc(c.rank)}</div></td>
      <td><div class="preview"><div class="t">${esc(c.title)}</div><div class="b">${esc(c.body)}</div></div>
        <div class="small muted mt12">facts ${esc(c.factIds.join(", ") || "none")}</div></td>
      <td>${
        c.violations.length
          ? `<ul class="violations">${c.violations.map((v) => `<li>${esc(v)}</li>`).join("")}</ul>`
          : '<span class="pill ok">passes</span>'
      }</td>
      <td class="rate"><div class="seg">${rateBtn("UP", "👍")}${rateBtn("DOWN", "👎")}</div>
        <input type="text" maxlength="500" placeholder="Note (saved with the rating)"
          data-note="${esc(p.id)}-${esc(c.index)}" value="${esc(c.note || "")}" class="mt12"></td>
    </tr>`;
  }

  function previewCard(p) {
    const outros = [
      p.outroWin
        ? `<div><span class="muted small">Outro, all right:</span> ${esc(p.outroWin)}</div>`
        : "",
      p.outroSoft
        ? `<div><span class="muted small">Outro, a miss:</span> ${esc(p.outroSoft)}</div>`
        : "",
    ].join("");
    const candidates = p.candidates.length
      ? `<div class="table-wrap"><table>
          <thead><tr><th>#</th><th>Copy</th><th>Linter</th><th>Rating</th></tr></thead>
          <tbody>${p.candidates.map((c) => candidateRow(p, c)).join("")}</tbody></table></div>`
      : '<p class="muted small">No AI candidates (template copy).</p>';
    return `<article class="rescue-preview">
      <div class="row between mb12">
        <div class="grow"><b>Preview #${esc(p.id)}</b> · user ${esc(p.userId)} · ${sourcePill(p.source)}
          <span class="small muted">${esc(p.angle)} · ${esc(p.wordIds.length)} words · ${esc(p.promptVersion)} · ${esc(fmtTime(p.createdAt))}</span></div>
      </div>
      <div class="row mb12">
        <div class="preview fixed"><div class="app">Lexicon</div><div class="t">${esc(p.title)}</div><div class="b">${esc(p.body)}</div></div>
        <div class="grow small">${outros || '<span class="muted">No outros (shown only when they pass the tone rules).</span>'}</div>
      </div>
      ${candidates}
    </article>`;
  }

  function renderPreviews() {
    const shown = previews.filter(
      (p) =>
        show === "ALL" ||
        p.candidates.some((c) => c.chosen && c.rating == null),
    );
    $("noPreviews").classList.toggle("hidden", shown.length > 0);
    $("previews").innerHTML = shown.map(previewCard).join("");
  }

  $("previews").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-rating]");
    if (!btn) return;
    const previewId = Number(btn.dataset.preview);
    const index = Number(btn.dataset.index);
    const noteInput = $("previews").querySelector(
      `input[data-note="${previewId}-${index}"]`,
    );
    const note = noteInput ? noteInput.value.trim() : "";
    withBusy(btn, async () => {
      const taste = await api(`/previews/${previewId}/ratings`, {
        method: "POST",
        body: JSON.stringify({
          candidateIndex: index,
          rating: btn.dataset.rating,
          note: note || null,
        }),
      });
      const candidate = previews
        .find((p) => p.id === previewId)
        ?.candidates.find((c) => c.index === index);
      if (candidate) {
        candidate.rating = btn.dataset.rating;
        candidate.note = note || null;
      }
      renderTaste(taste);
      renderPreviews();
    });
  });

  $("generateForm").addEventListener("submit", (e) => {
    e.preventDefault();
    const btn =
      e.submitter || $("generateForm").querySelector("button[type=submit]");
    const count = Number($("previewCount").value);
    withBusy(btn, async () => {
      flash(
        `Drafting copy for up to ${count} learners. This takes a while…`,
        "warn",
      );
      const generated = await api(
        `/previews?count=${encodeURIComponent(count)}`,
        { method: "POST" },
      );
      flash(
        generated.length
          ? `${generated.length} new preview${generated.length === 1 ? "" : "s"}.`
          : "No learner had enough usable words for a preview.",
        generated.length ? "ok" : "warn",
      );
      await loadPreviews();
    });
  });

  $("exportEval").addEventListener("click", (e) =>
    withBusy(e.currentTarget, async () => {
      const { res } = await request(`${API}/eval.jsonl`);
      if (!res.ok) throw new Error(`Export failed (${res.status})`);
      const url = URL.createObjectURL(await res.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = `rescue-eval-${new Date().toISOString().slice(0, 10)}.jsonl`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }),
  );

  // ── Plans & funnel ──
  function renderPlans(plans) {
    $("noPlans").classList.toggle("hidden", plans.length > 0);
    $("plans").innerHTML = plans
      .map(
        (r) => `<tr>
        <td class="nowrap">${esc(r.plannedFor)}</td><td>${esc(r.userId)}</td>
        <td><span class="pill ${r.status === "COMPLETED" ? "ok" : ""}">${esc(r.status.toLowerCase())}</span></td>
        <td>${sourcePill(r.source)}</td><td>${esc(r.angle)}</td>
        <td><b>${esc(r.title)}</b><div class="small">${esc(r.body)}</div></td>
        <td class="num">${esc(r.wordCount)}</td></tr>`,
      )
      .join("");
  }

  function renderFunnel(rows) {
    $("noFunnel").classList.toggle("hidden", rows.length > 0);
    $("funnel").innerHTML = rows
      .map(
        (r) => `<tr>
        <td>${esc(r.arm.toLowerCase())}</td><td>${sourcePill(r.source)}</td><td>${esc(r.angle)}</td>
        <td class="num">${esc(r.planned)}</td>
        <td class="num">${esc(r.sent)}</td>
        <td class="num">${esc(r.opened)}${ratio(r.opened, r.sent)}</td>
        <td class="num">${esc(r.completed)}${ratio(r.completed, r.sent)}</td>
        <td class="num">${esc(r.returned24h)}${ratio(r.returned24h, r.planned)}</td></tr>`,
      )
      .join("");
  }

  async function loadPreviews() {
    previews = await api("/previews?limit=50");
    renderPreviews();
    renderTaste(await api("/taste"));
  }

  async function loadFunnel() {
    renderFunnel(
      await api(`/funnel?days=${encodeURIComponent($("funnelDays").value)}`),
    );
  }
  $("funnelDays").addEventListener("change", () =>
    loadFunnel().catch((e) => flash(e.message)),
  );

  async function refresh() {
    await Promise.all([
      loadPreviews(),
      api("/plans?limit=50").then(renderPlans),
      loadFunnel(),
    ]);
  }

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
