/**
 * view-issues.js — click "View issues" on publishing, follow link.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `view-issues-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil: 'domcontentloaded' });
  await sleep(7000);

  // Click "View issues" link
  const clicked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (/^View issues?/i.test(t) || /^View \d+ issues?/i.test(t)) {
        const href = el.getAttribute && el.getAttribute('href');
        el.scrollIntoView({block:'center'}); el.click();
        return { href, text: t.slice(0, 60), tag: el.tagName };
      }
    }
    return null;
  });
  console.log(`View issues click: ${JSON.stringify(clicked)}`);
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '01-issues.png'), fullPage: true });
  console.log(`URL: ${page.url()}`);

  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 5000));
  console.log(`\n=== Body ===\n${t.slice(0, 4500)}`);

  // Also list visible buttons / links
  const links = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (!['A','BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      const t = (el.innerText || '').trim();
      if (!t || t.length > 80) continue;
      const href = el.getAttribute('href');
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      out.push({ tag: el.tagName, text: t.slice(0, 60), href: href ? href.slice(0, 100) : null });
    }
    return out.slice(0, 50);
  });
  console.log(`\n=== Links/Buttons ===`);
  for (const l of links) {
    if (/^(dashboard|Statistics|Publishing|Protected|Test and release|Monitor|Grow|Monetize|Production|Testing|Pre-registration|Advanced settings|Reach|Ratings|Android vitals|Policy|Store|Translations|Products|Financial|Subscriptions|Notifications|Status|Help|Privacy|Developer|Terms|All apps|Localyze$|Latest|Product updates|Open testing|Closed testing|Internal testing|Pre-launch|Overview|Device catalog|Reviews|Reviews analysis|Testing feedback|Crashes|App size|App content|Teacher|Store listings|Store listing|Store settings|Store analysis|Listing|App strings|Deep links|App pricing|One-time|Subscriptions|Merchandising|Price experiments|Promo codes|Financial|Overview|Retention|Cancellations|Revenue|Buyers|Conversions|Monetization|notifications|Notifications|sell|finance_mode|overview|shield|vital_signs|rocket_launch|expand_more|expand_less|arrow_right|arrow_left|chevron|keyboard_backspace|dashboard|bar_chart)/.test(l.text)) continue;
    console.log(`  ${l.tag} "${l.text}" ${l.href ? '['+l.href+']' : ''}`);
  }

  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
