const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = null;
  for (const p of c.pages()) {
    if (p.url().includes('/releases/2/review') || p.url().includes('/releases/2/')) { page = p; break; }
  }
  if (!page) { console.log('no tab'); process.exit(1); }
  await page.bringToFront();
  await new Promise(r=>setTimeout(r,2000));
  await page.screenshot({ path: '/tmp/review-now.png', fullPage: true });

  // List all visible buttons on the page
  const btns = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON','A'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
      const t = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      if (!t && !aria) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      // skip nav rail
      if (t.length > 80) continue;
      const disabled = el.disabled || el.getAttribute('aria-disabled') === 'true' || el.hasAttribute('disabled');
      out.push({ tag: el.tagName, text: t.slice(0,60), aria: aria.slice(0,60), disabled, y: Math.round(r.y) });
    }
    return out.sort((a, b) => a.y - b.y);
  });
  console.log(`URL: ${page.url()}`);
  console.log(`\n=== Visible buttons (top to bottom) ===`);
  for (const b of btns) {
    if (b.disabled) continue;  // skip greyed
    console.log(`  @y=${b.y} ${b.tag} ${b.disabled?'[disabled]':''} "${b.text}" aria="${b.aria}"`);
  }
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
