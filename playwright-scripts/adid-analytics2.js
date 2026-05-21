/**
 * adid-analytics2.js — Yes radio + check Analytics by JS click + Save.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-an2-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1400 });

  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration', { waitUntil:'networkidle', timeout: 60000 });
  await sleep(5000);

  await page.evaluate(() => {
    const text = Array.from(document.querySelectorAll('*')).find(el => (el.innerText||'').trim().startsWith('Does your app use advertising ID?'));
    if (text) text.scrollIntoView({ block: 'start' });
  });
  await sleep(2000);

  // Check Yes
  console.log('→ check Yes');
  try { await page.getByLabel('Yes', { exact: true }).first().check({ timeout: 8000, force: true }); } catch (e) { console.log(`  err: ${e.message.slice(0,80)}`); }
  await sleep(3500);

  // Scroll down to see the checkboxes
  await page.evaluate(() => {
    const text = Array.from(document.querySelectorAll('*')).find(el => /Why does your app need to use advertising ID/i.test((el.innerText||'').trim()));
    if (text) text.scrollIntoView({ block: 'start' });
  });
  await sleep(2000);

  // Find and click Analytics checkbox via DOM walk
  console.log('→ click Analytics checkbox');
  const r = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // Find checkboxes in the "Why does your app need..." section
    // Strategy: find label elements containing "Analytics" then look for nearby checkbox
    for (const el of walk(document)) {
      if (el.tagName !== 'LABEL') continue;
      const t = (el.innerText || '').trim();
      // Match exact label "Analytics" (the first paragraph line starts with "Analytics")
      if (!/^Analytics\b/.test(t)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      el.scrollIntoView({ block: 'center' });
      el.click();
      // also try clicking the internal input
      const cb = el.querySelector('input[type="checkbox"]');
      if (cb && !cb.checked) cb.click();
      return { label: t.slice(0, 80), cbFound: !!cb };
    }
    // Fallback: 2nd visible checkbox in the form (after radios)
    let cbCount = 0;
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'checkbox') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cbCount++;
      // Pick the second one (Analytics is typically 2nd after "App functionality")
      if (cbCount === 2) {
        el.scrollIntoView({block:'center'}); el.click();
        return { fallback: 'second-checkbox', idx: cbCount };
      }
    }
    return null;
  });
  console.log(`  click: ${JSON.stringify(r)}`);
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '01-analytics.png'), fullPage: true });

  // Check state
  const state = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cbs = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'checkbox') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cbs.push({ label: lbl.slice(0, 60), checked: el.checked });
    }
    return cbs;
  });
  console.log(`checkboxes: ${JSON.stringify(state)}`);

  // Save
  const save = page.getByRole('button', { name: 'Save' }).first();
  const disabled = await save.isDisabled().catch(() => true);
  console.log(`Save disabled? ${disabled}`);
  if (disabled) { console.error('❌ still disabled'); process.exit(2); }

  await save.click({ timeout: 8000 });
  console.log('✓ saved');
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '02-saved.png'), fullPage: true });
  console.log(`url: ${page.url()}`);

  // Publishing
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'networkidle' });
  await sleep(10000);
  await page.screenshot({ path: path.join(OUT, '03-publishing.png'), fullPage: true });

  const pub = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      blocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
    };
  });
  console.log(`pub: ${JSON.stringify(pub)}`);

  if (!pub.blocked && pub.hasSendBtn) {
    console.log('→ Send for review');
    const sendBtn = page.locator('button').filter({ hasText: /Send \d+ changes? for review/ }).first();
    await sendBtn.click({ timeout: 10000 });
    await sleep(6000);
    for (let i = 0; i < 4; i++) {
      try { await page.getByRole('button', { name: /^(Send for review|Send|Confirm)$/ }).first().click({ timeout: 4000 }); await sleep(4000); }
      catch { break; }
    }
    await page.screenshot({ path: path.join(OUT, '04-sent.png'), fullPage: true });
  }

  // Final
  await page.reload({ waitUntil:'networkidle' });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '05-final.png'), fullPage: true });
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasUnderReview: /Under review|Awaiting review|Pending publishing/i.test(t),
      hasChangesNotYet: /Changes not yet sent for review/i.test(t),
    };
  });
  console.log(`final: ${JSON.stringify(final)}`);

  if (final.hasUnderReview || !final.hasChangesNotYet) {
    console.log(`\n✅✅ DONE — v1.1.5 sent for review!`);
  } else {
    console.log(`\n⚠️  state unclear, see ${OUT}`);
  }
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
