/**
 * adid-check.js — use Playwright Locator.check() for the Yes radio.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-check-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });

  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration', { waitUntil:'networkidle', timeout: 60000 });
  await sleep(5000);
  await page.screenshot({ path: path.join(OUT, '01-init.png'), fullPage: true });

  // Scroll the radios into view
  await page.evaluate(() => {
    const text = Array.from(document.querySelectorAll('*')).find(el => (el.innerText||'').trim().startsWith('Does your app use advertising ID?'));
    if (text) text.scrollIntoView({ block: 'start' });
  });
  await sleep(2500);

  // Use Locator.check() on the Yes radio
  console.log('→ getByLabel("Yes").check()');
  try {
    const yes = page.getByLabel('Yes', { exact: true }).first();
    await yes.check({ timeout: 10000, force: true });
    console.log('  ✓ check ok');
  } catch (e) {
    console.log(`  ✗ getByLabel err: ${e.message.slice(0, 120)}`);
    // Fallback: page.locator('input[type=radio]') + filter by label
    try {
      const radios = page.locator('input[type="radio"]');
      const count = await radios.count();
      console.log(`  radios: ${count}`);
      for (let i = 0; i < count; i++) {
        const r = radios.nth(i);
        const label = await r.evaluate((el) => {
          if (el.labels && el.labels[0]) return el.labels[0].innerText;
          return '';
        });
        console.log(`    radio ${i}: label="${label.slice(0,30)}"`);
        if (/^Yes\b/i.test(label)) {
          await r.check({ force: true });
          console.log(`  ✓ checked radio ${i}`);
          break;
        }
      }
    } catch (e2) { console.log(`  ✗ fallback err: ${e2.message.slice(0, 120)}`); }
  }
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '02-after-check.png'), fullPage: true });

  // Verify
  const state = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'radio') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      out.push({ label: lbl.slice(0,60), checked: el.checked });
    }
    return out;
  });
  console.log(`radio state: ${JSON.stringify(state)}`);

  // Save
  const save = page.getByRole('button', { name: 'Save' }).first();
  const disabled = await save.isDisabled().catch(() => true);
  console.log(`Save disabled? ${disabled}`);

  if (!disabled) {
    await save.click({ timeout: 8000 });
    await sleep(8000);
    console.log('✓ saved');
    await page.screenshot({ path: path.join(OUT, '03-saved.png'), fullPage: true });
  } else {
    console.log('Save still disabled — Material radio state may require fillSequence');
    // Try keyboard navigation
    try {
      const yes = page.getByLabel('Yes', { exact: true }).first();
      await yes.focus();
      await page.keyboard.press('Space');
      await sleep(2500);
      const stillDisabled = await save.isDisabled().catch(() => true);
      console.log(`After Space, disabled? ${stillDisabled}`);
      if (!stillDisabled) {
        await save.click({ timeout: 8000 });
        await sleep(8000);
        await page.screenshot({ path: path.join(OUT, '03-saved-kbd.png'), fullPage: true });
      }
    } catch (e) { console.log(`  kbd err: ${e.message.slice(0, 100)}`); }
  }

  // Check publishing
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'networkidle' });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '04-publishing.png'), fullPage: true });

  const pub = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      blocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      checks: /Running quick checks/i.test(t),
    };
  });
  console.log(`pub: ${JSON.stringify(pub)}`);

  if (!pub.blocked && pub.hasSendBtn) {
    console.log('→ click Send for review');
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    await sendBtn.click({ timeout: 10000 });
    await sleep(6000);
    await page.screenshot({ path: path.join(OUT, '05-send.png'), fullPage: true });

    // Confirm
    for (let i = 0; i < 4; i++) {
      try {
        await page.getByRole('button', { name: /^(Send for review|Send|Confirm)$/ }).first().click({ timeout: 4000 });
        await sleep(4000);
      } catch { break; }
    }
    await page.screenshot({ path: path.join(OUT, '06-confirmed.png'), fullPage: true });
  }

  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
