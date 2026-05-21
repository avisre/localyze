const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  const p = c.pages().find(x => x.url().includes('/console/') && !x.url().includes('/about'));
  await p.bringToFront();
  console.log(`Current URL: ${p.url()}`);
  const t = await p.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  console.log(`\n=== Body ===\n${t.slice(0, 3500)}`);
  await b.close();
})().catch(e => console.error(e.message));
