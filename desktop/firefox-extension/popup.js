"use strict";
const $ = (id) => document.getElementById(id);
let currentTab = null;

const ACTION_LABELS = {
  ALLOW: "Saved normally",
  BLOCK: "Never saved",
  COLLAPSE_TO_ROOT: "Homepage only",
  FORGET_AFTER: "Saved temporarily",
  FORGET_ON_RESTART: "Forgotten on restart",
};

const ACTION_ICONS = {
  ALLOW: "icons/shield-idle.svg",
  BLOCK: "icons/shield-block.svg",
  COLLAPSE_TO_ROOT: "icons/shield-collapse.svg",
  FORGET_AFTER: "icons/shield-temporary.svg",
  FORGET_ON_RESTART: "icons/shield-temporary.svg",
};

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
    [currentTab] = await browser.tabs.query({ active: true, currentWindow: true });
    const pagePromise = currentTab?.id && /^https?:/i.test(currentTab.url || "")
      ? browser.runtime.sendMessage({
        type: "get-page-status", tabId: currentTab.id, url: currentTab.url, title: currentTab.title || "",
      })
      : Promise.resolve(null);
    const [settings, health, page] = await Promise.all([
      browser.runtime.sendMessage({ type: "get-settings" }),
      browser.runtime.sendMessage({ type: "health" }),
      pagePromise,
    ]);
    $("enabled").checked = Boolean(settings.enabled);
    $("removed").textContent = Number(settings.totalRemoved || 0).toLocaleString();
    $("period").textContent = `${Number(settings.todayProtected || 0).toLocaleString()} / ${Number(settings.weekProtected || 0).toLocaleString()}`;
    $("collapsed").textContent = Number(settings.totalCollapsed || 0).toLocaleString();
    $("rules").textContent = Number(health.rules || 0).toLocaleString();
    $("health").textContent = health.ok ? (health.enabled ? "Active" : "Paused") : "Error";
    $("lastMatch").textContent = formatWhen(health.lastMatchAt);
    renderPage(page, settings.enabled);
    if (health.lastError) $("status").textContent = `Background error: ${health.lastError}`;
  } catch (error) {
    showError(error);
  }
}

function renderPage(page, enabled) {
  const webPage = currentTab && /^https?:/i.test(currentTab.url || "");
  for (const id of ["blockSite", "keepHomepage", "allowPage", "forgetRestart", "forget24", "toggleTab", "protectNext"]) {
    $(id).disabled = !webPage;
  }
  if (!webPage) {
    $("pageHost").textContent = "Firefox page";
    $("pageAction").textContent = "No history rule applies";
    $("pageReason").textContent = "Open a website to use contextual controls.";
    $("pageShield").src = "icons/shield-paused.svg";
    return;
  }
  try { $("pageHost").textContent = new URL(currentTab.url).hostname; }
  catch (_) { $("pageHost").textContent = "Current page"; }
  $("pageAction").textContent = !enabled ? "Protection paused" : (ACTION_LABELS[page?.action] || "Saved normally");
  $("pageReason").textContent = page?.reason || "No matching rule";
  $("pageShield").src = !enabled ? "icons/shield-paused.svg" : (ACTION_ICONS[page?.action] || ACTION_ICONS.ALLOW);
  $("toggleTab").textContent = page?.tabProtected ? "Stop shielding tab" : "Shield tab + children";
  $("protectNext").textContent = page?.nextNavigationArmed ? "Next page is shielded" : "Shield next page";
}

async function quickAction(action, message) {
  if (!currentTab?.id || !currentTab.url) return;
  await browser.runtime.sendMessage({ type: "quick-action", action, tabId: currentTab.id, url: currentTab.url });
  $("status").textContent = message;
  await refresh();
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
$("blockSite").addEventListener("click", () => quickAction("block-site", "This site will no longer be saved."));
$("keepHomepage").addEventListener("click", () => quickAction("collapse-site", "Only this site's homepage will be retained."));
$("allowPage").addEventListener("click", () => quickAction("allow-page", "This exact page is explicitly allowed."));
$("forgetRestart").addEventListener("click", () => quickAction("forget-restart", "This site's history will be removed next time Firefox starts."));
$("forget24").addEventListener("click", () => quickAction("forget-24h", "This site's history will be kept for 24 hours."));
$("toggleTab").addEventListener("click", async () => {
  try {
    const result = await browser.runtime.sendMessage({
      type: "temporary-tab", tabId: currentTab?.id, url: currentTab?.url || "", inherit: true,
    });
    $("status").textContent = result.active
      ? "This tab and tabs opened from it are shielded until they close."
      : "Tab shielding stopped.";
    await refresh();
  } catch (error) { showError(error); }
});
$("protectNext").addEventListener("click", async () => {
  try {
    await browser.runtime.sendMessage({
      type: "protect-next-navigation", tabId: currentTab?.id, url: currentTab?.url || "",
    });
    $("status").textContent = "The next page opened in this tab will not be saved.";
    await refresh();
  } catch (error) { showError(error); }
});
$("temp15").addEventListener("click", async () => {
  try {
    await browser.runtime.sendMessage({ type: "temporary-mode", mode: "15", minutes: 15 });
    $("status").textContent = "All history shielded for 15 minutes; cookies stay saved.";
  } catch (error) { showError(error); }
});
$("temp60").addEventListener("click", async () => {
  try {
    await browser.runtime.sendMessage({ type: "temporary-mode", mode: "60", minutes: 60 });
    $("status").textContent = "All history shielded for 1 hour; cookies stay saved.";
  } catch (error) { showError(error); }
});
$("tempSession").addEventListener("click", async () => {
  try {
    await browser.runtime.sendMessage({ type: "temporary-mode", mode: "session" });
    $("status").textContent = "All history shielded until Firefox closes; cookies stay saved.";
  } catch (error) { showError(error); }
});
refresh();
