/* Optional, read-only probe of the real WeChat DevTools automation endpoint.
 * Install miniprogram-automator outside shared dependencies, then enable it with:
 * cli.bat auto --project <repo>/miniprogram --auto-port 9420
 * This never changes the API host, domain checks, or account data.
 */
const fs = require("node:fs");
const path = require("node:path");
const root = path.resolve(__dirname, "..");
const output = path.join(
  root,
  ".local/auth-dev/wechat-automation/runtime-acceptance.json",
);
const report = {
  checked_at: new Date().toISOString(),
  scope: "Real DevTools automation; no backend requests or device acceptance",
  checks: [],
  page_render_verified: false,
  backend_flow_verified: false,
  device_run: false,
};
fs.mkdirSync(path.dirname(output), { recursive: true });
function save() {
  fs.writeFileSync(output, JSON.stringify(report, null, 2));
}
async function check(name, operation) {
  let timer;
  try {
    const value = await Promise.race([
      operation(),
      new Promise((_, reject) => {
        timer = setTimeout(
          () => reject(new Error("Timed out after 15 seconds")),
          15000,
        );
      }),
    ]);
    report.checks.push({ name, status: "PASS", value });
    save();
    return value;
  } catch (error) {
    report.checks.push({ name, status: "FAIL", message: error.message });
    save();
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
(async () => {
  let mp;
  const watchdog = setTimeout(() => {
    report.status = "INCOMPLETE";
    save();
    process.exit(2);
  }, 65000);
  try {
    const automator = require(
      process.env.WECHAT_AUTOMATOR_MODULE ||
        path.join(
          root,
          ".local/auth-dev/wechat-automation/node_modules/miniprogram-automator",
        ),
    );
    await check("Authenticated IDE connection", () =>
      automator
        .connect({
          wsEndpoint:
            process.env.WECHAT_AUTOMATOR_ENDPOINT || "ws://127.0.0.1:9420",
        })
        .then((value) => {
          mp = value;
          return true;
        }),
    );
    const page = await mp.currentPage();
    await check("Current route", async () => ({ path: page?.path }));
    await check("Runtime metadata", async () => {
      const value = await mp.systemInfo();
      return {
        platform: value.platform,
        SDKVersion: value.SDKVersion,
        windowWidth: value.windowWidth,
        windowHeight: value.windowHeight,
      };
    });
    await check("App service and selected host", () =>
      mp.evaluate(() => ({
        apiBaseUrl: getApp().globalData.apiBaseUrl,
      })),
    );
    if (!page) throw new Error("No active page");
    // Do not persist field values or credentials from an already signed-in app.
    await check("Page data channel", async () => {
      const value = await page.data();
      return { available: !!value && typeof value === "object" };
    });
    report.status = "PASS";
  } catch (error) {
    report.status = "INCOMPLETE";
    console.error(error.message);
    process.exitCode = 2;
  } finally {
    if (mp) await mp.disconnect();
    clearTimeout(watchdog);
    save();
  }
})();
