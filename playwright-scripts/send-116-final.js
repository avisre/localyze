const { chromium } = require('playwright');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  const p = c.pages().find(x => x.url().includes('/console/') && !x.url().includes('/about'));
  await p.bringToFront();
  await p.setViewportSize({ width: 1400, height: 1200 });
  await p.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded', timeout: 90000 });
  await sleep(10000);
  await p.screenshot({ path: '/tmp/send116-1.png', fullPage: true });

  console.log('→ click Send 1 change for review');
  const sendBtn = p.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
  await sendBtn.click({ timeout: 10000 });
  await sleep(6000);
  await p.screenshot({ path: '/tmp/send116-2.png', fullPage: true });

  console.log('→ confirm "Send changes for review"');
  try {
    await p.getByRole('button', { name: 'Send changes for review', exact: true }).click({ timeout: 8000 });
    console.log('✓ confirmed');
  } catch (e) {
    console.log(`err: ${e.message.slice(0, 80)}`);
  }
  await sleep(10000);
  await p.screenshot({ path: '/tmp/send116-3.png', fullPage: true });

  // Verify
  await p.reload({ waitUntil:'domcontentloaded' });
  await sleep(10000);
  await p.screenshot({ path: '/tmp/send116-4.png', fullPage: true });
  const t = await p.evaluate(() => (document.body.innerText || '').slice(0, 3000));
  const inReviewMatch = t.match(/Changes in review[\s\S]*?(?=Changes not yet|What you've|$)/);
  const notSentMatch = t.match(/Changes not yet sent for review[\s\S]*?(?=Changes in review|What you've|$)/);
  console.log('=== In review ===');
  console.log(inReviewMatch ? inReviewMatch[0].slice(0, 800) : '[none]');
  console.log('\n=== Not sent ===');
  console.log(notSentMatch ? notSentMatch[0].slice(0, 800) : '[none]');
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
