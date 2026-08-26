"use strict";
const $ = (id) => document.getElementById(id);
const LIST_KEYS = ["domains", "keywords", "regex", "activeProfiles"];
const BOOL_KEYS = ["enabled", "scrubOnStartup", "caseSensitive", "wholeWord", "syncRules", "encryptedSync"];
const SYNC_DATA_PERMISSIONS = ["browsingActivity", "searchTerms", "technicalAndInteraction"];
let visualRules = [];
let editingId = null;

function lines(id) {
  return $(id).value.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
}

function status(message, error = false) {
  $("status").textContent = message;
  $("status").classList.toggle("error", error);
}

function showError(error) {
  status(`Extension error: ${error?.message || error}`, true);
}

function escapeText(value) {
  return String(value || "");
}

async function readForm() {
  const previous = await browser.runtime.sendMessage({ type: "get-settings" });
  const next = { ...previous, visualRules: [...visualRules] };
  for (const key of BOOL_KEYS) next[key] = $(key).checked;
  for (const key of LIST_KEYS) next[key] = lines(key);
  next.scrubEveryMinutes = Math.max(15, Number($("scrubEveryMinutes").value) || 15);
  return next;
}

async function ensureSyncPermission() {
  let granted = false;
  try {
    granted = await browser.permissions.contains({ data_collection: SYNC_DATA_PERMISSIONS });
    if (!granted) granted = await browser.permissions.request({ data_collection: SYNC_DATA_PERMISSIONS });
  } catch (_) { granted = false; }
  if (!granted) {
    $("syncRules").checked = false;
    status("Firefox Sync transport was not enabled; rules remain local only.", true);
  }
  return granted;
}

async function ensureActionPermissions(rule) {
  const permissions = [];
  const origins = [];
  if (rule.clearCookies) { permissions.push("cookies"); origins.push("<all_urls>"); }
  if (rule.clearCache) permissions.push("browsingData");
  if (rule.clearDownloads) permissions.push("downloads");
  if (!permissions.length && !origins.length) return true;
  try {
    const request = {};
    if (permissions.length) request.permissions = [...new Set(permissions)];
    if (origins.length) request.origins = [...new Set(origins)];
    return await browser.permissions.request(request);
  } catch (_) { return false; }
}

async function save(showMessage = true) {
  const settings = await readForm();
  if (settings.syncRules && !await ensureSyncPermission()) settings.syncRules = false;
  await browser.runtime.sendMessage({ type: "set-settings", settings });
  if (showMessage) status("Saved. Rules are active immediately.");
  await refreshDashboard();
  return settings;
}

function renderRules() {
  const host = $("visualRuleList");
  host.textContent = "";
  if (!visualRules.length) {
    const empty = document.createElement("p");
    empty.className = "empty-state";
    empty.textContent = "No visual rules yet.";
    host.appendChild(empty);
    return;
  }
  for (const rule of visualRules) {
    const row = document.createElement("div");
    row.className = `rule-row${rule.enabled === false ? " paused" : ""}`;
    const copy = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = `${rule.enabled === false ? "⏸" : "🛡️"} ${escapeText(rule.name)}`;
    const detail = document.createElement("small");
    detail.textContent = `${rule.profile || "Default"} · ${rule.matcher} · ${rule.action} · ${rule.value}`;
    copy.append(title, detail);
    const actions = document.createElement("div");
    actions.className = "actions compact";
    for (const [label, action] of [["Edit", "edit"], [rule.enabled === false ? "Enable" : "Pause", "toggle"], ["Delete", "delete"]]) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = label;
      button.dataset.action = action;
      button.dataset.id = rule.id;
      actions.appendChild(button);
    }
    row.append(copy, actions);
    host.appendChild(row);
  }
}

function resetBuilder() {
  editingId = null;
  $("ruleName").value = "";
  $("ruleProfile").value = "Default";
  $("ruleMatcher").value = "DOMAIN";
  $("ruleValue").value = "";
  $("ruleQueryParameter").value = "";
  $("ruleAction").value = "BLOCK";
  $("ruleExpiry").value = "";
  for (const id of ["ruleClearCookies", "ruleClearCache", "ruleClearDownloads", "ruleCloseTab"]) $(id).checked = false;
  $("cancelEdit").hidden = true;
  updateQueryVisibility();
}

function builderRule() {
  const minutes = Number($("ruleExpiry").value || 0);
  return {
    id: editingId || (crypto.randomUUID ? crypto.randomUUID() : `rule-${Date.now()}`),
    name: $("ruleName").value.trim() || $("ruleValue").value.trim(),
    profile: $("ruleProfile").value.trim() || "Default",
    matcher: $("ruleMatcher").value,
    value: $("ruleValue").value.trim(),
    queryParameter: $("ruleQueryParameter").value.trim(),
    action: $("ruleAction").value,
    enabled: true,
    expiresAtEpochMillis: minutes > 0 ? Date.now() + Math.min(minutes, 525600) * 60000 : 0,
    clearCookies: $("ruleClearCookies").checked,
    clearCache: $("ruleClearCache").checked,
    clearDownloads: $("ruleClearDownloads").checked,
    closeTab: $("ruleCloseTab").checked,
  };
}

function editRule(rule) {
  editingId = rule.id;
  $("ruleName").value = rule.name || "";
  $("ruleProfile").value = rule.profile || "Default";
  $("ruleMatcher").value = rule.matcher;
  $("ruleValue").value = rule.value || "";
  $("ruleQueryParameter").value = rule.queryParameter || "";
  $("ruleAction").value = rule.action;
  $("ruleExpiry").value = rule.expiresAtEpochMillis > Date.now()
    ? Math.max(1, Math.round((rule.expiresAtEpochMillis - Date.now()) / 60000)) : "";
  $("ruleClearCookies").checked = Boolean(rule.clearCookies);
  $("ruleClearCache").checked = Boolean(rule.clearCache);
  $("ruleClearDownloads").checked = Boolean(rule.clearDownloads);
  $("ruleCloseTab").checked = Boolean(rule.closeTab);
  $("cancelEdit").hidden = false;
  updateQueryVisibility();
  $("ruleName").scrollIntoView({ behavior: "smooth", block: "center" });
}

function updateQueryVisibility() {
  $("queryParameterRow").hidden = $("ruleMatcher").value !== "QUERY_PARAMETER";
}

async function refreshDashboard() {
  const [settings, health] = await Promise.all([
    browser.runtime.sendMessage({ type: "get-settings" }),
    browser.runtime.sendMessage({ type: "health" }),
  ]);
  $("metricTotal").textContent = Number(settings.totalRemoved || 0).toLocaleString();
  $("metricToday").textContent = Number(settings.todayProtected || 0).toLocaleString();
  $("metricWeek").textContent = Number(settings.weekProtected || 0).toLocaleString();
  $("metricCollapsed").textContent = Number(settings.totalCollapsed || 0).toLocaleString();
  $("metricHealth").textContent = health.ok ? `All ${health.selfTestPassed}/${health.selfTestTotal} checks passed` : `Check failed: ${health.lastError || "self-test"}`;
  $("selfTestResult").textContent = health.selfTestOk ? ` ${health.selfTestPassed}/${health.selfTestTotal} passed` : " Check required";
  $("updateCenter").textContent = `Installed extension ${health.version}. Signed updates use ${health.updateUrl || "the configured Mozilla update feed"}.`;
  const until = Number(settings.temporaryUntil || 0);
  $("temporaryStatus").textContent = until > Date.now() ? `Active until ${new Date(until).toLocaleTimeString()}` : "Off (session mode is shown in the popup while active)";
}

async function load() {
  const settings = await browser.runtime.sendMessage({ type: "get-settings" });
  for (const key of BOOL_KEYS) $(key).checked = Boolean(settings[key]);
  for (const key of LIST_KEYS) $(key).value = (settings[key] || []).join("\n");
  $("scrubEveryMinutes").value = Math.max(15, Number(settings.scrubEveryMinutes || 15));
  visualRules = Array.isArray(settings.visualRules) ? settings.visualRules.map((rule) => ({ ...rule })) : [];
  renderRules();
  resetBuilder();
  await refreshDashboard();
}

$("ruleMatcher").addEventListener("change", updateQueryVisibility);
$("cancelEdit").addEventListener("click", resetBuilder);
$("addRule").addEventListener("click", async () => {
  try {
    const rule = builderRule();
    if (!rule.value) { status("Enter a value for the rule.", true); return; }
    if (!await ensureActionPermissions(rule)) { status("Extra permission denied; destructive actions were not saved.", true); return; }
    const existing = visualRules.find((item) => item.id === rule.id);
    if (existing) rule.enabled = existing.enabled !== false;
    if (!existing) {
      const profiles = lines("activeProfiles");
      if (!profiles.includes(rule.profile)) $("activeProfiles").value = [...profiles, rule.profile].join("\n");
    }
    visualRules = [...visualRules.filter((item) => item.id !== rule.id), rule];
    renderRules();
    resetBuilder();
    await save(false);
    status("Visual rule saved and active.");
  } catch (error) { showError(error); }
});

$("visualRuleList").addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-id]");
  if (!button) return;
  const rule = visualRules.find((item) => item.id === button.dataset.id);
  if (!rule) return;
  if (button.dataset.action === "edit") { editRule(rule); return; }
  if (button.dataset.action === "toggle") rule.enabled = rule.enabled === false;
  if (button.dataset.action === "delete") visualRules = visualRules.filter((item) => item.id !== rule.id);
  renderRules();
  await save(false);
  status(button.dataset.action === "delete" ? "Rule deleted." : "Rule status updated.");
});

$("save").addEventListener("click", () => save().catch(showError));

$("test").addEventListener("click", async () => {
  try {
    $("testResult").textContent = "Testing…";
    const result = await browser.runtime.sendMessage({
      type: "test-rule", settings: await readForm(), url: $("testUrl").value, title: $("testTitle").value,
    });
    $("testResult").textContent = result.action === "ALLOW" ? "✅ Saved normally"
      : result.action === "COLLAPSE_TO_ROOT" ? `🏠 Only ${result.collapsedUrl} will be saved`
        : `🛡️ Blocked${result.ruleName ? ` by ${result.ruleName}` : ""}`;
  } catch (error) { $("testResult").textContent = `Error: ${error?.message || error}`; }
});

async function previewCleanup(execute) {
  await save(false);
  $("cleanupResult").textContent = "Scanning aggregate counts…";
  const preview = await browser.runtime.sendMessage({ type: "preview-scrub" });
  $("cleanupResult").textContent = `${preview.matching.toLocaleString()} matching of ${preview.scanned.toLocaleString()} scanned; ${preview.collapsed.toLocaleString()} collapse to a homepage.`;
  if (!execute || !preview.matching) return;
  if (!confirm(`Remove ${preview.matching} matching history entries? Cookies and sessions stay saved unless a visual rule explicitly changes them.`)) return;
  const result = await browser.runtime.sendMessage({ type: "scrub-now" });
  $("cleanupResult").textContent = `Removed ${result.removed.toLocaleString()}; retained ${result.collapsed.toLocaleString()} homepage targets.`;
  await refreshDashboard();
}
$("previewClean").addEventListener("click", () => previewCleanup(false).catch(showError));
$("clean").addEventListener("click", () => previewCleanup(true).catch(showError));

for (const button of document.querySelectorAll("[data-temp]")) {
  button.addEventListener("click", async () => {
    const mode = button.dataset.temp;
    const message = { type: "temporary-mode", mode };
    if (/^\d+$/.test(mode)) message.minutes = Number(mode);
    await browser.runtime.sendMessage(message);
    status(mode === "off" ? "Temporary mode stopped." : "Temporary history protection enabled.");
    await refreshDashboard();
  });
}

function passphrase() {
  const value = $("backupPassphrase").value;
  if (value.length < 8) throw new Error("Use a passphrase of at least 8 characters");
  return value;
}

$("exportEncrypted").addEventListener("click", async () => {
  try {
    await save(false);
    const result = await browser.runtime.sendMessage({ type: "export-encrypted", passphrase: passphrase() });
    const blob = new Blob([result.bundle], { type: "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a"); a.href = url; a.download = "fenix-privacy-rules.fprules"; a.click();
    URL.revokeObjectURL(url); $("backupPassphrase").value = ""; status("Encrypted Android-compatible bundle exported.");
  } catch (error) { showError(error); }
});

$("importEncrypted").addEventListener("change", async (event) => {
  const file = event.target.files?.[0]; if (!file) return;
  try {
    await browser.runtime.sendMessage({ type: "import-encrypted", bundle: await file.text(), passphrase: passphrase() });
    $("backupPassphrase").value = ""; await load(); status("Encrypted rules imported and activated.");
  } catch (error) { showError(error); }
  finally { event.target.value = ""; }
});

$("pushEncryptedSync").addEventListener("click", async () => {
  try {
    $("syncRules").checked = true; $("encryptedSync").checked = true;
    if (!await ensureSyncPermission()) return;
    await save(false);
    await browser.runtime.sendMessage({ type: "push-encrypted-sync", passphrase: passphrase() });
    $("backupPassphrase").value = ""; status("Encrypted rule bundle pushed to Firefox Sync.");
  } catch (error) { showError(error); }
});

$("pullEncryptedSync").addEventListener("click", async () => {
  try {
    if (!await ensureSyncPermission()) return;
    await browser.runtime.sendMessage({ type: "pull-encrypted-sync", passphrase: passphrase() });
    $("backupPassphrase").value = ""; await load(); status("Encrypted rules pulled from Firefox Sync and activated.");
  } catch (error) { showError(error); }
});

$("selfTest").addEventListener("click", async () => {
  try {
    const result = await browser.runtime.sendMessage({ type: "self-test" });
    $("selfTestResult").textContent = result.ok ? ` ${result.passed}/${result.total} passed` : ` Failed: ${result.failures.join(", ")}`;
    await refreshDashboard();
  } catch (error) { showError(error); }
});

$("resetCounter").addEventListener("click", async () => {
  try {
    const settings = await readForm();
    await browser.runtime.sendMessage({ type: "set-settings", settings: {
      ...settings, totalRemoved: 0, totalCollapsed: 0, todayProtected: 0, weekProtected: 0,
      lastRemovedAt: 0, lastMatchAt: 0, lastDecisionCode: 0,
    } });
    await refreshDashboard(); status("Only aggregate counters were reset.");
  } catch (error) { showError(error); }
});

$("openAddons").addEventListener("click", () => browser.tabs.create({ url: "about:addons" }));
load().catch(showError);
