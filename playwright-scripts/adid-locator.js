/**
 * adid-locator.js — use Playwright's real Locator API for reliable
 * radio + save interaction.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-loc-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });

  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration?source=publishing-overview', { waitUntil:'networkidle', timeout: 60000 });
  await sleep(5000);
  await page.screenshot({ path: path.join(OUT, '01-init.png'), fullPage: true });

  // Scroll the Yes radio into view
  console.log('→ scroll to radios');
  await page.evaluate(() => {
    const labels = document.querySelectorAll('label, [role="row"]');
    for (const el of labels) {
      if ((el.innerText || '').trim().startsWith('Yes')) {
        el.scrollIntoView({ block: 'center' });
        return;
      }
    }
  });
  await sleep(2000);

  // Try Playwright's locator with text=Yes
  console.log('→ try locator getByText("Yes").click()');
  try {
    const yesLoc = page.getByText('Yes', { exact: true }).first();
    await yesLoc.click({ timeout: 10000, force: true });
    console.log('  ✓ getByText Yes click ok');
  } catch (e) {
    console.log(`  ✗ getByText err: ${e.message.slice(0, 100)}`);
  }
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '02-after-yes.png'), fullPage: true });

  // Check Save state
  const saveBtn = page.getByRole('button', { name: 'Save' }).first();
  const isDisabled = await saveBtn.isDisabled().catch(() => true);
  console.log(`Save disabled? ${isDisabled}`);

  if (!isDisabled) {
    console.log('→ Locator Save click');
    await saveBtn.click({ timeout: 8000 });
    await sleep(8000);
    await page.screenshot({ path: path.join(OUT, '03-saved.png'), fullPage: true });
    console.log(`url: ${page.url()}`);
  } else {
    // If still disabled, the radio state and backend state match.
    // Force a change by clicking No first then Yes.
    console.log('→ Save disabled — toggle via No first');
    try {
      const noLoc = page.getByText('No', { exact: true }).first();
      await noLoc.click({ timeout: 10000, force: true });
      await sleep(2500);
      const yesLoc = page.getByText('Yes', { exact: true }).first();
      await yesLoc.click({ timeout: 10000, force: true });
      await sleep(2500);
      await page.screenshot({ path: path.join(OUT, '02b-after-toggle.png'), fullPage: true });
    } catch (e) { console.log(`  toggle err: ${e.message.slice(0, 100)}`); }

    const stillDisabled = await saveBtn.isDisabled().catch(() => true);
    console.log(`Save disabled (after toggle)? ${stillDisabled}`);
    if (!stillDisabled) {
      await saveBtn.click({ timeout: 8000 });
      await sleep(8000);
      await page.screenshot({ path: path.join(OUT, '03b-saved.png'), fullPage: true });
    }
  }

  // Check publishing
  console.log('\n→ verify publishing');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'networkidle' });
  await sleep(10000);
  await page.screenshot({ path: path.join(OUT, '04-publishing.png'), fullPage: true });

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      stillBlocked: /1 issue affects/.test(t),
      hasV115: /1\.1\.5/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasUnderReview: /Under review|Awaiting review|Pending publishing/i.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (state.stillBlocked) {
    console.log('❌ still blocked');
    process.exit(2);
  }

  // Send for review
  if (state.hasSendBtn) {
    console.log('→ Send for review');
    try {
      // Find via aria-label or partial text
      const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
      await sendBtn.click({ timeout: 10000 });
      await sleep(6000);
      await page.screenshot({ path: path.join(OUT, '05-sent.png'), fullPage: true });
    } catch (e) { console.log(`  send err: ${e.message.slice(0, 100)}`); }

    // Confirm dialog
    for (let i = 0; i < 4; i++) {
      try {
        const c = page.getByRole('button', { name: /^(Send for review|Send|Confirm)$/ }).first();
        await c.click({ timeout: 4000 });
        await sleep(4000);
      } catch { break; }
    }
    await page.screenshot({ path: path.join(OUT, '06-confirmed.png'), fullPage: true });
  }

  // Final check
  await page.reload({ waitUntil:'networkidle' });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '07-final.png'), fullPage: true });
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasUnderReview: /Under review|Awaiting review|Pending publishing/i.test(t),
      hasChangesNotYet: /Changes not yet sent for review/i.test(t),
    };
  });
  console.log(`\nfinal: ${JSON.stringify(final)}`);

  if (final.hasUnderReview || !final.hasChangesNotYet) {
    console.log(`\n✅ DONE — v1.1.5 sent for review!`);
  } else {
    console.log(`\n⚠️  state unclear; check Chrome window`);
  }
  console.log(`Output: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
