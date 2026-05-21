const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = null;
  for (const p of c.pages()) {
    if (p.url().includes('/console/') && !p.url().includes('/about') && !p.url().includes('accounts.google')) { page = p; break; }
  }
  if (!page) { console.log('no tab'); process.exit(1); }
  await page.bringToFront();
  // Try /review URL
  const reviewUrl = 'https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/tracks/4697325931803269676/releases/2/review';
  await page.goto(reviewUrl, { waitUntil:'domcontentloaded' });
  await new Promise(r=>setTimeout(r,8000));
  await page.screenshot({ path: '/tmp/review-state.png', fullPage: true });

  // Dump all visible buttons sorted by Y position
  const data = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const btns = [];
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON','A'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
      const t = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      if (!t && !aria) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      if (t.length > 100) continue;
      const disabled = el.disabled || el.getAttribute('aria-disabled') === 'true' || el.hasAttribute('disabled');
      btns.push({ tag: el.tagName, text: t.slice(0,80), aria: aria.slice(0,60), disabled, y: Math.round(r.y) });
    }
    btns.sort((a,b) => a.y - b.y);
    // body text excerpt
    const body = (document.body.innerText || '').slice(0, 4000);
    return { url: location.href, btns, body };
  });
  console.log(`URL: ${data.url}\n`);
  console.log(`=== Buttons (top to bottom) ===`);
  for (const b of data.btns) {
    if (b.disabled) continue;
    if (/dashboard|Statistics|Publishing overview|Protected with Play|Test and release|Monitor|Grow|Monetize|Production|Testing|Pre-registration|Advanced settings|Reach|Ratings|Android vitals|Policy and|Store|Translations|Products|Financial|Subscriptions|Notifications|Status dashboard|Help|Privacy|Developer Distribution|Terms of Service|All apps|Localyze$|Latest releases|Product updates/.test(b.text)) continue;
    console.log(`  @y=${String(b.y).padStart(5)} ${b.tag} "${b.text}" aria="${b.aria}"`);
  }
  console.log(`\n=== Body excerpt ===\n${data.body.slice(0, 3500)}`);
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
