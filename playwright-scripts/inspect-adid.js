const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration?source=publishing-overview', { waitUntil:'domcontentloaded' });
  await new Promise(r=>setTimeout(r,7000));
  // scroll to bottom
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await new Promise(r=>setTimeout(r,3000));
  await page.screenshot({ path: '/tmp/adid-bottom.png', fullPage: true });
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 6000));
  console.log('=== Body (full) ===');
  console.log(t.slice(0, 5500));

  // Also dump all radios and their states/labels
  const radios = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      const isRadio = (el.tagName === 'INPUT' && el.type === 'radio') || el.getAttribute('role') === 'radio';
      if (!isRadio) continue;
      const r = el.getBoundingClientRect();
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      if (!lbl) {
        let p = el.parentElement;
        for (let i = 0; i < 5 && p; i++) {
          const t = (p.innerText || '').trim();
          if (t && t.length < 200) { lbl = t; break; }
          p = p.parentElement;
        }
      }
      out.push({ y: Math.round(r.y), x: Math.round(r.x), w: Math.round(r.width), checked: el.checked || el.getAttribute('aria-checked') === 'true', label: lbl.slice(0, 150) });
    }
    return out;
  });
  console.log(`\n=== Radios ===`);
  for (const r of radios) console.log(`  @${r.x},${r.y} (w=${r.w}) checked=${r.checked} "${r.label}"`);

  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
