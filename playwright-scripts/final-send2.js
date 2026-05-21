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
  console.log('→ click Send 2 changes for review');
  const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
  await sendBtn.click({ timeout: 10000 });
  await new Promise(r=>setTimeout(r,5000));
  await page.screenshot({ path: '/tmp/final2-1.png', fullPage: true });

  // Click "Send changes for review" in dialog
  console.log('→ click Send changes for review (dialog confirm)');
  try {
    await page.getByRole('button', { name: 'Send changes for review', exact: true }).click({ timeout: 10000 });
    console.log('  ✓ clicked');
  } catch (e) {
    console.log(`  err: ${e.message.slice(0, 100)}`);
    // Fallback
    const r = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
        const t = (el.innerText || '').trim();
        if (t === 'Send changes for review') {
          el.scrollIntoView({block:'center'});
          el.click();
          return true;
        }
      }
      return false;
    });
    console.log(`  fallback: ${r}`);
  }
  await new Promise(r=>setTimeout(r,8000));
  await page.screenshot({ path: '/tmp/final2-2.png', fullPage: true });

  // Verify
  await page.reload({ waitUntil:'networkidle' });
  await new Promise(r=>setTimeout(r,10000));
  await page.screenshot({ path: '/tmp/final2-3.png', fullPage: true });
  
  const t = await page.evaluate(() => (document.body.innerText || '').slice(0, 4000));
  console.log('\n=== After ===');
  const sect = t.match(/(Changes (not yet )?sent for review|Sent for review|Under review|Awaiting review|Pending publishing)[\s\S]*?(?=Product updates|$)/);
  console.log(sect ? sect[0].slice(0, 1500) : '[no section]');
  const stillHas = /Send 2 changes for review/.test(t);
  console.log(`\nstill has 'Send 2 changes' button: ${stillHas}`);

  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
