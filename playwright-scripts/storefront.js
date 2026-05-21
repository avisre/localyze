const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  const page = await c.newPage();
  await page.setViewportSize({ width: 1400, height: 1000 });
  console.log('→ public Play Store listing');
  await page.goto('https://play.google.com/store/apps/details?id=com.localyze', { waitUntil:'networkidle', timeout: 60000 });
  await new Promise(r=>setTimeout(r,5000));
  await page.screenshot({ path: '/tmp/store-front.png', fullPage: true });
  const txt = await page.evaluate(() => (document.body.innerText || '').slice(0, 3000));
  console.log(`URL: ${page.url()}`);
  console.log(`\n=== Store front text ===\n${txt.slice(0, 2500)}`);
  await page.close();
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
