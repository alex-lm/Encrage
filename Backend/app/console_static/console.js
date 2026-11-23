"use strict";

/* Encrage admin console. Builds every node with textContent (never innerHTML with data). */

const state = { tab: "overview", days: 30, session: null, activityType: "", refreshTimer: null };
const root = document.getElementById("root");

// ---------------------------------------------------------------- helpers

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === "class") el.className = value;
    else if (key.startsWith("on")) el.addEventListener(key.slice(2), value);
    else if (value === true) el.setAttribute(key, "");
    else el.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

const SVG_NS = "http://www.w3.org/2000/svg";
function s(tag, attrs, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs || {})) el.setAttribute(key, value);
  for (const child of children) el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return el;
}

const GIB = 1024 ** 3;
function bytes(value) {
  if (value === null || value === undefined) return "—";
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
  let n = Number(value), i = 0;
  while (Math.abs(n) >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
}
const count = (n) => Number(n || 0).toLocaleString();
const pct = (part, whole) => (whole ? Math.min(100, (part / whole) * 100) : 0);

function ago(iso) {
  if (!iso) return "never";
  const seconds = Math.round((Date.now() - Date.parse(iso)) / 1000);
  if (seconds < 45) return "just now";
  const steps = [[60, "m", 60], [24, "h", 3600], [30, "d", 86400], [12, "mo", 2592000]];
  for (const [limit, unit, size] of steps) {
    const v = Math.round(seconds / size);
    if (v < limit) return `${Math.max(v, 1)}${unit} ago`;
  }
  return `${Math.round(seconds / 31536000)}y ago`;
}
const fullTime = (iso) => (iso ? new Date(iso).toLocaleString() : "");

function duration(seconds) {
  const d = Math.floor(seconds / 86400), hr = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60);
  return d ? `${d}d ${hr}h` : hr ? `${hr}h ${m}m` : `${m}m`;
}

function meter(percent) {
  const bar = h("span");
  bar.style.width = `${percent.toFixed(1)}%`;
  return h("div", { class: `meter${percent >= 95 ? " bad" : percent >= 85 ? " warn" : ""}` }, bar);
}

function toast(message, isError) {
  const el = h("div", { class: `toast${isError ? " error" : ""}` }, message);
  document.getElementById("toasts").append(el);
  setTimeout(() => el.remove(), isError ? 6000 : 3500);
}

class ApiError extends Error {
  constructor(status, body) {
    super((body && body.error && body.error.message) || `Request failed (${status})`);
    this.status = status;
    this.code = body && body.error && body.error.code;
  }
}

async function api(method, path, body) {
  const options = { method, credentials: "same-origin", headers: { "X-Encrage-Console": "1" } };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (response.status === 401 && path !== "/api/login") {
    showLogin();
    throw new ApiError(401, data);
  }
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

// ---------------------------------------------------------------- event labels

const EVENT_LABELS = {
  login: ["Signed in", "good"], login_failed: ["Failed sign-in", "bad"], logout: ["Signed out", ""],
  password_changed: ["Changed password", ""], upload_completed: ["Uploaded", "good"], media_deleted: ["Deleted item", ""],
  admin_login: ["Console sign-in", "admin"], admin_login_failed: ["Failed console sign-in", "bad"],
  admin_logout: ["Console sign-out", "admin"], user_created: ["User created", "admin"], user_deleted: ["User deleted", "bad"],
  quota_changed: ["Quota changed", "admin"], password_reset: ["Password reset", "admin"],
  user_disabled: ["User disabled", "bad"], user_enabled: ["User enabled", "admin"], sessions_revoked: ["Devices signed out", "admin"],
};

function describeEvent(e) {
  const [label, tone] = EVENT_LABELS[e.event_type] || [e.event_type, ""];
  const who = e.username || (e.details && e.details.unknown_user ? "unknown account" : "");
  const parts = [];
  if (e.event_type === "upload_completed" || e.event_type === "media_deleted") {
    parts.push(`${(e.details && e.details.media_type) || "item"}, ${bytes(e.bytes)}`);
  } else if (e.event_type === "quota_changed") {
    parts.push(`${bytes(e.details && e.details.previous_bytes)} → ${bytes(e.bytes)}`);
  } else if (e.event_type === "user_created") {
    parts.push(`quota ${bytes(e.bytes)}`);
  } else if (e.event_type === "user_deleted") {
    parts.push(`${count(e.details && e.details.media_count)} items, ${bytes(e.bytes)} freed`);
  } else if (e.details && e.details.sessions_revoked !== undefined) {
    parts.push(`${e.details.sessions_revoked} device(s) signed out`);
  }
  const meta = [e.actor.startsWith("admin:") ? `by ${e.actor.slice(6)}` : null, e.client_ip, ...parts].filter(Boolean);
  return { label, tone, who, meta: meta.join(" · ") };
}

function eventItem(e) {
  const d = describeEvent(e);
  return h("li", {},
    h("div", {}, h("span", { class: `tag ${d.tone}` }, d.label), d.who ? h("strong", {}, d.who) : null),
    h("span", { class: "when", title: fullTime(e.occurred_at) }, ago(e.occurred_at)),
    d.meta ? h("div", { class: "meta" }, d.meta) : null);
}

// ---------------------------------------------------------------- dialogs

function openDialog(build) {
  const dialog = h("dialog");
  const close = () => { dialog.close(); dialog.remove(); };
  dialog.append(build(close));
  dialog.addEventListener("cancel", (ev) => { ev.preventDefault(); close(); });
  document.body.append(dialog);
  dialog.showModal();
  const first = dialog.querySelector("input, select");
  if (first) first.focus();
}

function formDialog({ title, text, fields, submitLabel, danger, onSubmit }) {
  openDialog((close) => {
    const error = h("div", { class: "form-error" });
    const submit = h("button", { class: `btn ${danger ? "danger solid" : "primary"}`, type: "submit" }, submitLabel);
    const form = h("form", { class: "dialog-body form", novalidate: true },
      h("h2", {}, title), text ? h("p", {}, text) : null, ...fields, error,
      h("div", { class: "dialog-actions" }, h("button", { class: "btn", type: "button", onclick: close }, "Cancel"), submit));
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      error.textContent = "";
      submit.disabled = true;
      try {
        await onSubmit(form);
        close();
      } catch (err) {
        error.textContent = err.message;
        submit.disabled = false;
      }
    });
    return form;
  });
}

const field = (label, input, hint) => h("label", { class: "field" }, label, input, hint ? h("span", { class: "hint" }, hint) : null);

function quotaInput(currentBytes) {
  const amount = h("input", { type: "number", name: "quota_amount", min: "0.1", step: "any", required: true,
    value: currentBytes ? String(+(currentBytes / GIB).toFixed(2)) : "50" });
  const unit = h("select", { name: "quota_unit" }, h("option", { value: String(GIB) }, "GiB"),
    h("option", { value: String(1024 * GIB) }, "TiB"));
  return h("div", { class: "row" }, amount, unit);
}
function readQuota(form) {
  const amount = Number(form.elements.quota_amount.value);
  if (!Number.isFinite(amount) || amount <= 0) throw new Error("Enter a quota greater than zero");
  return Math.round(amount * Number(form.elements.quota_unit.value));
}
function readPassword(form) {
  const password = form.elements.password.value;
  if (password.length < 12) throw new Error("Password must contain at least 12 characters");
  if (password !== form.elements.confirm.value) throw new Error("Passwords do not match");
  return password;
}
const passwordFields = () => [
  field("Password", h("input", { type: "password", name: "password", autocomplete: "new-password", required: true }),
    "At least 12 characters"),
  field("Confirm password", h("input", { type: "password", name: "confirm", autocomplete: "new-password", required: true })),
];

// ---------------------------------------------------------------- login

function showLogin(setupRequired) {
  clearInterval(state.refreshTimer);
  state.session = null;
  const error = h("div", { class: "form-error" });
  const submit = h("button", { class: "btn primary", type: "submit" }, "Sign in");
  const form = h("form", { class: "form" },
    field("Username", h("input", { type: "text", name: "username", autocomplete: "username", required: true })),
    field("Password", h("input", { type: "password", name: "password", autocomplete: "current-password", required: true })),
    error, submit);
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    error.textContent = "";
    submit.disabled = true;
    try {
      await api("POST", "/api/login", { username: form.elements.username.value, password: form.elements.password.value });
      await boot();
    } catch (err) {
      error.textContent = err.message;
      submit.disabled = false;
    }
  });
  const setup = setupRequired ? [
    h("p", {}, "No console admin exists yet. Create one on the server, then sign in:"),
    h("pre", {}, "docker compose exec encrage-console \\\n  python manage.py set-console-admin <name>"),
  ] : [h("p", {}, "Sign in with a console admin account. App users cannot sign in here.")];
  root.replaceChildren(h("div", { class: "login-wrap" },
    h("div", { class: "card login" }, h("div", { class: "brand" }, h("img", { class: "brand-mark", src: "/static/icon.svg", alt: "" }), "Encrage Console"),
      ...setup, form)));
  form.elements.username.focus();
}

// ---------------------------------------------------------------- shell

function shell(content) {
  const tabs = [["overview", "Overview"], ["users", "Users"], ["activity", "Activity"]];
  const nav = h("nav", { class: "tabs", role: "tablist" }, tabs.map(([id, label]) =>
    h("button", { class: "tab", role: "tab", "aria-selected": String(state.tab === id), onclick: () => go(id) }, label)));
  const healthPill = h("span", { id: "health-pill", class: "pill" }, "checking");
  const logout = h("button", { class: "btn small", onclick: async () => {
    await api("POST", "/api/logout").catch(() => {});
    showLogin(false);
  } }, "Sign out");
  root.replaceChildren(
    h("header", { class: "topbar" },
      h("div", { class: "brand" }, h("img", { class: "brand-mark", src: "/static/icon.svg", alt: "" }), "Encrage Console"), nav,
      h("div", { class: "topbar-right" }, healthPill, h("span", { class: "muted" }, state.session.admin), logout)),
    h("main", {}, content));
}

function setHealthPill(status) {
  const pill = document.getElementById("health-pill");
  if (!pill) return;
  const label = { ok: "Healthy", warning: "Needs attention", critical: "Problem" }[status] || status;
  pill.className = `pill ${status}`;
  pill.textContent = label;
}

function go(tab) {
  state.tab = tab;
  clearInterval(state.refreshTimer);
  ({ overview: renderOverview, users: renderUsers, activity: renderActivity })[tab]();
}

// ---------------------------------------------------------------- overview

function barChart(series, valueKey, { format = count, tone = "", secondaryKey = null } = {}) {
  const width = 600, height = 170, padL = 44, padB = 22, padT = 8;
  const values = series.map((d) => d[valueKey] + (secondaryKey ? d[secondaryKey] : 0));
  const max = Math.max(1, ...values);
  const plotW = width - padL, plotH = height - padB - padT;
  const slot = plotW / series.length, barW = Math.max(1, slot * 0.72);
  const svg = s("svg", { class: "chart", viewBox: `0 0 ${width} ${height}`, preserveAspectRatio: "none", role: "img" });
  for (const frac of [0, 0.5, 1]) {
    const y = padT + plotH * (1 - frac);
    svg.append(s("line", { class: "grid-line", x1: padL, x2: width, y1: y, y2: y }));
    svg.append(s("text", { x: padL - 6, y: y + 4, "text-anchor": "end" }, format(max * frac)));
  }
  series.forEach((d, i) => {
    const x = padL + i * slot + (slot - barW) / 2;
    const primary = d[valueKey], secondary = secondaryKey ? d[secondaryKey] : 0;
    const hp = (primary / max) * plotH, hs = (secondary / max) * plotH;
    const tip = `${d.day}: ${format(primary)}${secondaryKey ? ` / ${format(secondary)}` : ""}`;
    if (primary) svg.append(s("rect", { class: `bar ${tone}`, x, width: barW, y: padT + plotH - hp, height: Math.max(hp, 1) }, s("title", {}, tip)));
    if (secondary) svg.append(s("rect", { class: "bar neg", x, width: barW, y: padT + plotH - hp - hs, height: Math.max(hs, 1) }, s("title", {}, tip)));
    if (!primary && !secondary) svg.append(s("rect", { x, width: barW, y: padT, height: plotH, fill: "transparent" }, s("title", {}, tip)));
  });
  const labels = [0, Math.floor(series.length / 2), series.length - 1];
  for (const i of new Set(labels)) {
    const x = padL + i * slot + slot / 2;
    const day = new Date(`${series[i].day}T00:00:00Z`);
    svg.append(s("text", { x, y: height - 6, "text-anchor": i === 0 ? "start" : i === series.length - 1 ? "end" : "middle" },
      day.toLocaleDateString(undefined, { month: "short", day: "numeric", timeZone: "UTC" })));
  }
  return svg;
}

function healthCards(health) {
  const { api: apiHealth, database: db, storage, queues, totals, console: con } = health;
  const diskPct = pct(storage.used_bytes, storage.total_bytes);
  const quotaPct = pct(totals.stored_bytes, totals.allocated_bytes);
  return h("div", { class: "grid cards" },
    h("div", { class: "card" },
      h("div", { class: "card-head" }, h("h3", {}, "API"), h("span", { class: `pill ${apiHealth.status}` }, apiHealth.status === "ok" ? "Online" : apiHealth.status)),
      h("div", { class: "big num" }, apiHealth.latency_ms !== null ? `${apiHealth.latency_ms} ms` : "—"),
      h("div", { class: "sub" }, apiHealth.error || "Health check response time"),
      h("dl", { class: "kv" }, h("dt", {}, "Environment"), h("dd", {}, con.environment),
        h("dt", {}, "Console uptime"), h("dd", {}, duration(con.uptime_seconds)))),
    h("div", { class: "card" },
      h("div", { class: "card-head" }, h("h3", {}, "Disk")),
      h("div", { class: "big num" }, `${bytes(storage.free_bytes)} free`),
      h("div", { class: "sub" }, `${bytes(storage.used_bytes)} of ${bytes(storage.total_bytes)} used`),
      meter(diskPct)),
    h("div", { class: "card" },
      h("div", { class: "card-head" }, h("h3", {}, "Encrypted storage")),
      h("div", { class: "big num" }, bytes(totals.stored_bytes)),
      h("div", { class: "sub" }, `${count(totals.media)} items (${count(totals.videos)} videos) · ${bytes(totals.allocated_bytes)} allocated`),
      meter(quotaPct)),
    h("div", { class: "card" },
      h("div", { class: "card-head" }, h("h3", {}, "Database"),
        h("button", { class: "btn small", onclick: runIntegrity }, "Check integrity")),
      h("div", { class: "big num" }, bytes(db.size_bytes + db.wal_bytes)),
      h("dl", { class: "kv" },
        h("dt", {}, "Schema"), h("dd", {}, `v${db.schema_version}${db.schema_version !== db.expected_schema_version ? ` (expected v${db.expected_schema_version})` : ""}`),
        h("dt", {}, "Users"), h("dd", {}, `${count(totals.active_users)} active / ${count(totals.users)}`),
        h("dt", {}, "Uploads in progress"), h("dd", {}, `${count(queues.uploads_in_progress)} (${bytes(queues.reserved_bytes)})`),
        h("dt", {}, "Pending deletions"), h("dd", {}, count(queues.pending_file_deletions)))));
}

async function runIntegrity(ev) {
  const button = ev.currentTarget;
  button.disabled = true;
  try {
    const result = await api("POST", "/api/health/integrity");
    toast(result.ok ? `Database integrity OK (${result.duration_ms} ms)` : `Integrity problems: ${result.results.join("; ")}`, !result.ok);
  } catch (err) {
    toast(err.message, true);
  } finally {
    button.disabled = false;
  }
}

async function renderOverview() {
  let data;
  try {
    data = await api("GET", `/api/overview?days=${state.days}`);
  } catch (err) {
    if (err.status !== 401) shell(h("div", { class: "notice critical" }, err.message));
    return;
  }
  const { health, usage, recent_events: events } = data;
  const t = usage.totals;
  const windowPicker = h("div", { class: "segmented", role: "group", "aria-label": "Time window" },
    [7, 30, 90].map((d) => h("button", { "aria-pressed": String(state.days === d), onclick: () => { state.days = d; renderOverview(); } }, `${d} days`)));
  const kpi = (label, value, sub) => h("div", { class: "card" }, h("h3", {}, label), h("div", { class: "big num" }, value), sub ? h("div", { class: "sub" }, sub) : null);
  const trackedNote = usage.events_tracked_since
    ? `Deletions and failed sign-ins are tracked since ${new Date(usage.events_tracked_since).toLocaleDateString()}.`
    : "Deletions and failed sign-ins are tracked from now on.";

  shell([
    health.warnings.length ? h("div", { class: "notices" }, health.warnings.map((w) => h("div", { class: `notice ${w.level}` }, w.message))) : null,
    healthCards(health),
    h("div", { class: "section-head" }, h("h2", {}, "Recent usage"), windowPicker),
    h("div", { class: "grid kpis" },
      kpi("Uploads", count(t.uploads), `${bytes(t.upload_bytes)} added`),
      kpi("Deleted", count(t.deletions), `${bytes(t.deleted_bytes)} removed`),
      kpi("Net change", bytes(t.upload_bytes - t.deleted_bytes)),
      kpi("Sign-ins", count(t.logins), `${count(t.failed_logins)} failed`),
      kpi("Active users", count(t.active_users), `of ${count(health.totals.users)}`)),
    h("div", { class: "grid charts" },
      h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", {}, "Data uploaded per day"),
        h("div", { class: "legend" }, h("span", {}, "uploaded"))), barChart(usage.daily, "upload_bytes", { format: bytes })),
      h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", {}, "Sign-ins per day"),
        h("div", { class: "legend" }, h("span", { class: "alt" }, "successful"), h("span", { class: "neg" }, "failed"))),
        barChart(usage.daily, "logins", { tone: "alt", secondaryKey: "failed_logins" }))),
    h("div", { class: "split" },
      h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", {}, "Latest activity"),
        h("button", { class: "btn link", onclick: () => go("activity") }, "View all")),
        events.length ? h("ul", { class: "events" }, events.map(eventItem)) : h("div", { class: "empty" }, "No activity recorded yet."),
        h("div", { class: "sub" }, trackedNote)),
      h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", {}, `Top uploaders · ${usage.days} days`)),
        usage.top_uploaders.length
          ? h("ul", { class: "events" }, usage.top_uploaders.map((u) => h("li", {}, h("strong", {}, u.username),
            h("span", { class: "when num" }, bytes(u.bytes)), h("div", { class: "meta" }, `${count(u.uploads)} uploads`))))
          : h("div", { class: "empty" }, "No uploads in this window."))),
  ]);
  setHealthPill(health.status);
  state.refreshTimer = setInterval(() => { if (state.tab === "overview" && !document.querySelector("dialog")) renderOverview(); }, 60000);
}

// ---------------------------------------------------------------- users

async function renderUsers() {
  let data;
  try {
    data = await api("GET", "/api/users");
  } catch (err) {
    if (err.status !== 401) shell(h("div", { class: "notice critical" }, err.message));
    return;
  }
  const rows = data.users.map((u) => {
    const used = pct(u.space_used_bytes, u.space_allocated_bytes);
    return h("tr", { class: u.is_active ? "" : "inactive" },
      h("td", {}, h("strong", {}, u.username), h("div", { class: "muted mono" }, u.user_id.slice(0, 8))),
      h("td", {}, h("span", { class: `pill ${u.is_active ? "ok" : "critical"}` }, u.is_active ? "Active" : "Disabled")),
      h("td", { class: "usage-cell" }, h("div", { class: "num" }, `${bytes(u.space_used_bytes)} / ${bytes(u.space_allocated_bytes)}`), meter(used)),
      h("td", { class: "right num" }, count(u.media_count), h("div", { class: "muted" }, `${count(u.video_count)} videos`)),
      h("td", { class: "right num" }, count(u.active_devices)),
      h("td", { title: fullTime(u.last_seen_at) }, ago(u.last_seen_at),
        h("div", { class: "muted" }, u.last_upload_at ? `upload ${ago(u.last_upload_at)}` : "no uploads")),
      h("td", {}, h("div", { class: "actions" },
        h("button", { class: "btn small", onclick: () => editQuota(u) }, "Quota"),
        h("button", { class: "btn small", onclick: () => resetPassword(u) }, "Password"),
        h("button", { class: "btn small", onclick: () => signOutDevices(u) }, "Sign out"),
        h("button", { class: "btn small", onclick: () => toggleActive(u) }, u.is_active ? "Disable" : "Enable"),
        h("button", { class: "btn small danger", onclick: () => deleteUser(u) }, "Delete"))));
  });
  shell([
    h("div", { class: "section-head" }, h("h2", {}, `Users (${data.users.length})`),
      h("button", { class: "btn primary", onclick: addUser }, "Add user")),
    h("div", { class: "card" }, h("div", { class: "table-wrap" }, data.users.length ? h("table", {},
      h("thead", {}, h("tr", {}, h("th", {}, "User"), h("th", {}, "Status"), h("th", {}, "Storage"),
        h("th", { class: "right" }, "Items"), h("th", { class: "right" }, "Devices (30d)"), h("th", {}, "Last seen"), h("th", { class: "right" }, "Actions"))),
      h("tbody", {}, rows)) : h("div", { class: "empty" }, "No users yet. Add one to let the app sign in."))),
  ]);
  api("GET", "/api/health").then((hl) => setHealthPill(hl.status)).catch(() => {});
}

function addUser() {
  formDialog({
    title: "Add user", submitLabel: "Create user",
    fields: [field("Username", h("input", { type: "text", name: "username", autocomplete: "off", required: true }),
      "Letters, digits, . _ @ -"), ...passwordFields(), field("Storage quota", quotaInput(50 * GIB))],
    onSubmit: async (form) => {
      const body = { username: form.elements.username.value.trim(), password: readPassword(form), quota_bytes: readQuota(form) };
      await api("POST", "/api/users", body);
      toast(`Created ${body.username}`);
      renderUsers();
    },
  });
}

function editQuota(u) {
  formDialog({
    title: `Storage quota for ${u.username}`, submitLabel: "Save quota",
    text: `Currently using ${bytes(u.space_used_bytes)}. The quota cannot go below that.`,
    fields: [field("Quota", quotaInput(u.space_allocated_bytes))],
    onSubmit: async (form) => {
      const quota = readQuota(form);
      await api("PUT", `/api/users/${u.user_id}/quota`, { quota_bytes: quota });
      toast(`${u.username}: quota set to ${bytes(quota)}`);
      renderUsers();
    },
  });
}

function resetPassword(u) {
  formDialog({
    title: `Reset password for ${u.username}`, submitLabel: "Reset password",
    text: "Every device signed in to this account will be signed out. Their photos stay encrypted and unaffected.",
    fields: passwordFields(),
    onSubmit: async (form) => {
      const result = await api("POST", `/api/users/${u.user_id}/password`, { password: readPassword(form) });
      toast(`Password reset; ${result.sessions_revoked} device(s) signed out`);
      renderUsers();
    },
  });
}

function signOutDevices(u) {
  formDialog({
    title: `Sign out ${u.username} everywhere?`, submitLabel: "Sign out all devices", fields: [],
    text: "Every device will need to sign in again with the current password.",
    onSubmit: async () => {
      const result = await api("POST", `/api/users/${u.user_id}/revoke-sessions`);
      toast(`${result.sessions_revoked} device(s) signed out`);
      renderUsers();
    },
  });
}

function toggleActive(u) {
  const enabling = !u.is_active;
  formDialog({
    title: `${enabling ? "Enable" : "Disable"} ${u.username}?`, submitLabel: enabling ? "Enable" : "Disable", danger: !enabling,
    text: enabling ? "The user will be able to sign in again." : "The user is signed out everywhere and cannot sign in. Their data is kept.",
    fields: [],
    onSubmit: async () => {
      await api("PUT", `/api/users/${u.user_id}/active`, { active: enabling });
      toast(`${u.username} ${enabling ? "enabled" : "disabled"}`);
      renderUsers();
    },
  });
}

function deleteUser(u) {
  formDialog({
    title: `Delete ${u.username}?`, submitLabel: "Delete permanently", danger: true,
    text: `This permanently deletes the account and all ${count(u.media_count)} stored items (${bytes(u.space_used_bytes)}). It cannot be undone. To keep the data, disable the user instead.`,
    fields: [field(`Type “${u.username}” to confirm`, h("input", { type: "text", name: "confirm", autocomplete: "off", required: true }))],
    onSubmit: async (form) => {
      const confirmName = form.elements.confirm.value.trim();
      if (confirmName.toLowerCase() !== u.username.toLowerCase()) throw new Error("The username does not match");
      const result = await api("DELETE", `/api/users/${u.user_id}`, { confirm_username: confirmName });
      toast(`Deleted ${result.username}; ${bytes(result.space_freed_bytes)} freed`);
      renderUsers();
    },
  });
}

// ---------------------------------------------------------------- activity

async function renderActivity() {
  const query = (before) => `/api/activity?limit=100${state.activityType ? `&event_type=${encodeURIComponent(state.activityType)}` : ""}${before ? `&before_id=${before}` : ""}`;
  let data;
  try {
    data = await api("GET", query());
  } catch (err) {
    if (err.status !== 401) shell(h("div", { class: "notice critical" }, err.message));
    return;
  }
  const tbody = h("tbody");
  const addRows = (events) => events.forEach((e) => {
    const d = describeEvent(e);
    tbody.append(h("tr", {},
      h("td", { title: fullTime(e.occurred_at) }, ago(e.occurred_at), h("div", { class: "muted" }, fullTime(e.occurred_at))),
      h("td", {}, h("span", { class: `tag ${d.tone}` }, d.label)),
      h("td", {}, d.who || h("span", { class: "muted" }, "—")),
      h("td", { class: "muted" }, d.meta)));
  });
  addRows(data.events);
  let next = data.next_before_id;
  const more = h("button", { class: "btn", onclick: async () => {
    more.disabled = true;
    const page = await api("GET", query(next));
    addRows(page.events);
    next = page.next_before_id;
    more.disabled = false;
    if (!next) more.remove();
  } }, "Load older");
  const filter = h("select", { "aria-label": "Event type", onchange: (ev) => { state.activityType = ev.target.value; renderActivity(); } },
    h("option", { value: "" }, "All events"),
    data.event_types.map((t) => h("option", { value: t, selected: state.activityType === t }, (EVENT_LABELS[t] || [t])[0])));
  filter.style.width = "auto";
  shell([
    h("div", { class: "section-head" }, h("h2", {}, "Activity"), filter),
    h("div", { class: "card" }, h("div", { class: "table-wrap" }, data.events.length ? h("table", {},
      h("thead", {}, h("tr", {}, h("th", {}, "When"), h("th", {}, "Event"), h("th", {}, "User"), h("th", {}, "Details"))), tbody)
      : h("div", { class: "empty" }, "No matching activity."))),
    next ? h("div", {}, more) : null,
    h("p", { class: "muted" }, `Events are kept for ${state.retention || 90} days. Media content, names and keys are never logged.`),
  ]);
  api("GET", "/api/health").then((hl) => { setHealthPill(hl.status); state.retention = hl.console.activity_retention_days; }).catch(() => {});
}

// ---------------------------------------------------------------- boot

async function boot() {
  try {
    const session = await api("GET", "/api/session");
    if (!session.authenticated) return showLogin(session.setup_required);
    state.session = session;
    go(state.tab);
  } catch (err) {
    root.replaceChildren(h("p", { class: "boot" }, `Console unavailable: ${err.message}`));
  }
}

boot();
