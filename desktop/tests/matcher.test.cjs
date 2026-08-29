const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const { webcrypto } = require("node:crypto");

const optionsHtml = fs.readFileSync(
  path.join(__dirname, "..", "firefox-extension", "options.html"),
  "utf8",
);
const requiredOptionIds = [
  "activeProfiles", "addRule", "backupPassphrase", "cancelEdit", "caseSensitive",
  "clean", "cleanupResult", "domains", "enabled", "encryptedSync", "exportEncrypted",
  "importEncrypted", "keywords", "metricCollapsed", "metricHealth", "metricToday",
  "metricTotal", "metricWeek", "openAddons", "previewClean", "pullEncryptedSync",
  "pushEncryptedSync", "queryParameterRow", "regex", "resetCounter", "ruleAction",
  "ruleClearCache", "ruleClearCookies", "ruleClearDownloads", "ruleCloseTab", "ruleExpiry",
  "ruleMatcher", "ruleName", "ruleProfile", "ruleQueryParameter", "ruleRetentionHours", "ruleValue", "save",
  "scrubEveryMinutes", "scrubOnStartup", "selfTest", "selfTestResult", "status", "syncRules",
  "temporaryStatus", "test", "testResult", "testTitle", "testUrl", "updateCenter", "showQr",
  "qrDialog", "qrImage", "qrStatus", "retentionRow",
  "visualRuleList", "wholeWord",
];
for (const id of requiredOptionIds) {
  assert.match(optionsHtml, new RegExp(`\\bid=["']${id}["']`), `options.html must contain #${id}`);
}
const optionIds = [...optionsHtml.matchAll(/\bid=["']([^"']+)["']/g)].map((match) => match[1]);
assert.equal(new Set(optionIds).size, optionIds.length, "options.html IDs must be unique");
assert.match(optionsHtml, /<\/html>\s*$/, "options.html must be a complete document");

const popupHtml = fs.readFileSync(
  path.join(__dirname, "..", "firefox-extension", "popup.html"),
  "utf8",
);
for (const id of [
  "allowPage", "blockSite", "forget24", "forgetRestart", "keepHomepage", "pageAction",
  "pageHost", "pageReason", "pageShield", "protectNext", "temp15", "temp60", "tempSession", "toggleTab",
]) {
  assert.match(popupHtml, new RegExp(`\\bid=["']${id}["']`), `popup.html must contain #${id}`);
}

function event() {
  const listeners = [];
  return {
    listeners,
    addListener(fn) { listeners.push(fn); },
    emit(...args) { for (const fn of listeners) fn(...args); },
  };
}

const state = {};
const ephemeralState = {};
const deleteCalls = [];
const addCalls = [];
const alarmPeriods = [];
const onVisited = event();
const onCommitted = event();
const onHistoryStateUpdated = event();
const onTabUpdated = event();
const onTabCreated = event();
const onTabActivated = event();
const onTabRemoved = event();
const onStorageChanged = event();
const onAlarm = event();
const onMessage = event();
const removedTabCalls = [];
let queriedTabs = [];
const browser = {
  permissions: {
    async contains() { return false; },
    onRemoved: event(),
  },
  storage: {
    sync: { async get() { return {}; }, async set() {} },
    session: {
      async get(key) { return { [key]: ephemeralState[key] }; },
      async set(patch) { Object.assign(ephemeralState, patch); },
      async remove(key) { delete ephemeralState[key]; },
    },
    local: {
      async get(defaults) { return { ...(defaults || {}), ...state }; },
      async set(patch) { Object.assign(state, patch); },
    },
    onChanged: onStorageChanged,
  },
  history: {
    onVisited,
    async deleteUrl(details) { deleteCalls.push(details.url); },
    async addUrl(details) { addCalls.push(details.url); },
    async search() { return []; },
  },
  webNavigation: { onCommitted, onHistoryStateUpdated },
  tabs: {
    onUpdated: onTabUpdated,
    onCreated: onTabCreated,
    onActivated: onTabActivated,
    onRemoved: onTabRemoved,
    async query() { return queriedTabs; },
    async get(tabId) { return queriedTabs.find((tab) => tab.id === tabId) || { id: tabId, url: "" }; },
    async remove(ids) { removedTabCalls.push(...(Array.isArray(ids) ? ids : [ids])); },
  },
  alarms: {
    async clear() {},
    create(_name, details) { alarmPeriods.push(details.periodInMinutes); },
    onAlarm,
  },
  runtime: {
    onInstalled: event(),
    onStartup: event(),
    onMessage,
    getManifest() { return { version: "test" }; },
  },
  action: {
    async setBadgeText() {},
    async setBadgeBackgroundColor() {},
    async setIcon() {},
    async setTitle() {},
  },
  pageAction: {
    async show() {}, async hide() {}, async setIcon() {}, async setTitle() {},
  },
};

const context = {
  browser,
  console,
  URL,
  decodeURIComponent,
  setTimeout(fn) { fn(); return 1; },
  clearTimeout() {},
  crypto: webcrypto,
  TextEncoder,
  TextDecoder,
  btoa,
  atob,
};
vm.createContext(context);
const background = path.join(__dirname, "..", "firefox-extension", "background.js");
vm.runInContext(fs.readFileSync(background, "utf8"), context, { filename: background });

function settings(extra = {}) {
  return context.normalizeSettings({ enabled: true, ...extra });
}

const sharedFixtures = JSON.parse(fs.readFileSync(
  path.join(__dirname, "..", "..", "shared", "rule-fixtures.json"),
  "utf8",
));
for (const fixture of sharedFixtures.cases) {
  const decision = context.decide(
    fixture.url,
    fixture.title,
    settings({ visualRules: fixture.rules }),
  );
  assert.equal(decision.action, fixture.expectedAction, fixture.name);
  if (fixture.expectedCollapsedUrl) {
    assert.equal(decision.collapsedUrl, fixture.expectedCollapsedUrl, fixture.name);
  }
}

assert.equal(context.shouldSuppress("https://example.com/a", "", settings({ domains: ["example.com"] })), true);
assert.equal(context.shouldSuppress("https://deep.sub.example.com/a", "", settings({ domains: ["*.example.com"] })), true);
assert.equal(context.shouldSuppress("https://deep.sub.example.com/a", "", settings({ domains: ["HTTPS://*.Example.COM/path"] })), true);
assert.equal(context.shouldSuppress("https://notexample.com/a", "", settings({ domains: ["example.com"] })), false);
assert.equal(context.shouldSuppress("https://search.test/?q=very%20secret", "", settings({ keywords: ["very secret"] })), true);
assert.equal(context.shouldSuppress("https://search.test/", "A secret document", settings({ keywords: ["secret"] })), true);
assert.equal(context.shouldSuppress("https://search.test/?q=Secret", "", settings({ keywords: ["secret"], caseSensitive: true })), false);
assert.equal(context.shouldSuppress("https://search.test/?q=secretary", "", settings({ keywords: ["secret"], wholeWord: true })), false);
assert.equal(context.shouldSuppress("https://search.test/?q=secret", "", settings({ keywords: ["secret"], wholeWord: true })), true);
assert.equal(context.shouldSuppress("https://test/", "A very secret page", settings({ keywords: ["very secret"], wholeWord: true })), true);
assert.equal(context.shouldSuppress("https://test/", "A very secretary page", settings({ keywords: ["very secret"], wholeWord: true })), false);
assert.equal(context.shouldSuppress("https://test/abc123", "", settings({ regex: ["abc\\d+"] })), true);
assert.equal(context.shouldSuppress("https://test/fooo", "", settings({ regex: ["foo{1,3}"] })), true);
assert.equal(context.shouldSuppress("https://test/alpha;beta", "", settings({ regex: ["alpha;beta"] })), true);
assert.equal(context.shouldSuppress("https://search.test/?q=very%2520secret", "", settings({ keywords: ["very secret"] })), true);
assert.equal(context.shouldSuppress("https://test/clean", "", settings({ regex: ["("] })), false);
assert.equal(context.shouldSuppress("https://example.com/", "", settings({ enabled: false, domains: ["example.com"] })), false);

const keepHomepage = {
  id: "keep-home", name: "Keep homepage", profile: "Default", matcher: "DOMAIN_EXCEPT_ROOT",
  value: "sitoacaso.it", action: "BLOCK", enabled: true,
};
assert.equal(context.shouldSuppress("https://www.sitoacaso.it/", "", settings({ visualRules: [keepHomepage] })), false);
assert.equal(context.shouldSuppress("https://www.sitoacaso.it/threads", "", settings({ visualRules: [keepHomepage] })), true);
assert.equal(context.shouldSuppress("https://www.sitoacaso.it/?q=private", "", settings({ visualRules: [keepHomepage] })), true);

const collapseHomepage = { ...keepHomepage, id: "collapse", action: "COLLAPSE_TO_ROOT" };
const collapsedDecision = context.decide(
  "https://www.sitoacaso.it/search/private",
  "",
  settings({ visualRules: [collapseHomepage] }),
);
assert.equal(collapsedDecision.action, "COLLAPSE_TO_ROOT");
assert.equal(collapsedDecision.collapsedUrl, "https://www.sitoacaso.it/");

const allowExact = {
  id: "allow", name: "Allow exact", profile: "Default", matcher: "EXACT_URL",
  value: "https://example.com/", action: "ALLOW", enabled: true,
};
assert.equal(context.shouldSuppress(
  "https://example.com/", "", settings({ domains: ["example.com"], visualRules: [allowExact] }),
), false);

const forgetRestart = {
  id: "restart", name: "Forget on restart", profile: "Default", matcher: "DOMAIN",
  value: "temporary.example", action: "FORGET_ON_RESTART", enabled: true,
};
const forgetAfter = {
  id: "after", name: "Forget after", profile: "Default", matcher: "DOMAIN",
  value: "aging.example", action: "FORGET_AFTER", retentionMillis: 3600000, enabled: true,
};
assert.equal(context.shouldSuppress(
  "https://temporary.example/page", "", settings({ visualRules: [forgetRestart] }),
), false, "restart rules must leave the current session usable and stored");
const restartDecision = context.decide(
  "https://temporary.example/page", "", settings({ visualRules: [forgetRestart] }),
);
assert.equal(context.shouldRemoveStoredVisit(restartDecision, Date.now(), { includeSessionRules: false }), false);
assert.equal(context.shouldRemoveStoredVisit(restartDecision, Date.now(), { includeSessionRules: true }), true);
const afterDecision = context.decide(
  "https://aging.example/page", "", settings({ visualRules: [forgetAfter] }),
);
assert.equal(context.shouldRemoveStoredVisit(afterDecision, Date.now() - 7200000), true);
assert.equal(context.shouldRemoveStoredVisit(afterDecision, Date.now() - 1000), false);

async function runIntegration() {
  // Let immediate background initialization finish before simulating user input.
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));

  await context.persistSettings(settings({ domains: ["example.com"] }));
  onVisited.emit({ url: "https://example.com/private", title: "Private" });

  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));

  assert.ok(deleteCalls.includes("https://example.com/private"), "matching visited URL should be deleted");
  assert.equal(state.totalRemoved, 1, "one matching history visit should increment the counter once");
  assert.equal(state.domains[0], "example.com");
  assert.equal(typeof state.lastMatchAt, "number");
  assert.ok(state.lastMatchAt > 0);

  await context.persistSettings(settings({ keywords: ["blocked title"] }));
  onTabUpdated.emit(
    1,
    { title: "A blocked title" },
    { url: "https://clean.example/article", title: "A blocked title" },
  );
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert.ok(
    deleteCalls.includes("https://clean.example/article"),
    "title-only match should be deleted from history",
  );

  await context.persistSettings(settings({ keywords: ["secret"] }));
  browser.history.search = async () => [
    { url: "https://search.test/?q=secret", title: "Search", lastVisitTime: 20 },
    { url: "https://clean.test/", title: "Clean", lastVisitTime: 10 },
  ];
  const scrub = await context.scrubAllHistory();
  // Values come from the VM context, so compare fields instead of prototypes.
  assert.equal(scrub.removed, 1);
  assert.equal(scrub.scanned, 2);
  assert.ok(deleteCalls.includes("https://search.test/?q=secret"));

  await context.persistSettings(settings({ visualRules: [collapseHomepage] }));
  onVisited.emit({ url: "https://www.sitoacaso.it/threads/1", title: "Thread" });
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert.ok(addCalls.includes("https://www.sitoacaso.it/"), "collapse rule should add only the site root");

  const restoredOnly = {
    id: "restored", name: "Restored only", profile: "Default", matcher: "DOMAIN",
    value: "restore.example", action: "BLOCK", enabled: true, closeTab: true,
  };
  await context.persistSettings(settings({ visualRules: [restoredOnly] }));
  const removedBeforeLiveVisit = removedTabCalls.length;
  await context.applyDecision("https://restore.example/live", "", { tabId: 44 });
  assert.equal(
    removedTabCalls.length,
    removedBeforeLiveVisit,
    "a close-tab rule must never close a matching live tab",
  );
  queriedTabs = [
    { id: 44, url: "https://restore.example/restored", title: "Restored" },
    { id: 45, url: "https://safe.example/", title: "Safe" },
  ];
  let startupQuery = true;
  browser.tabs.query = async () => {
    if (startupQuery) {
      startupQuery = false;
      return queriedTabs;
    }
    return [...queriedTabs, { id: 46, url: "https://restore.example/live", title: "Live" }];
  };
  assert.equal(await context.closeRestoredMatchingTabs(await context.getSettings()), 1);
  assert.ok(removedTabCalls.includes(44), "matching restored tab should close on the next startup pass");
  assert.equal(removedTabCalls.includes(45), false);
  assert.equal(removedTabCalls.includes(46), false, "a tab opened after startup capture must stay open");
  browser.tabs.query = async () => queriedTabs;

  await context.toggleTabShield(50, "https://parent.example/", { inherit: true });
  onTabCreated.emit({ id: 51, openerTabId: 50, url: "https://child.example/" });
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(
    context.decide("https://child.example/", "", await context.getSettings(), { tabId: 51 }).action,
    "BLOCK",
    "child tabs should inherit an explicitly inheriting tab shield",
  );

  const messageListener = onMessage.listeners[0];
  await messageListener({ type: "protect-next-navigation", tabId: 60, url: "https://start.example/" });
  await context.advanceOneShot(60, "https://next.example/private");
  assert.equal(
    context.decide("https://next.example/private", "", await context.getSettings(), { tabId: 60 }).action,
    "BLOCK",
    "the armed next navigation should be shielded",
  );
  assert.ok(
    ephemeralState.privacyStudioEphemeralV3.oneShots.some(([tabId]) => tabId === 60),
    "one-shot state should survive Manifest V3 event-page suspension in memory-only storage",
  );
  vm.runInContext(
    "sessionPrivateTabs.clear(); inheritingPrivateTabs.clear(); oneShotTabs.clear(); "
      + "sessionBlockAll = false; ephemeralStateLoaded = false; ephemeralStateLoad = null;",
    context,
  );
  await context.ensureEphemeralState();
  assert.equal(
    context.decide("https://next.example/private", "", await context.getSettings(), { tabId: 60 }).action,
    "BLOCK",
    "memory-only session state should rehydrate after an event-page restart",
  );

  await context.persistSettings(settings({ visualRules: [], domains: [], keywords: [], regex: [] }));
  browser.history.search = async () => [
    { url: "https://safe.example/old", title: "Safe", lastVisitTime: 1 },
  ];
  await messageListener({ type: "temporary-mode", mode: "session" });
  const deleteCountBeforeTemporaryScrub = deleteCalls.length;
  const temporaryScrub = await context.scrubAllHistory({ includeSessionRules: true });
  assert.equal(temporaryScrub.removed, 0, "a live session shield must not match older clean history");
  assert.equal(deleteCalls.length, deleteCountBeforeTemporaryScrub);
  await messageListener({ type: "temporary-mode", mode: "off" });

  await context.persistSettings(settings({
    scrubOnStartup: false,
    domains: ["immediate.example"],
    visualRules: [forgetRestart],
  }));
  browser.history.search = async () => [
    { url: "https://temporary.example/old", title: "Restart", lastVisitTime: 2 },
    { url: "https://immediate.example/old", title: "Immediate", lastVisitTime: 1 },
  ];
  const deleteCountBeforeRestartOnly = deleteCalls.length;
  const restartOnly = await context.scrubAllHistory({ includeSessionRules: true, restartOnly: true });
  assert.equal(restartOnly.removed, 1, "restart rules must run even when ordinary startup scrubbing is off");
  assert.deepEqual(
    deleteCalls.slice(deleteCountBeforeRestartOnly),
    ["https://temporary.example/old"],
    "the mandatory restart pass must not broaden a disabled startup scrub",
  );

  const encrypted = await context.encryptRuleBundle(
    settings({ visualRules: [collapseHomepage], activeProfiles: ["Default"] }),
    "correct horse",
  );
  assert.ok(encrypted.startsWith("FENIX-PRIVACY-2\n"));
  assert.equal(encrypted.includes("sitoacaso.it"), false, "encrypted export must not expose rule text");
  const imported = await context.decryptRuleBundle(encrypted, "correct horse");
  assert.equal(imported.visualRules[0].value, "sitoacaso.it");

  const alarmsBeforeSyncChange = alarmPeriods.length;
  state.scrubEveryMinutes = 37;
  onStorageChanged.emit({ scrubEveryMinutes: { newValue: 37 } }, "sync");
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert.ok(alarmPeriods.length > alarmsBeforeSyncChange, "synced interval should reschedule alarm");
  assert.equal(alarmPeriods.at(-1), 37);
}

runIntegration()
  .then(() => console.log("Fenix Privacy Desktop matcher + runtime tests passed"))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
