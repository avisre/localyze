/**
 * adid-direct.js — navigate directly to the advertising-id declaration
 * URL and click "No" + Save.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-direct-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function click(page, text, opts = {}) {
  const exact = !!opts.exact;
  const r = await page.evaluate(({ text, exact }) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      const match = exact ? (txt === text || aria === text) : (txt.includes(text) || aria.includes(text));
      if (!match) continue;
      if (!['BUTTON','A','MATERIAL-BUTTON'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return null;
    cands.sort((a, b) => b.area - a.area);
    const el = cands[0].el;
    el.scrollIntoView({ block: 'center' });
    el.click();
    return { tag: el.tagName, text: (el.innerText || '').slice(0, 60) };
  }, { text, exact });
  if (r) console.log(`✓ click "${text}"`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!r;
}

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  if (!page) { console.error('❌ no tab'); process.exit(1); }
  await page.bringToFront();

  // Try the advertising ID URL directly
  const candidates = [
    `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/app-content/advertising-id`,
    `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/app-content/overview`,
  ];

  let formUrl = null;
  for (const u of candidates) {
    console.log(`→ try ${u}`);
    try {
      await page.goto(u, { waitUntil: 'domcontentloaded', timeout: 60000 });
      await sleep(7000);
      const url = page.url();
      console.log(`  landed: ${url}`);
      if (url.includes('/app-content/')) { formUrl = u; break; }
    } catch (e) { console.log(`  err: ${e.message.slice(0, 80)}`); }
  }

  await page.screenshot({ path: path.join(OUT, '01-initial.png'), fullPage: true });
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 3500));
  console.log(`\n=== Page text ===\n${t.slice(0, 3000)}`);

  // If we're on app-content overview, click "Advertising ID" / "Manage" / "Start" for that section
  if (page.url().includes('app-content/overview')) {
    console.log('→ on overview, click into Advertising ID section');
    // Look for a row containing "Advertising ID" with a "Start" or "Manage" button
    const sectionClicked = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      // find anchor/button near "Advertising ID"
      for (const el of walk(document)) {
        const t = (el.innerText || '').trim();
        if (!/advertising id/i.test(t)) continue;
        if (t.length > 200) continue;
        // walk up to find a clickable
        let p = el;
        for (let i = 0; i < 8 && p; i++) {
          if (p.tagName === 'A' || (p.querySelector && p.querySelector('a, button'))) {
            const link = p.tagName === 'A' ? p : p.querySelector('a, button');
            if (link) { link.scrollIntoView({block:'center'}); link.click(); return { text: t.slice(0, 80) }; }
          }
          p = p.parentElement;
        }
      }
      return null;
    });
    console.log(`  result: ${JSON.stringify(sectionClicked)}`);
    await sleep(7000);
    await page.screenshot({ path: path.join(OUT, '02-adid-section.png'), fullPage: true });
  }

  console.log(`\nFinal URL: ${page.url()}`);

  // Now select the "No" radio
  console.log('→ select "No" radio (does not use advertising ID)');
  const noClick = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }

    // Strategy: find radio elements, then check their associated label
    function getLabel(input) {
      // 1. labels collection
      if (input.labels && input.labels[0]) return (input.labels[0].innerText || '').trim();
      // 2. aria-labelledby
      const lblId = input.getAttribute('aria-labelledby');
      if (lblId) {
        const lbl = document.getElementById(lblId);
        if (lbl) return (lbl.innerText || '').trim();
      }
      // 3. parent text
      let p = input.parentElement;
      for (let i = 0; i < 4 && p; i++) {
        const t = (p.innerText || '').trim();
        if (t && t.length < 200) return t;
        p = p.parentElement;
      }
      return '';
    }

    const radios = [];
    for (const el of walk(document)) {
      const isRadio = (el.tagName === 'INPUT' && el.type === 'radio') || el.getAttribute('role') === 'radio';
      if (!isRadio) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      radios.push({ el, label: getLabel(el), y: r.y });
    }
    radios.sort((a, b) => a.y - b.y);
    // Find the one whose label says "No" / "does not"
    for (const r of radios) {
      if (/^No\b/i.test(r.label) || /does not use/i.test(r.label) || /doesn['']?t use/i.test(r.label)) {
        r.el.scrollIntoView({ block: 'center' });
        r.el.click();
        return { label: r.label.slice(0, 100) };
      }
    }
    // Fallback: show all labels
    return { error: 'no match', labels: radios.map(r => r.label.slice(0, 80)) };
  });
  console.log(`  no click: ${JSON.stringify(noClick)}`);
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '03-no-selected.png'), fullPage: true });

  // Click Save
  await click(page, 'Save', { exact: true, afterMs: 6000 });
  await page.screenshot({ path: path.join(OUT, '04-saved.png'), fullPage: true });
  console.log(`url after save: ${page.url()}`);

  // Navigate to publishing to send for review
  console.log('\n→ publishing → send for review');
  await page.goto(`https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`, { waitUntil: 'domcontentloaded' });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '05-publishing.png'), fullPage: true });

  // Check if "Send 2 changes for review" is enabled now
  const sent = await click(page, 'Send 2 changes for review', { afterMs: 6000 });
  if (!sent) await click(page, 'Send for review', { afterMs: 6000 });
  await page.screenshot({ path: path.join(OUT, '06-after-send.png'), fullPage: true });

  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    if (await click(page, lbl, { exact: true, afterMs: 5000 })) break;
  }
  await page.screenshot({ path: path.join(OUT, '07-confirmed.png'), fullPage: true });

  console.log(`\n✅ DONE. Output: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
