const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  if (!page) { console.log('no tab'); process.exit(1); }
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await new Promise(r=>setTimeout(r,7000));
  await page.screenshot({ path: '/tmp/pub-final.png', fullPage: true });
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 6000));
  console.log('=== FULL body (first 4500 chars) ===');
  console.log(t.slice(0, 4500));
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
