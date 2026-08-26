"use strict";
const $ = (id) => document.getElementById(id);

function formatWhen(timestamp) {
  if (!timestamp) return "Never";
  const delta = Date.now() - Number(timestamp);
  if (delta < 5000) return "Just now";
  if (delta < 60000) return `${Math.max(1, Math.floor(delta / 1000))}s ago`;
  if (delta < 3600000) return `${Math.floor(delta / 60000)}m ago`;
  return new Date(timestamp).toLocaleString();
}

function showError(error) {
  $("health").textContent = "Error";
  $("status").textContent = `Extension error: ${error?.message || error}`;
}

async function refresh() {
  try {
    const [settings, health] = await Promise.all([
      browser.runtime.sendMessage({ type: "get-settings" }),
      browser.runtime.sendMessage({ type: "health" }),
    ]);
    $("enabled").checked = Boolean(settings.enabled);
    $("removed").textContent = Number(settings.totalRemoved || 0).toLocaleString();
    $("period").textContent = `${Number(settings.todayProtected || 0).toLocaleString()} / ${Number(settings.weekProtected || 0).toLocaleString()}`;
    $("collapsed").textContent = Number(settings.totalCollapsed || 0).toLocaleString();
    $("rules").textContent = Number(health.rules || 0).toLocaleString();
    $("health").textContent = health.ok ? (health.enabled ? "Active" : "Paused") : "Error";
    $("lastMatch").textContent = formatWhen(health.lastMatchAt);
    if (health.lastError) $("status").textContent = `Background error: ${health.lastError}`;
  } catch (error) {
    showError(error);
  }
}

$("enabled").addEventListener("change", async (event) => {
  try {
    const settings = await browser.runtime.sendMessage({ type: "get-settings" });
    await browser.runtime.sendMessage({
      type: "set-settings",
      settings: { ...settings, enabled: event.target.checked },
    });
    $("status").textContent = event.target.checked ? "Protection enabled." : "Protection paused.";
    await refresh();
  } catch (error) {
    showError(error);
  }
});

$("clean").addEventListener("click", async () => {
  try {
    $("status").textContent = "Previewing aggregate counts…";
    const preview = await browser.runtime.sendMessage({ type: "preview-scrub" });
    if (!preview.matching) {
      $("status").textContent = `Scanned ${preview.scanned}; no matching history.`;
      return;
    }
    const approved = confirm(
      `Remove ${preview.matching} matching history entr${preview.matching === 1 ? "y" : "ies"}? `
      + `${preview.collapsed} will collapse to a homepage. Cookies and sessions stay saved.`,
    );
    if (!approved) {
      $("status").textContent = "Cleanup cancelled; nothing changed.";
      return;
    }
    $("status").textContent = "Cleaning…";
    const result = await browser.runtime.sendMessage({ type: "scrub-now" });
    $("status").textContent = `Scanned ${result.scanned}; removed ${result.removed} matching entr${result.removed === 1 ? "y" : "ies"}.`;
    await refresh();
  } catch (error) {
    showError(error);
  }
});

$("options").addEventListener("click", () => browser.runtime.openOptionsPage());
$("temp15").addEventListener("click", async () => {
  try {
    await browser.runtime.sendMessage({ type: "temporary-mode", mode: "15", minutes: 15 });
    $("status").textContent = "All history shielded for 15 minutes; cookies stay saved.";
  } catch (error) { showError(error); }
});
$("tempTab").addEventListener("click", async () => {
  try {
    const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
    await browser.runtime.sendMessage({ type: "temporary-tab", tabId: tab?.id });
    $("status").textContent = "This tab is shielded until it closes.";
  } catch (error) { showError(error); }
});
refresh();
