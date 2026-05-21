const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  const page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/tracks/production', { waitUntil:'networkidle' });
  await new Promise(r=>setTimeout(r,6000));
  // Click Releases tab
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('tab-button, [role="tab"]')) {
      if ((el.innerText||'').trim() === 'Releases' || el.getAttribute('aria-label') === 'Releases') { el.click(); return; }
    }
  });
  await new Promise(r=>setTimeout(r,5000));
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 5000));
  // Find the section showing 1.1.5
  const m = t.match(/Track summary[\s\S]*?(?=Release dashboard|Releases|Active releases|Inactive|$)/);
  console.log('=== Production track ===');
  console.log(t.slice(0, 3500));
  await b.close();
})().catch(e => { console.error(e.message); });
