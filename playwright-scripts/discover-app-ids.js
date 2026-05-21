/**
 * discover-app-ids.js — opens Play Console dashboard, lists every app + its
 * APP_ID by scraping the dashboard table. Output goes to stdout + a JSON
 * file we can then read.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROFILE = path.join(os.homedir(), '.cache', 'play-console-profile');
const DEVELOPER_ID = '6492542126543167241';
const PINNED = path.join(os.homedir(), '.cache', 'ms-playwright', 'chromium-1217', 'chrome-linux64', 'chrome');

(async () => {
  fs.mkdirSync(PROFILE, { recursive: true });
  const opts = { headless: false, viewport: { width: 1400, height: 900 } };
  if (fs.existsSync(PINNED)) opts.executablePath = PINNED;
  const ctx = await chromium.launchPersistentContext(PROFILE, opts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  await page.goto(`https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app-list`, { waitUntil: 'domcontentloaded', timeout: 60000 });

  // Wait for the apps table to render.
  for (let i = 0; i < 30; i++) {
    const found = await page.evaluate(() => /com\.\w+/.test(document.body.innerText || ''));
    if (found) break;
    await new Promise((r) => setTimeout(r, 2000));
  }

  // Extract every (displayName, packageName, appId) tuple.
  const apps = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('a[href*="/app/"]').forEach((a) => {
      const m = a.href.match(/\/app\/(\d+)\//);
      if (!m) return;
      const id = m[1];
      // Find adjacent display name + package name
      let node = a;
      for (let i = 0; i < 4 && node; i++) node = node.parentElement;
      const text = (node ? node.innerText : a.innerText) || '';
      const pkg = (text.match(/com\.[\w.]+/) || [''])[0];
      const name = text.split('\n').find((l) => l && !l.startsWith('com.') && !/^\d+$/.test(l)) || '';
      out.push({ id, pkg, name: name.trim().slice(0, 60) });
    });
    // Dedup by id
    const seen = new Set();
    return out.filter((a) => { if (seen.has(a.id)) return false; seen.add(a.id); return true; });
  });

  console.log(JSON.stringify(apps, null, 2));
  fs.writeFileSync(path.join(__dirname, 'discovered-apps.json'), JSON.stringify(apps, null, 2));
  await ctx.close();
})().catch((e) => { console.error('❌', e.message); process.exit(1); });
