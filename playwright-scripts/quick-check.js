const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = null;
  for (const p of c.pages()) {
    const u = p.url();
    if (u.includes('/releases/2/prepare') || u.includes('/tracks/')) { page = p; break; }
  }
  if (!page) { console.log('no draft tab'); process.exit(1); }
  await page.bringToFront();
  await page.screenshot({ path: '/tmp/quick-now.png', fullPage: true });
  const t = (await page.evaluate(() => document.body.innerText || '')).slice(0, 3000);
  console.log(`URL: ${page.url()}`);
  console.log(`\nText (first 2000 chars):\n${t.slice(0, 2000)}`);
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
