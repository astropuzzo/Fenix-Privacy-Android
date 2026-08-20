"use strict";
const $ = (id) => document.getElementById(id);
const LIST_KEYS = ["domains", "keywords", "regex"];
const BOOL_KEYS = ["enabled", "scrubOnStartup", "caseSensitive", "wholeWord", "syncRules"];
const SYNC_DATA_PERMISSIONS = ["browsingActivity", "searchTerms", "technicalAndInteraction"];

function lines(id) {
  return $(id).value.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
}

function showError(error) {
  $("status").textContent = `Extension error: ${error?.message || error}`;
}

async function readForm() {
  const previous = await browser.runtime.sendMessage({ type: "get-settings" });
  const next = { ...previous };
  for (const key of BOOL_KEYS) next[key] = $(key).checked;
  for (const key of LIST_KEYS) next[key] = lines(key);
  next.scrubEveryMinutes = Math.max(15, Number($("scrubEveryMinutes").value) || 15);
  return next;
}

async function load() {
  const settings = await browser.runtime.sendMessage({ type: "get-settings" });
  for (const key of BOOL_KEYS) $(key).checked = Boolean(settings[key]);
  for (const key of LIST_KEYS) $(key).value = (settings[key] || []).join("\n");
  $("scrubEveryMinutes").value = Math.max(15, Number(settings.scrubEveryMinutes || 15));
}

async function save(showMessage = true) {
  const settings = await readForm();
  if (settings.syncRules) {
    let granted = false;
    try {
      granted = await browser.permissions.contains({ data_collection: SYNC_DATA_PERMISSIONS });
      if (!granted) {
        granted = await browser.permissions.request({ data_collection: SYNC_DATA_PERMISSIONS });
      }
    } catch (_) {
      granted = false;
    }
    if (!granted) {
      settings.syncRules = false;
      $("syncRules").checked = false;
      $("status").textContent = "Firefox Sync for rules was not enabled; rules remain local only.";
    }
  }
  await browser.runtime.sendMessage({ type: "set-settings", settings });
  if (showMessage && !$("status").textContent) $("status").textContent = "Saved. Rules are active immediately.";
  return settings;
}

$("save").addEventListener("click", async () => {
  try {
    $("status").textContent = "";
    await save();
  } catch (error) {
    showError(error);
  }
});

$("clean").addEventListener("click", async () => {
  try {
    $("status").textContent = "";
    await save(false);
    $("status").textContent = "Scanning Firefox history…";
    const result = await browser.runtime.sendMessage({ type: "scrub-now" });
    $("status").textContent = `Scanned ${result.scanned.toLocaleString()} entries; removed ${result.removed.toLocaleString()}.`;
  } catch (error) {
    showError(error);
  }
});

$("test").addEventListener("click", async () => {
  try {
    $("testResult").textContent = "Testing…";
    const settings = await readForm();
    const result = await browser.runtime.sendMessage({
      type: "test-rule",
      settings,
      url: $("testUrl").value,
      title: $("testTitle").value,
    });
    $("testResult").textContent = result.match ? "MATCH — history will be removed" : "No match";
  } catch (error) {
    $("testResult").textContent = `Error: ${error?.message || error}`;
  }
});

$("export").addEventListener("click", async () => {
  try {
    const settings = await readForm();
    const blob = new Blob([JSON.stringify(settings, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "fenix-privacy-rules.json";
    a.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    showError(error);
  }
});

$("import").addEventListener("change", async (event) => {
  const file = event.target.files?.[0];
  if (!file) return;
  try {
    const imported = JSON.parse(await file.text());
    const current = await browser.runtime.sendMessage({ type: "get-settings" });
    await browser.runtime.sendMessage({ type: "set-settings", settings: { ...current, ...imported } });
    await load();
    $("status").textContent = "Imported and saved.";
  } catch (error) {
    $("status").textContent = `Import failed: ${error.message}`;
  } finally {
    event.target.value = "";
  }
});

load().catch(showError);
