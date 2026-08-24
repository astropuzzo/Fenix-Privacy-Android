const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

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
const onVisited = event();
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
    onChanged: event(),
  },
  history: {
    onVisited,
    async deleteUrl(details) { deleteCalls.push(details.url); },
    async search() { return []; },
  },
  webNavigation: { onCommitted: event(), onHistoryStateUpdated: event() },
  tabs: { onUpdated: event() },
  alarms: { async clear() {}, create() {}, onAlarm: event() },
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
assert.equal(context.shouldSuppress("https://test/abc123", "", settings({ regex: ["abc\\d+"] })), true);
assert.equal(context.shouldSuppress("https://test/clean", "", settings({ regex: ["("] })), false);
assert.equal(context.shouldSuppress("https://example.com/", "", settings({ enabled: false, domains: ["example.com"] })), false);

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
}

runIntegration()
  .then(() => console.log("Fenix Privacy Desktop matcher + runtime tests passed"))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
