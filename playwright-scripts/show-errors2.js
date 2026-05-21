/**
 * show-errors2.js — Connect via CDP, expand error rows aggressively
 * (click on the row, click expand icon, scroll), dump the revealed text.
 * Assumes the Chrome tab is already on the Preview & confirm page OR
 * navigates there if not.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const PROD_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `show-errors2-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  // Pick the existing tab that's on the preview page if available
  let page = ctx.pages().find((p) => /tracks\/production\/releases\//.test(p.url()));
  if (!page) {
    page = await ctx.newPage();
    await page.setViewportSize({ width: 1400, height: 1200 });
    console.log(`→ goto ${PROD_URL}`);
    await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await sleep(6000);

    // Click "Releases" tab
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const txt = (el.innerText || '').trim();
        const aria = el.getAttribute('aria-label') || '';
        if ((el.tagName === 'TAB-BUTTON' || el.getAttribute('role') === 'tab') && (txt === 'Releases' || aria === 'Releases')) {
          el.click(); return;
        }
      }
    });
    await sleep(5000);

    // Click "Edit release"
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (!['BUTTON','A'].includes(el.tagName)) continue;
        if ((el.innerText || '').trim() === 'Edit release') { el.click(); return; }
      }
    });
    await sleep(8000);

    // Click Next
    await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (el.tagName !== 'BUTTON') continue;
        if ((el.innerText || '').trim() === 'Next') { el.click(); return; }
      }
    });
    await sleep(7000);
  }
  await page.bringToFront();
  await page.screenshot({ path: path.join(OUT, '01-current.png'), fullPage: true });
  console.log(`current url: ${page.url()}`);

  // Aggressive expansion: try multiple strategies
  console.log('→ expand errors via multiple strategies');
  const result = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const tries = [];

    // Strategy 1: click any element whose innerText is "Show more"
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      if (txt === 'Show more') {
        try { el.scrollIntoView({block:'center'}); el.click(); tries.push({s:'1-text', tag: el.tagName}); } catch{}
      }
    }
    // Strategy 2: click expand_more icons near "Errors" or "Warning" text
    for (const el of walk(document)) {
      const txt = (el.innerText || el.textContent || '').trim();
      if (txt === 'expand_more') {
        try { el.scrollIntoView({block:'center'}); el.click(); tries.push({s:'2-icon', tag: el.tagName}); } catch{}
      }
    }
    // Strategy 3: click parent <a> or <button> of "Show more"
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      if (txt === 'Show more') {
        let p = el;
        for (let i = 0; i < 6 && p; i++) {
          if (['BUTTON','A'].includes(p.tagName) || p.getAttribute('role') === 'button') {
            try { p.scrollIntoView({block:'center'}); p.click(); tries.push({s:'3-parent', tag: p.tagName, depth: i}); break; } catch{}
          }
          p = p.parentElement;
        }
      }
    }
    // Strategy 4: click anything with aria-expanded=false near "Errors"/"Warning"
    const bodyTxt = (document.body.innerText || '');
    if (/Errors,\s*warnings/i.test(bodyTxt)) {
      for (const el of walk(document)) {
        const exp = el.getAttribute && el.getAttribute('aria-expanded');
        if (exp === 'false') {
          try { el.click(); tries.push({s:'4-expandable', tag: el.tagName, aria: el.getAttribute('aria-label')}); } catch{}
        }
      }
    }
    return tries;
  });
  console.log(`  expansion attempts: ${JSON.stringify(result)}`);
  await sleep(3500);
  await page.screenshot({ path: path.join(OUT, '02-after-expand.png'), fullPage: true });

  // Read the error/warning text now
  const dumped = await page.evaluate(() => {
    const body = (document.body.innerText || '');
    // Slice around the errors heading
    const m = body.match(/Errors,\s*warnings[\s\S]*?(?=\nChanges to your supported|\nRelease delivery|\nRelease notes|$)/);
    return { full: body.slice(0, 10000), section: m ? m[0] : null };
  });

  console.log('\n=== Errors/Warnings section ===');
  console.log(dumped.section || '[not found]');
  fs.writeFileSync(path.join(OUT, 'errors.txt'), JSON.stringify(dumped, null, 2));
  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
