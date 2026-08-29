"use strict";

const DEFAULTS = Object.freeze({
  enabled: true,
  caseSensitive: false,
  wholeWord: false,
  domains: [],
  keywords: [],
  regex: [],
  visualRules: [],
  activeProfiles: ["Default"],
  temporaryUntil: 0,
  scrubOnStartup: true,
  scrubEveryMinutes: 15,
  syncRules: false,
  encryptedSync: true,
  totalRemoved: 0,
  totalCollapsed: 0,
  todayProtected: 0,
  weekProtected: 0,
  dayBucket: -1,
  weekBucket: -1,
  lastRemovedAt: 0,
  lastScrubAt: 0,
  lastMatchAt: 0,
  lastDecisionCode: 0,
  lastSelfTestAt: 0,
  selfTestOk: false,
  selfTestPassed: 0,
  selfTestTotal: 0,
  lastError: "",
});

const ACTION = Object.freeze({
  ALLOW: "ALLOW",
  BLOCK: "BLOCK",
  COLLAPSE: "COLLAPSE_TO_ROOT",
  FORGET_AFTER: "FORGET_AFTER",
  FORGET_ON_RESTART: "FORGET_ON_RESTART",
});
const MATCHER = Object.freeze({
  DOMAIN: "DOMAIN",
  DOMAIN_EXCEPT_ROOT: "DOMAIN_EXCEPT_ROOT",
  PATH_PREFIX: "PATH_PREFIX",
  URL_CONTAINS: "URL_CONTAINS",
  TITLE_CONTAINS: "TITLE_CONTAINS",
  QUERY_PARAMETER: "QUERY_PARAMETER",
  REGEX: "REGEX",
  EXACT_URL: "EXACT_URL",
});
const EVENT = Object.freeze({ PREVENTED: 1, REMOVED: 2, CLEANUP: 3, COLLAPSED: 4 });
const ALARM_NAME = "fenix-privacy-history-scrub";
const SYNC_DATA_PERMISSIONS = Object.freeze(["browsingActivity", "searchTerms", "technicalAndInteraction"]);
const SYNC_KEYS = Object.freeze([
  "enabled", "caseSensitive", "wholeWord", "domains", "keywords", "regex", "visualRules",
  "activeProfiles", "scrubOnStartup", "scrubEveryMinutes", "syncRules", "encryptedSync",
]);
const SHIELD_SETTING_KEYS = Object.freeze([
  "enabled", "caseSensitive", "wholeWord", "domains", "keywords", "regex", "visualRules",
  "activeProfiles", "temporaryUntil",
]);
const DELETE_RETRY_DELAYS_MS = Object.freeze([0, 120, 450, 1200]);
const BACKUP_HEADER = "FENIX-PRIVACY-2\n";
const PBKDF2_ITERATIONS = 210000;
const EPHEMERAL_STATE_KEY = "privacyStudioEphemeralV3";
const sessionPrivateTabs = new Set();
const inheritingPrivateTabs = new Set();
const oneShotTabs = new Map();
let sessionBlockAll = false;
let ephemeralStateLoaded = false;
let ephemeralStateLoad = null;
let cached = null;
const pendingDeletes = new Map();

async function ensureEphemeralState() {
  if (ephemeralStateLoaded) return;
  if (!ephemeralStateLoad) {
    ephemeralStateLoad = (async () => {
      try {
        const area = browser.storage.session;
        if (!area?.get) return;
        const stored = await area.get(EPHEMERAL_STATE_KEY);
        const value = stored?.[EPHEMERAL_STATE_KEY];
        if (!value || typeof value !== "object") return;
        sessionBlockAll = value.sessionBlockAll === true;
        for (const id of value.privateTabs || []) if (Number.isInteger(id)) sessionPrivateTabs.add(id);
        for (const id of value.inheritingTabs || []) if (Number.isInteger(id)) inheritingPrivateTabs.add(id);
        for (const entry of value.oneShots || []) {
          if (!Array.isArray(entry) || !Number.isInteger(entry[0]) || typeof entry[1] !== "object") continue;
          oneShotTabs.set(entry[0], {
            armedUrl: String(entry[1].armedUrl || ""),
            protectedUrl: String(entry[1].protectedUrl || ""),
          });
        }
      } catch (_) {
        // Session protection falls back to the current event-page lifetime.
      } finally {
        ephemeralStateLoaded = true;
      }
    })();
  }
  await ephemeralStateLoad;
}

async function persistEphemeralState() {
  const area = browser.storage.session;
  if (!area?.set) return;
  try {
    await area.set({
      [EPHEMERAL_STATE_KEY]: {
        sessionBlockAll,
        privateTabs: [...sessionPrivateTabs],
        inheritingTabs: [...inheritingPrivateTabs],
        oneShots: [...oneShotTabs.entries()],
      },
    });
  } catch (_) {
    // Never fall back to disk for process/session-only tab URLs.
  }
}

async function resetEphemeralState() {
  sessionBlockAll = false;
  sessionPrivateTabs.clear();
  inheritingPrivateTabs.clear();
  oneShotTabs.clear();
  ephemeralStateLoaded = true;
  ephemeralStateLoad = Promise.resolve();
  try { await browser.storage.session?.remove?.(EPHEMERAL_STATE_KEY); } catch (_) { /* already empty */ }
}

function cleanLines(value) {
  const items = Array.isArray(value) ? value : String(value || "").split(/\r?\n/);
  return [...new Set(items.map((x) => String(x).trim()).filter(Boolean))];
}

function normalizeDomainRule(value) {
  const raw = String(value || "").trim().replace(/^(\w+:\/\/)?\*\./i, "$1");
  if (!raw) return "";
  try {
    const candidate = raw.includes("://") ? raw : `https://${raw}`;
    return new URL(candidate).hostname.toLowerCase().replace(/^\.+|\.+$/g, "");
  } catch (_) {
    return raw.toLowerCase().replace(/^\.+|\.+$/g, "");
  }
}

function normalizeVisualRule(raw = {}) {
  const matcher = Object.values(MATCHER).includes(raw.matcher) ? raw.matcher : MATCHER.DOMAIN;
  const action = Object.values(ACTION).includes(raw.action) ? raw.action : ACTION.BLOCK;
  return {
    id: String(raw.id || cryptoRandomId()),
    name: String(raw.name || raw.value || "Rule").trim(),
    profile: String(raw.profile || "Default").trim() || "Default",
    matcher,
    value: String(raw.value || "").trim(),
    queryParameter: String(raw.queryParameter || "").trim(),
    action,
    enabled: raw.enabled !== false,
    expiresAtEpochMillis: Math.max(0, Number(raw.expiresAtEpochMillis || 0)),
    retentionMillis: Math.max(0, Number(raw.retentionMillis || 0)),
    clearCookies: Boolean(raw.clearCookies),
    clearCache: Boolean(raw.clearCache),
    clearDownloads: Boolean(raw.clearDownloads),
    closeTab: Boolean(raw.closeTab),
    protectLogin: Boolean(raw.protectLogin),
  };
}

function cryptoRandomId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `rule-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function normalizeSettings(raw = {}) {
  const merged = { ...DEFAULTS, ...raw };
  merged.domains = cleanLines(merged.domains).map(normalizeDomainRule).filter(Boolean);
  merged.keywords = cleanLines(merged.keywords);
  merged.regex = cleanLines(merged.regex);
  merged.visualRules = (Array.isArray(merged.visualRules) ? merged.visualRules : [])
    .map(normalizeVisualRule).filter((rule) => rule.value);
  merged.activeProfiles = cleanLines(merged.activeProfiles);
  if (!merged.activeProfiles.length) merged.activeProfiles = ["Default"];
  merged.scrubEveryMinutes = Math.max(15, Number(merged.scrubEveryMinutes) || 15);
  for (const key of ["totalRemoved", "totalCollapsed", "todayProtected", "weekProtected"]) {
    merged[key] = Math.max(0, Number(merged[key]) || 0);
  }
  merged.temporaryUntil = Math.max(0, Number(merged.temporaryUntil) || 0);
  return merged;
}

async function hasSyncConsent() {
  if (!browser.permissions?.contains) return false;
  try {
    return await browser.permissions.contains({ data_collection: SYNC_DATA_PERMISSIONS });
  } catch (_) {
    return false;
  }
}

function pickSyncSettings(settings) {
  return Object.fromEntries(SYNC_KEYS.map((key) => [key, settings[key]]));
}

async function getSettings() {
  if (cached) return cached;
  const local = normalizeSettings(await browser.storage.local.get(DEFAULTS));
  if (local.syncRules && !local.encryptedSync && await hasSyncConsent()) {
    try {
      const remote = await browser.storage.sync.get(SYNC_KEYS);
      cached = normalizeSettings({ ...local, ...remote });
      return cached;
    } catch (_) {
      // Fail closed to the local rules when Sync is unavailable.
    }
  }
  cached = local;
  return cached;
}

async function persistSettings(settings) {
  cached = normalizeSettings(settings);
  await browser.storage.local.set(cached);
  if (cached.syncRules && !cached.encryptedSync && await hasSyncConsent()) {
    try {
      await browser.storage.sync.set(pickSyncSettings(cached));
    } catch (_) {
      // Local protection remains active.
    }
  }
  await updateShieldBadge(cached);
  return cached;
}

function decodeLoose(value) {
  let out = String(value || "");
  for (let i = 0; i < 3; i += 1) {
    try {
      const decoded = decodeURIComponent(out.replace(/\+/g, "%20"));
      if (decoded === out) break;
      out = decoded;
    } catch (_) { break; }
  }
  return out;
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function domainMatches(hostname, rule) {
  const host = String(hostname || "").toLowerCase().replace(/\.$/, "");
  const domain = normalizeDomainRule(rule);
  return Boolean(domain) && (host === domain || host.endsWith(`.${domain}`));
}

function keywordMatches(haystack, keyword, settings) {
  if (!keyword) return false;
  if (settings.wholeWord) {
    const flags = settings.caseSensitive ? "u" : "iu";
    try {
      return new RegExp(`(^|[^\\p{L}\\p{N}_])${escapeRegex(keyword)}(?=$|[^\\p{L}\\p{N}_])`, flags)
        .test(haystack);
    } catch (_) { return false; }
  }
  return settings.caseSensitive
    ? haystack.includes(keyword)
    : haystack.toLocaleLowerCase().includes(keyword.toLocaleLowerCase());
}

function exactUrlKey(value) {
  try {
    const parsed = new URL(String(value).includes("://") ? value : `https://${value}`);
    if (!parsed.pathname) parsed.pathname = "/";
    return parsed.href;
  } catch (_) { return ""; }
}

function siteRoot(value) {
  try {
    const parsed = new URL(value);
    return `${parsed.protocol}//${parsed.host}/`;
  } catch (_) { return ""; }
}

function pathPrefixMatches(parsed, value) {
  const raw = String(value || "").trim();
  if (raw.startsWith("/")) return parsed.pathname.startsWith(raw);
  try {
    const rule = new URL(raw.includes("://") ? raw : `https://${raw}`);
    return domainMatches(parsed.hostname, rule.hostname) && parsed.pathname.startsWith(rule.pathname || "/");
  } catch (_) { return false; }
}

function visualRuleMatches(rule, url, title, settings) {
  let parsed = null;
  try { parsed = new URL(url); } catch (_) { parsed = null; }
  const normalizedUrl = decodeLoose(url);
  switch (rule.matcher) {
    case MATCHER.DOMAIN:
      return Boolean(parsed) && domainMatches(parsed.hostname, rule.value);
    case MATCHER.DOMAIN_EXCEPT_ROOT:
      return Boolean(parsed) && domainMatches(parsed.hostname, rule.value)
        && (parsed.pathname !== "/" || Boolean(parsed.search) || Boolean(parsed.hash));
    case MATCHER.PATH_PREFIX:
      return Boolean(parsed) && pathPrefixMatches(parsed, rule.value);
    case MATCHER.URL_CONTAINS:
      return keywordMatches(normalizedUrl, rule.value, settings);
    case MATCHER.TITLE_CONTAINS:
      return keywordMatches(String(title || ""), rule.value, settings);
    case MATCHER.QUERY_PARAMETER:
      if (!parsed) return false;
      for (const [key, value] of parsed.searchParams.entries()) {
        if ((!rule.queryParameter || key.localeCompare(rule.queryParameter, undefined, { sensitivity: settings.caseSensitive ? "variant" : "accent" }) === 0)
          && keywordMatches(value, rule.value, settings)) return true;
      }
      return false;
    case MATCHER.REGEX: {
      const flags = settings.caseSensitive ? "u" : "iu";
      try { return new RegExp(rule.value, flags).test(`${normalizedUrl}\n${title || ""}`); }
      catch (_) { return false; }
    }
    case MATCHER.EXACT_URL:
      return exactUrlKey(url) === exactUrlKey(rule.value);
    default:
      return false;
  }
}

function activeVisualRules(settings) {
  const activeProfiles = new Set(settings.activeProfiles);
  return settings.visualRules.filter((rule) => rule.enabled
    && activeProfiles.has(rule.profile)
    && (!rule.expiresAtEpochMillis || rule.expiresAtEpochMillis > Date.now()));
}

function decide(url, title, settings, { tabId = null, includeTransientProtection = true } = {}) {
  if (!settings.enabled || !url) return { action: ACTION.ALLOW, rule: null, collapsedUrl: "" };
  const oneShot = tabId != null ? oneShotTabs.get(tabId) : null;
  const oneShotActive = Boolean(oneShot?.protectedUrl && oneShot.protectedUrl === url);
  if (includeTransientProtection && (sessionBlockAll || Number(settings.temporaryUntil) > Date.now()
    || (tabId != null && sessionPrivateTabs.has(tabId)) || oneShotActive)) {
    return { action: ACTION.BLOCK, rule: null, collapsedUrl: "" };
  }

  const activeRules = activeVisualRules(settings);
  const allowed = activeRules.find((rule) => rule.action === ACTION.ALLOW && visualRuleMatches(rule, url, title, settings));
  if (allowed) return { action: ACTION.ALLOW, rule: allowed, collapsedUrl: "" };
  const matched = activeRules.find((rule) => rule.action !== ACTION.ALLOW && visualRuleMatches(rule, url, title, settings));
  if (matched) {
    return {
      action: matched.action,
      rule: matched,
      collapsedUrl: matched.action === ACTION.COLLAPSE ? siteRoot(url) : "",
    };
  }

  let hostname = "";
  try { hostname = new URL(url).hostname; } catch (_) { hostname = ""; }
  if (settings.domains.some((rule) => domainMatches(hostname, rule))) {
    return { action: ACTION.BLOCK, rule: null, collapsedUrl: "" };
  }
  const haystack = [String(url || ""), decodeLoose(url), String(title || "")].filter(Boolean).join("\n");
  if (settings.keywords.some((rule) => keywordMatches(haystack, rule, settings))) {
    return { action: ACTION.BLOCK, rule: null, collapsedUrl: "" };
  }
  const flags = settings.caseSensitive ? "u" : "iu";
  for (const pattern of settings.regex) {
    try {
      if (new RegExp(pattern, flags).test(haystack)) return { action: ACTION.BLOCK, rule: null, collapsedUrl: "" };
    } catch (_) { /* invalid user regex is ignored */ }
  }
  return { action: ACTION.ALLOW, rule: null, collapsedUrl: "" };
}

function shouldCloseRestoredTab(url, title, settings) {
  const matching = activeVisualRules(settings).filter((rule) => visualRuleMatches(rule, url, title, settings));
  const allow = matching.find((rule) => rule.action === ACTION.ALLOW);
  if (allow) return Boolean(allow.closeTab);
  return matching.some((rule) => rule.action !== ACTION.ALLOW && rule.closeTab);
}

function describeDecision(decision, settings, { tabId = null } = {}) {
  if (!settings.enabled) return "Protection is paused";
  if (sessionBlockAll) return "Session shield is active";
  if (Number(settings.temporaryUntil) > Date.now()) return "Temporary shield is active";
  if (tabId != null && sessionPrivateTabs.has(tabId)) {
    return inheritingPrivateTabs.has(tabId)
      ? "This tab and tabs opened from it are protected"
      : "This tab is protected until it closes";
  }
  if (tabId != null && oneShotTabs.has(tabId)) return "The next navigation is protected";
  if (decision.rule) {
    const labels = {
      [ACTION.ALLOW]: "Saved normally",
      [ACTION.BLOCK]: "Never saved",
      [ACTION.COLLAPSE]: "Collapsed to the homepage",
      [ACTION.FORGET_AFTER]: "Saved temporarily, then forgotten",
      [ACTION.FORGET_ON_RESTART]: "Forgotten at the next Firefox start",
    };
    return `${labels[decision.action] || decision.action} · ${decision.rule.name}`;
  }
  if (decision.action === ACTION.BLOCK) return "Matched a compatibility domain, word or regular expression";
  return "No matching rule; saved normally";
}

function shouldSuppress(url, title, settings) {
  return removesImmediately(decide(url, title, settings));
}

function removesImmediately(decision) {
  return decision.action === ACTION.BLOCK || decision.action === ACTION.COLLAPSE;
}

function shouldRemoveStoredVisit(decision, lastVisitTime, { includeSessionRules = false } = {}) {
  if (removesImmediately(decision)) return true;
  if (decision.action === ACTION.FORGET_ON_RESTART) return includeSessionRules;
  if (decision.action === ACTION.FORGET_AFTER) {
    const retention = Math.max(60000, Number(decision.rule?.retentionMillis || 0));
    return retention > 0 && Number(lastVisitTime || 0) <= Date.now() - retention;
  }
  return false;
}

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

async function updateDiagnostics(patch) {
  try {
    await browser.storage.local.set(patch);
    if (cached) cached = normalizeSettings({ ...cached, ...patch });
  } catch (_) { /* diagnostics never break protection */ }
}

function currentBuckets() {
  const day = Math.floor(Date.now() / 86400000);
  return { day, week: Math.floor(day / 7) };
}

async function recordProtection(eventCode, count = 1) {
  if (!count) return;
  const settings = await getSettings();
  const buckets = currentBuckets();
  const today = settings.dayBucket === buckets.day ? settings.todayProtected : 0;
  const week = settings.weekBucket === buckets.week ? settings.weekProtected : 0;
  const patch = {
    totalRemoved: Number(settings.totalRemoved || 0) + count,
    totalCollapsed: Number(settings.totalCollapsed || 0) + (eventCode === EVENT.COLLAPSED ? count : 0),
    todayProtected: today + count,
    weekProtected: week + count,
    dayBucket: buckets.day,
    weekBucket: buckets.week,
    lastRemovedAt: Date.now(),
    lastMatchAt: Date.now(),
    lastDecisionCode: eventCode,
  };
  await updateDiagnostics(patch);
  await updateShieldBadge(normalizeSettings({ ...settings, ...patch }));
}

async function updateShieldBadge(settings = null) {
  if (!browser.action?.setBadgeText) return;
  const state = settings || await getSettings();
  const text = state.enabled && state.todayProtected ? String(Math.min(999, state.todayProtected)) : "";
  await browser.action.setBadgeText({ text });
  if (browser.action.setBadgeBackgroundColor) {
    await browser.action.setBadgeBackgroundColor({ color: state.enabled ? "#5b5bd6" : "#777777" });
  }
}

async function deleteUrlWithRetries(url) {
  if (pendingDeletes.has(url)) return pendingDeletes.get(url);
  const task = (async () => {
    let ok = false;
    let lastError = null;
    for (const delay of DELETE_RETRY_DELAYS_MS) {
      if (delay) await sleep(delay);
      try { await browser.history.deleteUrl({ url }); ok = true; }
      catch (error) { lastError = error; }
    }
    if (!ok && lastError) await updateDiagnostics({ lastError: String(lastError?.message || lastError) });
    return ok;
  })().finally(() => pendingDeletes.delete(url));
  pendingDeletes.set(url, task);
  return task;
}

async function executeOptionalActions(decision, url, tabId) {
  const rule = decision.rule;
  if (!rule) return;
  let parsed = null;
  try { parsed = new URL(url); } catch (_) { parsed = null; }

  try {
    if (rule.clearCookies && parsed && browser.cookies) {
      const cookies = await browser.cookies.getAll({ domain: parsed.hostname });
      for (const cookie of cookies) {
        const scheme = cookie.secure ? "https:" : "http:";
        const host = cookie.domain.replace(/^\./, "");
        await browser.cookies.remove({ url: `${scheme}//${host}${cookie.path || "/"}`, name: cookie.name });
      }
    }
    if (rule.clearCache && browser.browsingData) await browser.browsingData.removeCache({});
    if (rule.clearDownloads && browser.downloads) {
      const items = await browser.downloads.search({ query: parsed ? [parsed.hostname] : [url] });
      for (const item of items) await browser.downloads.erase({ id: item.id });
    }
    // closeTab is applied only to restored tabs during the next Firefox startup.
    // A matching live page must stay usable throughout the current session.
  } catch (error) {
    await updateDiagnostics({ lastError: `Optional action: ${String(error?.message || error)}` });
  }
}

async function applyDecision(url, title = "", { countRemoval = false, tabId = null } = {}) {
  await ensureEphemeralState();
  const settings = await getSettings();
  const decision = decide(url, title, settings, { tabId });
  if (decision.action === ACTION.ALLOW) return false;
  await executeOptionalActions(decision, url, tabId);
  if (!removesImmediately(decision)) return false;
  const deleted = await deleteUrlWithRetries(url);
  if (decision.action === ACTION.COLLAPSE && decision.collapsedUrl && browser.history.addUrl) {
    await browser.history.addUrl({ url: decision.collapsedUrl });
  }
  if (deleted && countRemoval) {
    await recordProtection(decision.action === ACTION.COLLAPSE ? EVENT.COLLAPSED : EVENT.PREVENTED, 1);
  } else {
    await updateDiagnostics({ lastMatchAt: Date.now(), lastDecisionCode: decision.action === ACTION.COLLAPSE ? EVENT.COLLAPSED : EVENT.PREVENTED });
  }
  return deleted;
}

async function scanHistory({ execute = false, includeSessionRules = false, restartOnly = false } = {}) {
  const settings = await getSettings();
  if (!settings.enabled) return { removed: 0, scanned: 0, collapsed: 0, matching: 0 };
  let endTime = Date.now() + 1;
  let removed = 0;
  let collapsed = 0;
  let scanned = 0;
  const seenBoundaries = new Set();
  while (endTime > 0) {
    const batch = await browser.history.search({ text: "", startTime: 0, endTime, maxResults: 1000 });
    if (!batch.length) break;
    scanned += batch.length;
    for (const item of batch) {
      const decision = decide(
        item.url,
        item.title || "",
        settings,
        { includeTransientProtection: false },
      );
      if (restartOnly && decision.action !== ACTION.FORGET_ON_RESTART) continue;
      if (!shouldRemoveStoredVisit(decision, item.lastVisitTime, { includeSessionRules })) continue;
      removed += 1;
      if (decision.action === ACTION.COLLAPSE) collapsed += 1;
      if (execute) {
        try {
          await browser.history.deleteUrl({ url: item.url });
          if (decision.action === ACTION.COLLAPSE && decision.collapsedUrl && browser.history.addUrl) {
            await browser.history.addUrl({ url: decision.collapsedUrl });
          }
        } catch (_) { removed -= 1; }
      }
    }
    const oldest = Math.min(...batch.map((item) => Number(item.lastVisitTime || 0)));
    if (!Number.isFinite(oldest) || oldest <= 0 || seenBoundaries.has(oldest)) break;
    seenBoundaries.add(oldest);
    endTime = Math.max(0, oldest - 0.001);
    if (batch.length < 1000) break;
  }
  if (execute) {
    await recordProtection(EVENT.CLEANUP, removed);
    await updateDiagnostics({ lastScrubAt: Date.now(), lastError: "" });
  }
  return { removed: execute ? removed : 0, scanned, collapsed, matching: removed };
}

async function scrubAllHistory({ includeSessionRules = false, restartOnly = false } = {}) {
  return scanHistory({ execute: true, includeSessionRules, restartOnly });
}

async function scheduleAlarm() {
  const settings = await getSettings();
  await browser.alarms.clear(ALARM_NAME);
  if (settings.enabled) browser.alarms.create(ALARM_NAME, { periodInMinutes: settings.scrubEveryMinutes });
}

async function runSelfTest() {
  const failures = [];
  let total = 0;
  const check = (name, value) => { total += 1; if (!value) failures.push(name); };
  const base = normalizeSettings({ enabled: true });
  check("legacy-domain", shouldSuppress("https://self-test.invalid/x", "", normalizeSettings({ ...base, domains: ["self-test.invalid"] })));
  const rules = [normalizeVisualRule({
    id: "self-test", name: "self-test", matcher: MATCHER.DOMAIN_EXCEPT_ROOT,
    value: "self-test.invalid", action: ACTION.COLLAPSE,
  })];
  const collapseSettings = normalizeSettings({ ...base, visualRules: rules });
  check("allow-root", decide("https://self-test.invalid/", "", collapseSettings).action === ACTION.ALLOW);
  check("collapse-path", decide("https://self-test.invalid/path", "", collapseSettings).collapsedUrl === "https://self-test.invalid/");
  check("counter-shape", ["totalRemoved", "todayProtected", "weekProtected"].every((key) => Number.isFinite(base[key])));
  check("cookie-default", !rules[0].clearCookies);
  check("restored-tab-default", !rules[0].closeTab);
  const delayedSettings = normalizeSettings({ ...base, visualRules: [normalizeVisualRule({
    id: "self-test-delay", name: "self-test-delay", matcher: MATCHER.DOMAIN,
    value: "delay-self-test.invalid", action: ACTION.FORGET_AFTER, retentionMillis: 3600000,
  })] });
  check("delayed-not-immediate", !shouldSuppress("https://delay-self-test.invalid/page", "", delayedSettings));
  const result = { ok: failures.length === 0, passed: total - failures.length, total, failures };
  await updateDiagnostics({
    selfTestOk: result.ok,
    selfTestPassed: result.passed,
    selfTestTotal: result.total,
    lastSelfTestAt: Date.now(),
  });
  return result;
}

function bytesToBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function backupPayload(settings) {
  return {
    format: "fenix-privacy-rules",
    version: 2,
    createdAt: Date.now(),
    visualRules: JSON.stringify(settings.visualRules),
    activeProfiles: settings.activeProfiles.join("\n"),
    domains: settings.domains.join("\n"),
    keywords: settings.keywords.join("\n"),
    regex: settings.regex.join("\n"),
    matchUrl: true,
    matchTitle: true,
    decodeUrl: true,
    caseSensitive: settings.caseSensitive,
    wholeWords: settings.wholeWord,
  };
}

async function deriveBackupKey(passphrase, salt, usage) {
  const encoder = new TextEncoder();
  const material = await crypto.subtle.importKey("raw", encoder.encode(passphrase), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt, iterations: PBKDF2_ITERATIONS, hash: "SHA-256" },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    [usage],
  );
}

async function encryptRuleBundle(settings, passphrase) {
  if (String(passphrase || "").length < 8) throw new Error("Passphrase must contain at least 8 characters");
  const encoder = new TextEncoder();
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveBackupKey(passphrase, salt, "encrypt");
  const cipher = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: encoder.encode(BACKUP_HEADER), tagLength: 128 },
    key,
    encoder.encode(JSON.stringify(backupPayload(settings))),
  ));
  const joined = new Uint8Array(salt.length + iv.length + cipher.length);
  joined.set(salt, 0); joined.set(iv, salt.length); joined.set(cipher, salt.length + iv.length);
  return BACKUP_HEADER + bytesToBase64(joined);
}

async function decryptRuleBundle(bundle, passphrase) {
  if (!String(bundle).startsWith(BACKUP_HEADER)) throw new Error("Unsupported encrypted bundle");
  const bytes = base64ToBytes(String(bundle).slice(BACKUP_HEADER.length).trim());
  const salt = bytes.slice(0, 16); const iv = bytes.slice(16, 28); const cipher = bytes.slice(28);
  const key = await deriveBackupKey(passphrase, salt, "decrypt");
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv, additionalData: new TextEncoder().encode(BACKUP_HEADER), tagLength: 128 },
    key,
    cipher,
  );
  const payload = JSON.parse(new TextDecoder().decode(plain));
  if (payload.format !== "fenix-privacy-rules") throw new Error("Wrong bundle type");
  return normalizeSettings({
    ...(await getSettings()),
    visualRules: JSON.parse(payload.visualRules || "[]"),
    activeProfiles: cleanLines(payload.activeProfiles),
    domains: cleanLines(payload.domains),
    keywords: cleanLines(payload.keywords),
    regex: cleanLines(payload.regex),
    caseSensitive: Boolean(payload.caseSensitive),
    wholeWord: Boolean(payload.wholeWords),
  });
}

async function setupContextMenus() {
  if (!browser.contextMenus) return;
  try { await browser.contextMenus.removeAll(); } catch (_) { /* first run */ }
  const items = [
    ["block-site", "Never save this site"],
    ["block-section", "Never save this section"],
    ["keep-homepage", "Keep only this site's homepage"],
    ["allow-page", "Always allow this exact page"],
    ["collapse-site", "Collapse this site to its homepage"],
    ["forget-restart", "Keep history only until Firefox restarts"],
    ["forget-24h", "Forget this site after 24 hours"],
    ["private-tab", "Hide history for this tab until it closes"],
  ];
  for (const [id, title] of items) browser.contextMenus.create({ id, title, contexts: ["page"] });
}

async function addQuickRule(menuId, url, tabId) {
  await ensureEphemeralState();
  if (menuId === "private-tab") {
    if (tabId != null) {
      sessionPrivateTabs.add(tabId);
      inheritingPrivateTabs.add(tabId);
      await persistEphemeralState();
      await updatePageShield(tabId, url, "");
    }
    return;
  }
  let parsed;
  try { parsed = new URL(url); } catch (_) { return; }
  const settings = await getSettings();
  const first = parsed.pathname.split("/").find(Boolean);
  const rule = normalizeVisualRule({
    id: cryptoRandomId(),
    name: menuId,
    profile: "Default",
    matcher: menuId === "allow-page" ? MATCHER.EXACT_URL
      : menuId === "block-section" ? MATCHER.PATH_PREFIX : MATCHER.DOMAIN_EXCEPT_ROOT,
    value: menuId === "allow-page" ? url
      : menuId === "block-section" ? `${parsed.hostname}/${first || ""}` : parsed.hostname,
    action: menuId === "allow-page" ? ACTION.ALLOW
      : menuId === "collapse-site" ? ACTION.COLLAPSE
        : menuId === "forget-restart" ? ACTION.FORGET_ON_RESTART
          : menuId === "forget-24h" ? ACTION.FORGET_AFTER : ACTION.BLOCK,
    retentionMillis: menuId === "forget-24h" ? 24 * 60 * 60 * 1000 : 0,
  });
  if (["block-site", "forget-restart", "forget-24h"].includes(menuId)) rule.matcher = MATCHER.DOMAIN;
  await persistSettings({ ...settings, visualRules: [...settings.visualRules, rule] });
  await refreshOpenShields();
}

async function advanceOneShot(tabId, url) {
  await ensureEphemeralState();
  const pending = oneShotTabs.get(tabId);
  if (!pending || !url) return;
  let changed = false;
  if (!pending.protectedUrl && url !== pending.armedUrl) {
    pending.protectedUrl = url;
    oneShotTabs.set(tabId, pending);
    changed = true;
  } else if (pending.protectedUrl && pending.protectedUrl !== url) {
    oneShotTabs.delete(tabId);
    changed = true;
  }
  if (changed) await persistEphemeralState();
}

async function clearTabProtection(tabId) {
  await ensureEphemeralState();
  sessionPrivateTabs.delete(tabId);
  inheritingPrivateTabs.delete(tabId);
  oneShotTabs.delete(tabId);
  await persistEphemeralState();
}

async function toggleTabShield(tabId, url, { inherit = true } = {}) {
  await ensureEphemeralState();
  if (!Number.isInteger(tabId)) return { active: false };
  if (sessionPrivateTabs.has(tabId)) {
    sessionPrivateTabs.delete(tabId);
    inheritingPrivateTabs.delete(tabId);
    oneShotTabs.delete(tabId);
  } else {
    sessionPrivateTabs.add(tabId);
    if (inherit) inheritingPrivateTabs.add(tabId);
  }
  await persistEphemeralState();
  await updatePageShield(tabId, url, "");
  return { active: sessionPrivateTabs.has(tabId), inherit: inheritingPrivateTabs.has(tabId) };
}

async function closeRestoredMatchingTabs(settings, capturedStartupIds = null) {
  if (!browser.tabs?.query || !browser.tabs?.remove) return 0;
  // Capture the startup tab set before waiting for Firefox to finish hydration. A tab created by
  // the user after this point is live and must never be closed by a closeTab rule.
  const startupIds = capturedStartupIds || new Set((await browser.tabs.query({}))
      .filter((tab) => Number.isInteger(tab.id))
      .map((tab) => tab.id));
  await sleep(750);
  const tabs = await browser.tabs.query({});
  const ids = tabs
    .filter((tab) => startupIds.has(tab.id)
      && shouldCloseRestoredTab(tab.url || "", tab.title || "", settings))
    .map((tab) => tab.id);
  if (ids.length) await browser.tabs.remove(ids);
  return ids.length;
}

function shieldPresentation(decision, settings, tabId) {
  if (!settings.enabled) return { icon: "icons/shield-paused.svg", title: "Fenix Privacy: paused" };
  if (sessionBlockAll || Number(settings.temporaryUntil) > Date.now()
    || (Number.isInteger(tabId) && sessionPrivateTabs.has(tabId))) {
    return { icon: "icons/shield-block.svg", title: "Fenix Privacy: history blocked" };
  }
  if (decision.action === ACTION.BLOCK) {
    return { icon: "icons/shield-block.svg", title: "Fenix Privacy: this page is not saved" };
  }
  if (decision.action === ACTION.COLLAPSE) {
    return { icon: "icons/shield-collapse.svg", title: "Fenix Privacy: only the homepage is saved" };
  }
  if ([ACTION.FORGET_AFTER, ACTION.FORGET_ON_RESTART].includes(decision.action)) {
    return { icon: "icons/shield-temporary.svg", title: "Fenix Privacy: this history is temporary" };
  }
  return { icon: "icons/shield-idle.svg", title: "Fenix Privacy: saved normally" };
}

async function updatePageShield(tabId, url, title) {
  if (!Number.isInteger(tabId) || !url) return;
  await ensureEphemeralState();
  const isWebPage = /^https?:/i.test(url);
  if (browser.pageAction) {
    try {
      if (!isWebPage) await browser.pageAction.hide(tabId);
      else await browser.pageAction.show(tabId);
    } catch (_) { /* unsupported internal pages */ }
  }
  if (!isWebPage) return;
  const settings = await getSettings();
  const decision = decide(url, title, settings, { tabId });
  const presentation = shieldPresentation(decision, settings, tabId);
  try {
    if (browser.pageAction?.setIcon) await browser.pageAction.setIcon({ tabId, path: presentation.icon });
    if (browser.pageAction?.setTitle) await browser.pageAction.setTitle({ tabId, title: presentation.title });
    if (browser.action?.setIcon) await browser.action.setIcon({ tabId, path: presentation.icon });
    if (browser.action?.setTitle) await browser.action.setTitle({ tabId, title: presentation.title });
  } catch (_) { /* presentation never breaks protection */ }
}

async function refreshOpenShields() {
  if (!browser.tabs?.query) return;
  const tabs = await browser.tabs.query({});
  await Promise.all(tabs.map((tab) => updatePageShield(tab.id, tab.url || "", tab.title || "")));
}

async function initializeBackground({ scrub = false, closeRestored = false } = {}) {
  try {
    await ensureEphemeralState();
    const startupIds = closeRestored && browser.tabs?.query
      ? new Set((await browser.tabs.query({})).filter((tab) => Number.isInteger(tab.id)).map((tab) => tab.id))
      : null;
    cached = null;
    const settings = await getSettings();
    await scheduleAlarm();
    await setupContextMenus();
    await runSelfTest();
    await updateShieldBadge(settings);
    if (scrub && (settings.scrubOnStartup || closeRestored)) {
      await scrubAllHistory({
        includeSessionRules: closeRestored,
        restartOnly: closeRestored && !settings.scrubOnStartup,
      });
    }
    if (closeRestored) await closeRestoredMatchingTabs(settings, startupIds);
    await refreshOpenShields();
    await updateDiagnostics({ lastError: "" });
  } catch (error) {
    await updateDiagnostics({ lastError: String(error?.message || error) });
  }
}

browser.history.onVisited.addListener((item) => {
  void applyDecision(item.url, item.title || "", { countRemoval: true });
});
browser.webNavigation.onCommitted.addListener((details) => {
  if (details.frameId === 0) {
    void (async () => {
      await advanceOneShot(details.tabId, details.url);
      await applyDecision(details.url, "", { tabId: details.tabId });
      await updatePageShield(details.tabId, details.url, "");
    })();
  }
});
if (browser.webNavigation.onHistoryStateUpdated) {
  browser.webNavigation.onHistoryStateUpdated.addListener((details) => {
    if (details.frameId === 0) {
      void (async () => {
        await advanceOneShot(details.tabId, details.url);
        await applyDecision(details.url, "", { tabId: details.tabId });
        await updatePageShield(details.tabId, details.url, "");
      })();
    }
  });
}
browser.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  const url = changeInfo.url || tab.url;
  const title = changeInfo.title || tab.title || "";
  if (url && (changeInfo.url || changeInfo.title || changeInfo.status === "complete")) {
    void (async () => {
      if (changeInfo.url) await advanceOneShot(tabId, url);
      await applyDecision(url, title, { tabId });
      await updatePageShield(tabId, url, title);
    })();
  }
});
if (browser.tabs.onCreated) {
  browser.tabs.onCreated.addListener((tab) => {
    void (async () => {
      await ensureEphemeralState();
      if (Number.isInteger(tab.id) && Number.isInteger(tab.openerTabId)
        && sessionPrivateTabs.has(tab.openerTabId) && inheritingPrivateTabs.has(tab.openerTabId)) {
        sessionPrivateTabs.add(tab.id);
        inheritingPrivateTabs.add(tab.id);
        await persistEphemeralState();
      }
      if (Number.isInteger(tab.id) && tab.url) await updatePageShield(tab.id, tab.url, tab.title || "");
    })();
  });
}
if (browser.tabs.onActivated) {
  browser.tabs.onActivated.addListener(async ({ tabId }) => {
    try {
      const tab = await browser.tabs.get(tabId);
      await updatePageShield(tabId, tab.url || "", tab.title || "");
    } catch (_) { /* tab may have closed */ }
  });
}
if (browser.tabs.onRemoved) browser.tabs.onRemoved.addListener((tabId) => { void clearTabProtection(tabId); });
browser.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === ALARM_NAME) void scrubAllHistory({ includeSessionRules: false });
});
browser.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "sync" && areaName !== "local") return;
  cached = null;
  if (Object.keys(changes || {}).some((key) => ["enabled", "scrubEveryMinutes", "syncRules"].includes(key))) {
    void scheduleAlarm();
  }
  if (Object.keys(changes || {}).some((key) => SHIELD_SETTING_KEYS.includes(key))) void refreshOpenShields();
});
browser.runtime.onInstalled.addListener(() => { void initializeBackground({ scrub: true, closeRestored: false }); });
browser.runtime.onStartup.addListener(() => {
  void (async () => {
    await resetEphemeralState();
    await initializeBackground({ scrub: true, closeRestored: true });
  })();
});
if (browser.contextMenus?.onClicked) {
  browser.contextMenus.onClicked.addListener((info, tab) => {
    if (String(info.menuItemId).startsWith("block-")
      || ["keep-homepage", "allow-page", "collapse-site", "forget-restart", "forget-24h", "private-tab"].includes(info.menuItemId)) {
      void addQuickRule(info.menuItemId, info.pageUrl || tab?.url || "", tab?.id);
    }
  });
}
if (browser.permissions?.onRemoved) {
  browser.permissions.onRemoved.addListener((permissions) => {
    if (permissions.data_collection?.some((permission) => SYNC_DATA_PERMISSIONS.includes(permission))) {
      cached = null;
      void browser.storage.local.set({ syncRules: false });
    }
  });
}

browser.runtime.onMessage.addListener(async (message) => {
  if (!message || typeof message !== "object") return undefined;
  await ensureEphemeralState();
  if (message.type === "get-settings") return getSettings();
  if (message.type === "set-settings") {
    const updated = await persistSettings(message.settings || {});
    await scheduleAlarm();
    await refreshOpenShields();
    return updated;
  }
  if (message.type === "preview-scrub") return scanHistory({ execute: false, includeSessionRules: true });
  if (message.type === "scrub-now") return scrubAllHistory({ includeSessionRules: true });
  if (message.type === "test-rule") {
    const settings = normalizeSettings(message.settings || await getSettings());
    const result = decide(message.url || "", message.title || "", settings);
    return { match: result.action !== ACTION.ALLOW, action: result.action, collapsedUrl: result.collapsedUrl, ruleName: result.rule?.name || "" };
  }
  if (message.type === "temporary-mode") {
    const settings = await getSettings();
    if (message.mode === "session") sessionBlockAll = true;
    else if (message.mode === "off") { sessionBlockAll = false; await persistSettings({ ...settings, temporaryUntil: 0 }); }
    else await persistSettings({ ...settings, temporaryUntil: Date.now() + Math.max(1, Number(message.minutes || 15)) * 60000 });
    await persistEphemeralState();
    await refreshOpenShields();
    return { sessionBlockAll, temporaryUntil: (await getSettings()).temporaryUntil };
  }
  if (message.type === "temporary-tab") {
    return toggleTabShield(message.tabId, message.url || "", { inherit: message.inherit !== false });
  }
  if (message.type === "protect-next-navigation") {
    if (!Number.isInteger(message.tabId)) return { armed: false };
    oneShotTabs.set(message.tabId, { armedUrl: String(message.url || ""), protectedUrl: "" });
    await persistEphemeralState();
    await updatePageShield(message.tabId, message.url || "", "");
    return { armed: true };
  }
  if (message.type === "quick-action") {
    await addQuickRule(String(message.action || ""), String(message.url || ""), message.tabId);
    return { ok: true };
  }
  if (message.type === "get-page-status") {
    const settings = await getSettings();
    const result = decide(message.url || "", message.title || "", settings, { tabId: message.tabId });
    return {
      action: result.action,
      ruleName: result.rule?.name || "",
      matcher: result.rule?.matcher || "",
      reason: describeDecision(result, settings, { tabId: message.tabId }),
      tabProtected: Number.isInteger(message.tabId) && sessionPrivateTabs.has(message.tabId),
      inherit: Number.isInteger(message.tabId) && inheritingPrivateTabs.has(message.tabId),
      nextNavigationArmed: Number.isInteger(message.tabId) && oneShotTabs.has(message.tabId),
    };
  }
  if (message.type === "export-encrypted") return { bundle: await encryptRuleBundle(await getSettings(), message.passphrase || "") };
  if (message.type === "import-encrypted") return persistSettings(await decryptRuleBundle(message.bundle || "", message.passphrase || ""));
  if (message.type === "push-encrypted-sync") {
    if (!await hasSyncConsent()) throw new Error("Firefox Sync data permission is required");
    const bundle = await encryptRuleBundle(await getSettings(), message.passphrase || "");
    await browser.storage.sync.set({ encryptedRuleBundle: bundle });
    return { ok: true };
  }
  if (message.type === "pull-encrypted-sync") {
    if (!await hasSyncConsent()) throw new Error("Firefox Sync data permission is required");
    const remote = await browser.storage.sync.get("encryptedRuleBundle");
    if (!remote.encryptedRuleBundle) throw new Error("No encrypted rule bundle in Firefox Sync");
    return persistSettings(await decryptRuleBundle(remote.encryptedRuleBundle, message.passphrase || ""));
  }
  if (message.type === "self-test") return runSelfTest();
  if (message.type === "health") {
    const settings = await getSettings();
    const manifest = browser.runtime.getManifest();
    return {
      ok: !settings.lastError && settings.selfTestOk,
      version: manifest.version,
      updateUrl: manifest.browser_specific_settings?.gecko?.update_url || "",
      enabled: settings.enabled,
      rules: settings.domains.length + settings.keywords.length + settings.regex.length + settings.visualRules.length,
      activeProfiles: settings.activeProfiles.length,
      today: settings.todayProtected,
      week: settings.weekProtected,
      collapsed: settings.totalCollapsed,
      lastMatchAt: settings.lastMatchAt,
      lastError: settings.lastError,
      selfTestOk: settings.selfTestOk,
      selfTestPassed: settings.selfTestPassed,
      selfTestTotal: settings.selfTestTotal,
    };
  }
  return undefined;
});

void initializeBackground({ scrub: false, closeRestored: false });
