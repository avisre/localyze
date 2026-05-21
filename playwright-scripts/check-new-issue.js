const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await new Promise(r=>setTimeout(r,8000));
  
  // Click "View 1 issue" button
  const clicked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'BUTTON' && el.tagName !== 'A') continue;
      const t = (el.innerText || '').trim();
      if (/View \d+ issue/i.test(t)) {
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({block:'center'});
        el.click();
        return { text: t.slice(0, 60) };
      }
    }
    return null;
  });
  console.log(`click: ${JSON.stringify(clicked)}`);
  await new Promise(r=>setTimeout(r,5000));
  await page.screenshot({ path: '/tmp/new-issue.png', fullPage: true });
  
  const body = await page.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  console.log(`\n=== Body ===\n${body.slice(0, 3500)}`);
  
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
