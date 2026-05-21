const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  const p = c.pages().find(x => x.url().includes('/console/') && !x.url().includes('/about'));
  await p.bringToFront();
  await p.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/tracks/4697325931803269676/releases/3/prepare', { waitUntil:'networkidle' });
  await new Promise(r => setTimeout(r, 8000));
  console.log(`URL: ${p.url()}`);
  await p.screenshot({ path: '/tmp/r3.png', fullPage: true });
  const t = await p.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  console.log('=== release 3 page ===');
  console.log(t.slice(0, 3500));
  await b.close();
})().catch(e => console.error(e.message));
