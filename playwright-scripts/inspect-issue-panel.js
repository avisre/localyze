/**
 * inspect-issue-panel.js — click "View issues" on publishing, then dump
 * the side panel that opens, including all clickable links.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `issue-panel-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil: 'domcontentloaded' });
  await sleep(7000);
  await page.screenshot({ path: path.join(OUT, '00-before-click.png'), fullPage: true });

  // Click "View issues" via the BUTTON containing that text (not text match, but DOM walking)
  const clickResult = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'BUTTON' && el.tagName !== 'A') continue;
      const t = (el.innerText || '').trim();
      if (/View issues/i.test(t)) {
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({block:'center'});
        el.click();
        return { clicked: true, text: t, href: el.getAttribute('href'), x: r.x, y: r.y, w: r.width, h: r.height };
      }
    }
    return null;
  });
  console.log(`click: ${JSON.stringify(clickResult)}`);
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '01-after-click.png'), fullPage: true });

  // Wait a bit more and dump
  await sleep(2000);
  await page.screenshot({ path: path.join(OUT, '02-stabilized.png'), fullPage: true });

  // Dump all visible content
  const links = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (!['A','BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      const t = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      if (!t && !aria) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      out.push({ tag: el.tagName, text: t.slice(0, 100), aria: aria.slice(0, 80), href: el.getAttribute('href'), x: Math.round(r.x), y: Math.round(r.y) });
    }
    return out;
  });
  console.log(`\n=== All visible links/buttons ===`);
  for (const l of links) {
    // skip nav rail (left side x < 250)
    if (l.x < 250) continue;
    // skip footer/header repetitive
    if (l.x > 0 && l.x < 100) continue;
    if (l.text.length > 100) continue;
    console.log(`  @${l.x},${l.y} ${l.tag} "${l.text}" ${l.href ? '['+l.href.slice(0,80)+']' : ''} ${l.aria ? 'aria="'+l.aria+'"' : ''}`);
  }

  // Get body text in the issue panel area
  const body = await page.evaluate(() => (document.body.innerText || '').slice(0, 6000));
  // find segment around "issue" or "advertising"
  const adidM = body.match(/Incomplete advertising[\s\S]*?(?=\n\n[A-Z]|$)/);
  console.log(`\n=== Advertising ID section ===\n${adidM ? adidM[0].slice(0, 1500) : '[not found in body]'}`);

  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
