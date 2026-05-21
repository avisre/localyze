const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  if (!page) { console.log('no tab'); process.exit(1); }
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await new Promise(r=>setTimeout(r,7000));
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  const m = t.match(/Changes not yet sent for review[\s\S]*?(?=Product updates|$)/);
  console.log('=== Changes section ===');
  console.log(m ? m[0].slice(0, 2500) : '[no changes section]');
  console.log('\n=== sent for review banner ===');
  const sentBanner = t.match(/(\d+\s+changes? sent for review|Pending review|Awaiting review)/i);
  console.log(sentBanner ? sentBanner[0] : '[none]');
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
