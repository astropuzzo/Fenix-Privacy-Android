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
    $("status").textContent = "Scanning…";
    const result = await browser.runtime.sendMessage({ type: "scrub-now" });
    $("status").textContent = `Scanned ${result.scanned}; removed ${result.removed} matching entr${result.removed === 1 ? "y" : "ies"}.`;
    await refresh();
  } catch (error) {
    showError(error);
  }
});

$("options").addEventListener("click", () => browser.runtime.openOptionsPage());
refresh();
