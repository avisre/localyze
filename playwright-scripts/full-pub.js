const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'networkidle' });
  await new Promise(r=>setTimeout(r,7000));
  await page.screenshot({ path: '/tmp/full-pub.png', fullPage: true });
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 5000));
  console.log(t.slice(0, 4500));
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
