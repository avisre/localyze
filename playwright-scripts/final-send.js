const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = c.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'networkidle' });
  await new Promise(r=>setTimeout(r,10000));
  
  // Click Send 2 changes for review
  console.log('→ Send 2 changes for review');
  const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
  try {
    await sendBtn.click({ timeout: 10000 });
    console.log('  ✓ clicked');
  } catch (e) { console.log(`  err: ${e.message.slice(0, 100)}`); }
  await new Promise(r=>setTimeout(r,5000));
  await page.screenshot({ path: '/tmp/final-send-1.png', fullPage: true });

  // Look for any dialog and click confirm
  const dialog = await page.evaluate(() => {
    const body = document.body.innerText || '';
    const m = body.match(/Send \d+ changes? for review\?[\s\S]*?(?=\nProduct updates|$)/);
    return m ? m[0].slice(0, 1000) : null;
  });
  console.log(`dialog text: ${dialog || '[none]'}`);

  for (let i = 0; i < 5; i++) {
    try {
      const c2 = page.getByRole('button', { name: /^Send for review$|^Send$|^Confirm$/ }).first();
      await c2.click({ timeout: 4000 });
      console.log(`  confirm ${i+1} clicked`);
      await new Promise(r=>setTimeout(r,4000));
    } catch { break; }
  }
  await page.screenshot({ path: '/tmp/final-send-2.png', fullPage: true });

  // Reload + verify
  await page.reload({ waitUntil:'networkidle' });
  await new Promise(r=>setTimeout(r,10000));
  await page.screenshot({ path: '/tmp/final-send-3.png', fullPage: true });
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  console.log('\n=== After ===');
  const sect = t.match(/Changes not yet sent[\s\S]*?(?=Product updates|$)/);
  console.log(sect ? sect[0].slice(0, 1500) : '[no section]');
  const under = t.match(/(Under review|Awaiting review|Pending publishing|sent for review)/);
  console.log(`\nbanner: ${under ? under[0] : '[none]'}`);
  
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
