const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/'));
  if (!page) { console.log('no tab'); process.exit(1); }
  await page.bringToFront();
  // Bundle library URL
  const LIB = 'https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/bundle-explorer-selector';
  await page.goto(LIB, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await new Promise(r=>setTimeout(r, 8000));
  await page.screenshot({ path: '/tmp/bundle-lib.png', fullPage: true });
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  console.log(`URL: ${page.url()}`);
  console.log('---body:');
  console.log(t.slice(0, 3500));
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
