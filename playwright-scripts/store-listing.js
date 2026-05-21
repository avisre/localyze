const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1400 });
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/store-listings', { waitUntil:'networkidle', timeout: 60000 });
  await new Promise(r=>setTimeout(r,8000));
  await page.screenshot({ path: '/tmp/store-listing.png', fullPage: true });
  console.log(`URL: ${page.url()}`);
  const txt = await page.evaluate(() => (document.body.innerText || '').slice(0, 3000));
  console.log(`\n=== Store listing ===\n${txt.slice(0, 2500)}`);
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
