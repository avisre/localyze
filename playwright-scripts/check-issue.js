const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  if (!page) { console.log('no tab'); process.exit(1); }
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await new Promise(r=>setTimeout(r,7000));

  // Click "View 1 issue"
  const clicked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (/View \d+ issue/.test(t) && (el.tagName === 'A' || el.tagName === 'BUTTON')) {
        const href = el.getAttribute('href');
        el.scrollIntoView({block:'center'}); el.click();
        return { href, tag: el.tagName };
      }
    }
    return null;
  });
  console.log(`clicked: ${JSON.stringify(clicked)}`);
  await new Promise(r=>setTimeout(r,7000));
  console.log(`url: ${page.url()}`);
  await page.screenshot({ path: '/tmp/issue.png', fullPage: true });
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  console.log(`\n=== Issue page ===\n${t.slice(0, 3500)}`);
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
