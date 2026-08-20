"use strict";
const $ = (id) => document.getElementById(id);

async function refresh() {
  const settings = await browser.runtime.sendMessage({ type: "get-settings" });
  $("enabled").checked = Boolean(settings.enabled);
  $("removed").textContent = Number(settings.totalRemoved || 0).toLocaleString();
}

$("enabled").addEventListener("change", async (event) => {
  const settings = await browser.runtime.sendMessage({ type: "get-settings" });
  await browser.runtime.sendMessage({
    type: "set-settings",
    settings: { ...settings, enabled: event.target.checked },
  });
  await refresh();
});

$("clean").addEventListener("click", async () => {
  $("status").textContent = "Scanning…";
  const result = await browser.runtime.sendMessage({ type: "scrub-now" });
  $("status").textContent = `Removed ${result.removed} matching entr${result.removed === 1 ? "y" : "ies"}.`;
  await refresh();
});

$("options").addEventListener("click", () => browser.runtime.openOptionsPage());
refresh();
