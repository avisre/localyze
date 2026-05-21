/**
 * fs-decl-fill.js — fill the Foreground Service declaration form and Save.
 *
 * Our app uses FOREGROUND_SERVICE_DATA_SYNC for:
 *   - ModelDownloadService: downloading the Gemma 3n E2B .litertlm model
 *     (~3.6 GB) from HuggingFace. Maps to Network processing → "Backing
 *     up, restoring" (restoring on-device AI model from a remote source).
 *   - ModelLoadingService: loading the model file into the inference
 *     engine's memory. Maps to Local processing → "Importing, exporting".
 *
 * Both pre-defined categories — no video proof required.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `fs-fill-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  // Find the tab already on the declaration page
  let page = ctx.pages().find((p) => /foreground|declar|permissions/i.test(p.url())) ||
             ctx.pages().find((p) => p.url().includes('play.google.com/console'));
  if (!page) { console.error('❌ no Play Console tab'); process.exit(1); }
  await page.bringToFront();
  console.log(`current url: ${page.url()}`);
  await sleep(2000);
  await page.screenshot({ path: path.join(OUT, '01-initial.png'), fullPage: true });

  // Check checkboxes by nearest label text
  const wanted = ['Backing up, restoring', 'Importing, exporting'];
  console.log(`→ ticking: ${wanted.join(', ')}`);
  const ticked = await page.evaluate((wanted) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Build map: for each checkbox, find nearest text label
    function nearestLabel(input) {
      // Walk up + siblings to find a label-like sibling
      let node = input;
      for (let depth = 0; depth < 8 && node; depth++) {
        // sibling text
        if (node.parentElement) {
          for (const sib of node.parentElement.children) {
            const t = (sib.innerText || sib.textContent || '').trim();
            if (t && t.length < 80 && sib !== node) return t;
          }
        }
        node = node.parentElement;
      }
      return null;
    }
    const found = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT') continue;
      if (el.type !== 'checkbox') continue;
      const lbl = nearestLabel(el);
      found.push({ label: lbl, checked: el.checked });
      if (lbl && wanted.includes(lbl) && !el.checked) {
        el.scrollIntoView({ block: 'center' });
        el.click();
      }
    }
    return found;
  }, wanted);
  console.log(`  checkboxes found: ${JSON.stringify(ticked)}`);
  await sleep(2500);
  await page.screenshot({ path: path.join(OUT, '02-ticked.png'), fullPage: true });

  // Click Save
  console.log('→ click Save');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName === 'BUTTON' && (el.innerText || '').trim() === 'Save') {
        el.scrollIntoView({ block: 'center' });
        el.click();
        return;
      }
    }
  });
  await sleep(5000);
  await page.screenshot({ path: path.join(OUT, '03-after-save.png'), fullPage: true });
  console.log(`url: ${page.url()}`);

  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
