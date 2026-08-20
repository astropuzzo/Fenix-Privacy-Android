"use strict";

const DEFAULTS = Object.freeze({
  enabled: true,
  caseSensitive: false,
  wholeWord: false,
  domains: [],
  keywords: [],
  regex: [],
  scrubOnStartup: true,
  scrubEveryMinutes: 15,
  syncRules: false,
  totalRemoved: 0,
  lastRemovedAt: 0,
  lastScrubAt: 0,
});

const ALARM_NAME = "fenix-privacy-history-scrub";
const SYNC_DATA_PERMISSIONS = Object.freeze(["browsingActivity", "searchTerms", "technicalAndInteraction"]);
const SYNC_KEYS = Object.freeze([
  "enabled", "caseSensitive", "wholeWord", "domains", "keywords", "regex",
  "scrubOnStartup", "scrubEveryMinutes", "syncRules",
]);
let cached = null;

function cleanLines(value) {
  const items = Array.isArray(value) ? value : String(value || "").split(/\r?\n/);
  return [...new Set(items.map((x) => String(x).trim()).filter(Boolean))];
}

function normalizeSettings(raw = {}) {
  const merged = { ...DEFAULTS, ...raw };
  merged.domains = cleanLines(merged.domains).map((d) => d.toLowerCase().replace(/^\*\./, "").replace(/^\.+|\.+$/g, ""));
  merged.keywords = cleanLines(merged.keywords);
  merged.regex = cleanLines(merged.regex);
  merged.scrubEveryMinutes = Math.max(15, Number(merged.scrubEveryMinutes) || 15);
  return merged;
}

async function hasSyncConsent() {
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
  if (await hasSyncConsent()) {
    try {
      const remote = await browser.storage.sync.get(SYNC_KEYS);
      cached = normalizeSettings({ ...local, ...remote });
      return cached;
    } catch (error) {
      console.warn("Fenix Privacy: storage.sync read failed, using local settings", error);
    }
  }
  cached = local;
  return cached;
}

async function persistSettings(settings) {
  cached = normalizeSettings(settings);
  await browser.storage.local.set(cached);
  if (cached.syncRules && await hasSyncConsent()) {
    try {
      await browser.storage.sync.set(pickSyncSettings(cached));
    } catch (error) {
      console.warn("Fenix Privacy: storage.sync write failed; local settings remain active", error);
    }
  }
  return cached;
}

function decodeLoose(value) {
  let out = String(value || "");
  for (let i = 0; i < 3; i += 1) {
    try {
      const decoded = decodeURIComponent(out.replace(/\+/g, "%20"));
      if (decoded === out) break;
      out = decoded;
    } catch (_) {
      break;
    }
  }
  return out;
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function domainMatches(hostname, rule) {
  const host = String(hostname || "").toLowerCase().replace(/\.$/, "");
  const domain = String(rule || "").toLowerCase().replace(/^\*\./, "").replace(/^\.+|\.+$/g, "");
  return Boolean(domain) && (host === domain || host.endsWith(`.${domain}`));
}

function keywordMatches(haystack, keyword, settings) {
  if (!keyword) return false;
  if (settings.wholeWord) {
    const flags = settings.caseSensitive ? "u" : "iu";
    try {
      return new RegExp(`(^|[^\\p{L}\\p{N}_])${escapeRegex(keyword)}(?=$|[^\\p{L}\\p{N}_])`, flags).test(haystack);
    } catch (_) {
      return false;
    }
  }
  if (settings.caseSensitive) return haystack.includes(keyword);
  return haystack.toLocaleLowerCase().includes(keyword.toLocaleLowerCase());
}

function buildHaystack(url, title) {
  const pieces = [String(url || ""), decodeLoose(url), String(title || "")];
  try {
    const parsed = new URL(url);
    for (const [key, value] of parsed.searchParams.entries()) {
      pieces.push(key, value, decodeLoose(key), decodeLoose(value));
    }
  } catch (_) {
    // about:, file:, malformed or internal URLs can still be keyword matched as raw strings.
  }
  return pieces.filter(Boolean).join("\n");
}

function shouldSuppress(url, title, settings) {
  if (!settings.enabled || !url) return false;
  let hostname = "";
  try {
    hostname = new URL(url).hostname;
  } catch (_) {
    hostname = "";
  }
  if (settings.domains.some((rule) => domainMatches(hostname, rule))) return true;

  const haystack = buildHaystack(url, title);
  if (settings.keywords.some((rule) => keywordMatches(haystack, rule, settings))) return true;

  const flags = settings.caseSensitive ? "u" : "iu";
  for (const pattern of settings.regex) {
    try {
      if (new RegExp(pattern, flags).test(haystack)) return true;
    } catch (error) {
      console.warn(`Fenix Privacy: invalid regex ignored: ${pattern}`, error);
    }
  }
  return false;
}

async function recordRemoval(count = 1) {
  if (!count) return;
  const settings = await getSettings();
  cached = normalizeSettings({
    ...settings,
    totalRemoved: Number(settings.totalRemoved || 0) + count,
    lastRemovedAt: Date.now(),
  });
  await browser.storage.local.set({
    totalRemoved: cached.totalRemoved,
    lastRemovedAt: cached.lastRemovedAt,
  });
}

async function removeUrlIfNeeded(url, title = "") {
  const settings = await getSettings();
  if (!shouldSuppress(url, title, settings)) return false;
  try {
    await browser.history.deleteUrl({ url });
    await recordRemoval(1);
    return true;
  } catch (error) {
    console.warn("Fenix Privacy: unable to delete history URL", url, error);
    return false;
  }
}

async function scrubAllHistory() {
  const settings = await getSettings();
  if (!settings.enabled) return { removed: 0, scanned: 0 };

  let endTime = Date.now() + 1;
  let removed = 0;
  let scanned = 0;
  const seenBoundaries = new Set();

  while (endTime > 0) {
    const batch = await browser.history.search({
      text: "",
      startTime: 0,
      endTime,
      maxResults: 1000,
    });
    if (!batch.length) break;

    scanned += batch.length;
    const matching = batch.filter((item) => shouldSuppress(item.url, item.title || "", settings));
    for (const item of matching) {
      try {
        await browser.history.deleteUrl({ url: item.url });
        removed += 1;
      } catch (error) {
        console.warn("Fenix Privacy: scrub delete failed", item.url, error);
      }
    }

    const oldest = Math.min(...batch.map((item) => Number(item.lastVisitTime || 0)));
    if (!Number.isFinite(oldest) || oldest <= 0 || seenBoundaries.has(oldest)) break;
    seenBoundaries.add(oldest);
    endTime = Math.max(0, oldest - 0.001);
    if (batch.length < 1000) break;
  }

  const latest = await getSettings();
  cached = normalizeSettings({
    ...latest,
    totalRemoved: Number(latest.totalRemoved || 0) + removed,
    lastRemovedAt: removed ? Date.now() : latest.lastRemovedAt,
    lastScrubAt: Date.now(),
  });
  await browser.storage.local.set({
    totalRemoved: cached.totalRemoved,
    lastRemovedAt: cached.lastRemovedAt,
    lastScrubAt: cached.lastScrubAt,
  });
  return { removed, scanned };
}

async function scheduleAlarm() {
  const settings = await getSettings();
  await browser.alarms.clear(ALARM_NAME);
  if (settings.enabled) {
    browser.alarms.create(ALARM_NAME, { periodInMinutes: settings.scrubEveryMinutes });
  }
}

browser.history.onVisited.addListener((item) => {
  void removeUrlIfNeeded(item.url, item.title || "");
});

browser.webNavigation.onCommitted.addListener((details) => {
  if (details.frameId === 0) void removeUrlIfNeeded(details.url, "");
});

browser.tabs.onUpdated.addListener((_tabId, changeInfo, tab) => {
  const url = changeInfo.url || tab.url;
  const title = changeInfo.title || tab.title || "";
  if (url && (changeInfo.url || changeInfo.title || changeInfo.status === "complete")) {
    void removeUrlIfNeeded(url, title);
  }
});

browser.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === ALARM_NAME) void scrubAllHistory();
});

browser.storage.onChanged.addListener((_changes, areaName) => {
  if (areaName === "sync" || areaName === "local") {
    cached = null;
    void scheduleAlarm();
  }
});

browser.runtime.onInstalled.addListener(async () => {
  const current = await getSettings();
  await persistSettings(current);
  await scheduleAlarm();
  if (current.scrubOnStartup) await scrubAllHistory();
});

browser.runtime.onStartup.addListener(async () => {
  cached = null;
  const settings = await getSettings();
  await scheduleAlarm();
  if (settings.scrubOnStartup) await scrubAllHistory();
});

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
  if (message.type === "get-settings") return getSettings();
  if (message.type === "set-settings") {
    const updated = await persistSettings(message.settings || {});
    await scheduleAlarm();
    return updated;
  }
  if (message.type === "scrub-now") return scrubAllHistory();
  if (message.type === "test-rule") {
    const settings = normalizeSettings(message.settings || await getSettings());
    return { match: shouldSuppress(message.url || "", message.title || "", settings) };
  }
  return undefined;
});
