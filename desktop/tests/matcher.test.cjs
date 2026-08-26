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
  "ruleMatcher", "ruleName", "ruleProfile", "ruleQueryParameter", "ruleValue", "save",
  "scrubEveryMinutes", "scrubOnStartup", "selfTest", "selfTestResult", "status", "syncRules",
  "temporaryStatus", "test", "testResult", "testTitle", "testUrl", "updateCenter",
  "visualRuleList", "wholeWord",
];
for (const id of requiredOptionIds) {
  assert.match(optionsHtml, new RegExp(`\\bid=["']${id}["']`), `options.html must contain #${id}`);
}
const optionIds = [...optionsHtml.matchAll(/\bid=["']([^"']+)["']/g)].map((match) => match[1]);
assert.equal(new Set(optionIds).size, optionIds.length, "options.html IDs must be unique");
assert.match(optionsHtml, /<\/html>\s*$/, "options.html must be a complete document");

function event() {
  const listeners = [];
  return {
    listeners,
    addListener(fn) { listeners.push(fn); },
    emit(...args) { for (const fn of listeners) fn(...args); },
  };
}

const state = {};
const deleteCalls = [];
const addCalls = [];
const alarmPeriods = [];
const onVisited = event();
const onCommitted = event();
const onHistoryStateUpdated = event();
const onTabUpdated = event();
const onStorageChanged = event();
const onAlarm = event();
const browser = {
  permissions: {
    async contains() { return false; },
    onRemoved: event(),
  },
  storage: {
    sync: { async get() { return {}; }, async set() {} },
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
  tabs: { onUpdated: onTabUpdated },
  alarms: {
    async clear() {},
    create(_name, details) { alarmPeriods.push(details.periodInMinutes); },
    onAlarm,
  },
  runtime: {
    onInstalled: event(),
    onStartup: event(),
    onMessage: event(),
    getManifest() { return { version: "test" }; },
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
