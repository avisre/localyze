/**
 * show-errors.js — Connect via CDP, navigate to the v1.1.3 draft's
 * Preview & confirm step, expand the error/warning sections, and dump
 * their text so we can see why Save is grayed out.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PROD_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `show-errors-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  const page = await ctx.newPage();
  await page.setViewportSize({ width: 1400, height: 1200 });

  console.log(`→ goto ${PROD_URL}`);
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(6000);

  // Click "Releases" tab
  console.log('→ click Releases tab');
  await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    for (const el of walk(document)) {
      const tag = el.tagName;
      const role = el.getAttribute('role');
      const txt = (el.innerText || el.textContent || '').trim();
      const aria = el.getAttribute('aria-label');
      if ((tag === 'TAB-BUTTON' || role === 'tab') && (txt === 'Releases' || aria === 'Releases')) {
        el.scrollIntoView({ block: 'center' });
        el.click();
        return;
      }
    }
  });
  await sleep(5000);

  // Click "Edit release"
  console.log('→ click Edit release');
  await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    for (const el of walk(document)) {
      const tag = el.tagName;
      const txt = (el.innerText || el.textContent || '').trim();
      if (txt === 'Edit release' && ['BUTTON', 'A'].includes(tag)) {
        el.scrollIntoView({ block: 'center' });
        el.click();
        return;
      }
    }
  });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '01-draft-edit.png'), fullPage: true });

  // Click Next to get to preview & confirm
  console.log('→ click Next');
  await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    for (const el of walk(document)) {
      if (el.tagName !== 'BUTTON') continue;
      const txt = (el.innerText || el.textContent || '').trim();
      if (txt === 'Next') {
        el.scrollIntoView({ block: 'center' });
        el.click();
        return;
      }
    }
  });
  await sleep(7000);
  await page.screenshot({ path: path.join(OUT, '02-preview-and-confirm.png'), fullPage: true });

  // Expand all "Show more" buttons to reveal error/warning text
  console.log('→ expand all "Show more"');
  const expanded = await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    let count = 0;
    for (const el of walk(document)) {
      const tag = el.tagName;
      const txt = (el.innerText || el.textContent || '').trim();
      if ((tag === 'BUTTON' || tag === 'A') && (txt === 'Show more' || txt === 'Show details')) {
        el.click();
        count++;
      }
    }
    return count;
  });
  console.log(`  expanded ${expanded} buttons`);
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '03-errors-expanded.png'), fullPage: true });

  // Dump the page body text + all visible text in the Errors/Warnings section
  const dumped = await page.evaluate(() => {
    const body = (document.body.innerText || '').replace(/\s+\n/g, '\n');
    // Pull the section after "Errors, warnings and messages" up to the next H2
    const m = body.match(/Errors,\s*warnings[\s\S]*?(?=\nChanges to your supported|$)/);
    return { full: body.slice(0, 8000), section: m ? m[0] : null };
  });

  console.log('\n=== Errors/Warnings section ===');
  console.log(dumped.section || '[not found]');
  console.log('\n=== Body (first 4000 chars) ===');
  console.log(dumped.full.slice(0, 4000));

  fs.writeFileSync(path.join(OUT, 'errors.txt'), JSON.stringify(dumped, null, 2));
  console.log(`\nScreens + dump: ${OUT}`);

  await browser.close();
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
