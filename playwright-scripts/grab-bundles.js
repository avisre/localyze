const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = null;
  for (const p of c.pages()) {
    if (p.url().includes('/releases/2/prepare')) { page = p; break; }
  }
  if (!page) { console.log('no draft tab'); process.exit(1); }
  await page.bringToFront();
  const t = await page.evaluate(() => {
    const t = document.body.innerText || '';
    const m = t.match(/App bundles[\s\S]*?(?=Previous release|Release details|$)/);
    return m ? m[0].slice(0, 2000) : '[not found]';
  });
  console.log('=== App bundles section ===');
  console.log(t);
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
