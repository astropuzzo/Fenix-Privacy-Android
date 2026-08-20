const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

const event = () => ({ addListener() {} });
const browser = {
  storage: {
    sync: { async get() { return {}; }, async set() {} },
    local: { async get() { return {}; }, async set() {} },
    onChanged: event(),
  },
  history: { onVisited: event(), async deleteUrl() {}, async search() { return []; } },
  webNavigation: { onCommitted: event() },
  tabs: { onUpdated: event() },
  alarms: { async clear() {}, create() {}, onAlarm: event() },
  runtime: { onInstalled: event(), onStartup: event(), onMessage: event() },
};

const context = { browser, console, URL, decodeURIComponent, setTimeout, clearTimeout };
vm.createContext(context);
const background = path.join(__dirname, "..", "firefox-extension", "background.js");
vm.runInContext(fs.readFileSync(background, "utf8"), context, { filename: background });

function settings(extra = {}) {
  return context.normalizeSettings({ enabled: true, ...extra });
}

assert.equal(context.shouldSuppress("https://example.com/a", "", settings({ domains: ["example.com"] })), true);
assert.equal(context.shouldSuppress("https://deep.sub.example.com/a", "", settings({ domains: ["*.example.com"] })), true);
assert.equal(context.shouldSuppress("https://notexample.com/a", "", settings({ domains: ["example.com"] })), false);
assert.equal(context.shouldSuppress("https://search.test/?q=very%20secret", "", settings({ keywords: ["very secret"] })), true);
assert.equal(context.shouldSuppress("https://search.test/", "A secret document", settings({ keywords: ["secret"] })), true);
assert.equal(context.shouldSuppress("https://search.test/?q=Secret", "", settings({ keywords: ["secret"], caseSensitive: true })), false);
assert.equal(context.shouldSuppress("https://search.test/?q=secretary", "", settings({ keywords: ["secret"], wholeWord: true })), false);
assert.equal(context.shouldSuppress("https://search.test/?q=secret", "", settings({ keywords: ["secret"], wholeWord: true })), true);
assert.equal(context.shouldSuppress("https://test/abc123", "", settings({ regex: ["abc\\d+"] })), true);
assert.equal(context.shouldSuppress("https://test/clean", "", settings({ regex: ["("] })), false);
assert.equal(context.shouldSuppress("https://example.com/", "", settings({ enabled: false, domains: ["example.com"] })), false);

console.log("Fenix Privacy Desktop matcher tests passed");
