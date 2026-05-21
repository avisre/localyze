/**
 * fs-try-other.js — uncheck the current pre-defined boxes and try the
 * "Other tasks → Other" path to see if it just needs a textarea (no video).
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `fs-other-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find((p) => /foreground/i.test(p.url())) ||
             ctx.pages().find((p) => p.url().includes('play.google.com/console'));
  await page.bringToFront();
  await sleep(2000);

  // Uncheck all currently-checked boxes
  console.log('→ uncheck all');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName === 'INPUT' && el.type === 'checkbox' && el.checked) el.click();
    }
  });
  await sleep(2000);
  await page.screenshot({ path: path.join(OUT, '01-unchecked.png'), fullPage: true });

  // Check ONLY the last "Other" (Other tasks → Other)
  console.log('→ check Other tasks → Other (last checkbox)');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cbs = [];
    for (const el of walk(document)) {
      if (el.tagName === 'INPUT' && el.type === 'checkbox') cbs.push(el);
    }
    if (cbs.length) {
      const last = cbs[cbs.length - 1];
      last.scrollIntoView({ block: 'center' });
      last.click();
    }
  });
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '02-other-only-checked.png'), fullPage: true });

  // Dump what appeared
  const after = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const text = (document.body.innerText || '').slice(0, 4000);
    const inputs = [];
    for (const el of walk(document)) {
      if (['INPUT','TEXTAREA'].includes(el.tagName)) {
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) {
          inputs.push({ tag: el.tagName, type: el.type, aria: el.getAttribute('aria-label'), required: el.required });
        }
      }
    }
    return { text, inputs };
  });
  console.log(`\nVisible inputs:\n${JSON.stringify(after.inputs, null, 2)}`);
  console.log(`\nBody:\n${after.text.slice(0, 3000)}`);
  fs.writeFileSync(path.join(OUT, 'state.json'), JSON.stringify(after, null, 2));

  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
