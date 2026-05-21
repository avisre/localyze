/**
 * check-bundle-status.js — check if v1.1.4 (versionCode 11) finished
 * uploading + processing in Play Console's bundle library.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `check-bundle-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = null;
  for (const p of ctx.pages()) {
    const url = p.url();
    if (url.startsWith('https://play.google.com/console/') &&
        !url.includes('accounts.google.com') &&
        !url.includes('/about')) {
      try {
        const ok = await p.evaluate(() => /Publishing overview|Production|Release/i.test(document.body.innerText || ''));
        if (ok) { page = p; break; }
      } catch {}
    }
  }
  if (!page) { console.error('❌ no signed-in tab'); process.exit(1); }
  await page.bringToFront();

  // Look in the production-release draft form
  const url = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;
  console.log(`→ ${url}`);
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, 'draft.png'), fullPage: true });

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      has114: /\(11\)|1\.1\.4|version code 11/i.test(t),
      has113: /\(10\)|1\.1\.3/i.test(t),
      hasError: /unexpected error|7FD4FA4E/i.test(t),
      hasProcessing: /optimized for distribution|optimizing|processing/i.test(t),
      bundleSection: (() => {
        const m = t.match(/App bundles[\s\S]*?(?=Previous release|Release details|$)/);
        return m ? m[0].slice(0, 1500) : null;
      })(),
    };
  });
  console.log(`\nv1.1.4 present? ${state.has114}`);
  console.log(`v1.1.3 present? ${state.has113}`);
  console.log(`Error banner? ${state.hasError}`);
  console.log(`Still processing? ${state.hasProcessing}`);
  console.log(`\nApp bundles section:\n${state.bundleSection || '[not found]'}`);
  fs.writeFileSync(path.join(OUT, 'state.json'), JSON.stringify(state, null, 2));
  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
