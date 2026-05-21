/**
 * upload-116-direct.js — just upload v1.1.6 AAB directly to current draft.
 * Draft has v1.1.3 attached; v1.1.6 will get added alongside, then we
 * remove v1.1.3.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const AAB_PATH = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PREPARE_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/4697325931803269676/releases/2/prepare`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `upload116-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log(`AAB: ${(fs.statSync(AAB_PATH).size/1e6).toFixed(1)} MB`);
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });

  console.log('→ goto prepare');
  await page.goto(PREPARE_URL, { waitUntil: 'networkidle', timeout: 60000 });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '01-prepare.png'), fullPage: true });

  // List ALL file inputs to debug
  const allInputs = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'file') continue;
      const r = el.getBoundingClientRect();
      out.push({ accept: el.accept, hidden: el.hidden, name: el.name, size: `${r.width}x${r.height}` });
    }
    return out;
  });
  console.log(`all file inputs: ${JSON.stringify(allInputs)}`);

  // Find and upload to .aab-accepting input
  let uploaded = false;
  for (let attempt = 0; attempt < 6 && !uploaded; attempt++) {
    const inputs = await page.$$('input[type="file"]');
    console.log(`  attempt ${attempt+1}: found ${inputs.length} file inputs`);
    for (const fi of inputs) {
      try {
        const accept = await fi.getAttribute('accept');
        console.log(`    accept=${accept}`);
        if (accept && /\.aab/i.test(accept)) {
          console.log('  → setInputFiles');
          await fi.setInputFiles(AAB_PATH, { timeout: 180000 });
          console.log('✓ setInputFiles returned');
          uploaded = true;
          break;
        }
      } catch (e) { console.log(`    err: ${e.message.slice(0, 90)}`); }
    }
    if (!uploaded) await sleep(3000);
  }

  if (!uploaded) {
    console.error('❌ no .aab input on the page. Falling back: try the FIRST file input regardless of accept.');
    const inputs = await page.$$('input[type="file"]');
    if (inputs.length) {
      try {
        await inputs[0].setInputFiles(AAB_PATH, { timeout: 180000 });
        console.log('✓ setInputFiles (first input) returned');
        uploaded = true;
      } catch (e) { console.log(`  err: ${e.message.slice(0, 90)}`); }
    }
  }

  if (!uploaded) { console.error('❌ upload completely failed'); process.exit(2); }
  await sleep(5000);
  await page.screenshot({ path: path.join(OUT, '02-upload-started.png'), fullPage: true });

  // Wait for v1.1.6 row
  console.log('→ wait for v1.1.6 row');
  let attached = false;
  for (let i = 0; i < 144; i++) {
    await sleep(5000);
    const s = await page.evaluate(() => {
      const t = document.body.innerText || '';
      return {
        hasRow: /13\s*\(1\.1\.6\)/.test(t),
        spinner: /Uploading|Optimizing|Processing|optimized for distribution/i.test(t),
        error: /unexpected error|already been used/i.test(t),
      };
    });
    if (i % 6 === 0) console.log(`  ${(i+1)*5}s: hasRow=${s.hasRow} spinner=${s.spinner} err=${s.error}`);
    if (s.hasRow && !s.spinner) { attached = true; console.log(`✓ attached after ~${(i+1)*5}s`); break; }
  }
  await page.screenshot({ path: path.join(OUT, '03-attached.png'), fullPage: true });
  if (!attached) { console.error('❌ v1.1.6 not attached'); process.exit(3); }

  console.log(`\n✅ v1.1.6 uploaded and attached to draft. Output: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
