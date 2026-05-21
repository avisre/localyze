/**
 * adid-yes-analytics.js — Yes radio + check "Analytics" checkbox + Save.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-analytics-${TS}`);
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

  // Scroll to radios
  await page.evaluate(() => {
    const text = Array.from(document.querySelectorAll('*')).find(el => (el.innerText||'').trim().startsWith('Does your app use advertising ID?'));
    if (text) text.scrollIntoView({ block: 'start' });
  });
  await sleep(2000);

  // Check Yes radio
  console.log('→ check Yes radio');
  try {
    await page.getByLabel('Yes', { exact: true }).first().check({ timeout: 8000, force: true });
  } catch (e) { console.log(`  err: ${e.message.slice(0, 80)}`); }
  await sleep(3500);
  await page.screenshot({ path: path.join(OUT, '01-after-yes.png'), fullPage: true });

  // Dump all checkboxes that appeared
  const checkboxes = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'checkbox') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      else {
        let p = el.parentElement;
        for (let i=0;i<5&&p;i++) { const t=(p.innerText||'').trim(); if (t && t.length<200){lbl=t;break;} p=p.parentElement; }
      }
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      out.push({ label: lbl.slice(0, 100), checked: el.checked });
    }
    return out;
  });
  console.log(`checkboxes: ${JSON.stringify(checkboxes, null, 2)}`);

  // Check "Analytics" checkbox
  console.log('→ check Analytics');
  try {
    await page.getByLabel('Analytics', { exact: true }).first().check({ timeout: 8000, force: true });
    console.log('  ✓ Analytics checked');
  } catch (e) {
    console.log(`  ✗ getByLabel("Analytics") err: ${e.message.slice(0, 100)}`);
    // Fallback: click first checkbox whose label contains "Analytics"
    const r = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (el.tagName !== 'INPUT' || el.type !== 'checkbox') continue;
        let lbl = '';
        if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
        else { let p = el.parentElement; for (let i=0;i<5&&p;i++) { const t=(p.innerText||'').trim(); if (t&&t.length<200){lbl=t;break;} p=p.parentElement; } }
        if (/^Analytics/i.test(lbl)) {
          el.scrollIntoView({block:'center'}); el.click();
          return { lbl };
        }
      }
      return null;
    });
    console.log(`  fallback: ${JSON.stringify(r)}`);
  }
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '02-analytics-checked.png'), fullPage: true });

  // Save
  const save = page.getByRole('button', { name: 'Save' }).first();
  const disabled = await save.isDisabled().catch(() => true);
  console.log(`Save disabled? ${disabled}`);
  if (disabled) {
    console.error('❌ Save still disabled, need to check what else is required');
    const body = await page.evaluate(() => document.body.innerText.slice(0, 3000));
    console.log(body.slice(0, 2500));
    process.exit(2);
  }

  await save.click({ timeout: 8000 });
  console.log('✓ Save clicked');
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '03-saved.png'), fullPage: true });
  console.log(`url: ${page.url()}`);

  // Verify on publishing
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'networkidle' });
  await sleep(10000);
  await page.screenshot({ path: path.join(OUT, '04-publishing.png'), fullPage: true });

  const pub = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      blocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      checks: /Running quick checks/i.test(t),
      hasUnderReview: /Under review|Awaiting review|Pending publishing/i.test(t),
    };
  });
  console.log(`publishing: ${JSON.stringify(pub)}`);

  if (pub.blocked) {
    console.log('⚠️  still blocked');
    process.exit(2);
  }

  if (pub.hasSendBtn) {
    console.log('→ Send for review');
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    try {
      await sendBtn.click({ timeout: 10000 });
      await sleep(6000);
      await page.screenshot({ path: path.join(OUT, '05-send.png'), fullPage: true });
      // Confirm dialog
      for (let i = 0; i < 4; i++) {
        try {
          await page.getByRole('button', { name: /^(Send for review|Send|Confirm)$/ }).first().click({ timeout: 4000 });
          await sleep(4000);
        } catch { break; }
      }
      await page.screenshot({ path: path.join(OUT, '06-confirmed.png'), fullPage: true });
    } catch (e) { console.log(`  send err: ${e.message.slice(0, 100)}`); }
  }

  // Final state
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
    console.log(`\n⚠️  state unclear`);
  }
  console.log(`Output: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
