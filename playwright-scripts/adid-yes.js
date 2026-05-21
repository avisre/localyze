/**
 * adid-yes.js — set advertising ID declaration to YES (matches AAB), save,
 * then send v1.1.5 for review.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-yes-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  try { await page.screenshot({ path: path.join(OUT, `${label}.png`), fullPage: true }); } catch {}
  console.log(`📸 ${label}`);
}

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();

  console.log('\n=== STEP 1: open adid declaration form ===');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration?source=publishing-overview', { waitUntil:'domcontentloaded' });
  await sleep(8000);
  await shot(page, '01-form');

  // Real-click "Yes" radio
  const yesPos = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'radio') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      else {
        let p = el.parentElement;
        for (let i=0;i<5&&p;i++) { const t=(p.innerText||'').trim(); if (t && t.length<200) {lbl=t;break;} p=p.parentElement;}
      }
      if (/^Yes\b/i.test(lbl)) {
        const r = el.getBoundingClientRect();
        return { x: r.x + r.width/2, y: r.y + r.height/2 };
      }
    }
    return null;
  });
  console.log(`Yes pos: ${JSON.stringify(yesPos)}`);

  if (yesPos) {
    console.log('→ real-click Yes');
    await page.mouse.click(yesPos.x, yesPos.y);
    await sleep(3000);
    await shot(page, '02-yes');
  }

  // Real-click Save
  const savePos = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      if ((el.innerText || '').trim() !== 'Save') continue;
      const disabled = el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true';
      if (disabled) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      return { x: r.x + r.width/2, y: r.y + r.height/2 };
    }
    return null;
  });
  console.log(`Save pos: ${JSON.stringify(savePos)}`);

  if (savePos) {
    console.log('→ real-click Save');
    await page.mouse.click(savePos.x, savePos.y);
    await sleep(8000);
    await shot(page, '03-saved');
    console.log(`url: ${page.url()}`);
  } else {
    console.log('❌ Save disabled');
    process.exit(2);
  }

  console.log('\n=== STEP 2: publishing → check + send ===');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await sleep(10000);
  await shot(page, '04-publishing');

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      stillBlocked: /1 issue affects/.test(t),
      hasV115: /1\.1\.5/.test(t),
      runningChecks: /Running quick checks/i.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (state.stillBlocked) {
    console.log('⚠️  still blocked after Yes declaration');
    process.exit(2);
  }

  // Click Send (real click via mouse)
  const sendPos = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'BUTTON') continue;
      const t = (el.innerText || '').trim();
      if (!/Send \d+ changes? for review/.test(t)) continue;
      const disabled = el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true';
      if (disabled) continue;
      const r = el.getBoundingClientRect();
      return { x: r.x + r.width/2, y: r.y + r.height/2, text: t };
    }
    return null;
  });
  console.log(`Send: ${JSON.stringify(sendPos)}`);

  if (sendPos) {
    await page.mouse.click(sendPos.x, sendPos.y);
    await sleep(6000);
    await shot(page, '05-send-clicked');

    // Confirm dialog
    for (let i = 0; i < 4; i++) {
      const confirmPos = await page.evaluate(() => {
        function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
        for (const el of walk(document)) {
          if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
          const t = (el.innerText || '').trim();
          if (!['Send for review','Send','Confirm'].includes(t)) continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.width > 250) continue;
          return { x: r.x + r.width/2, y: r.y + r.height/2, text: t };
        }
        return null;
      });
      if (!confirmPos) break;
      console.log(`→ confirm: ${JSON.stringify(confirmPos)}`);
      await page.mouse.click(confirmPos.x, confirmPos.y);
      await sleep(5000);
    }
    await shot(page, '06-confirmed');
  }

  // Final check
  await page.reload({ waitUntil:'domcontentloaded' });
  await sleep(10000);
  await shot(page, '07-final');
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasChanges: /Changes not yet sent for review/i.test(t),
      hasSendBtn: /Send \d+ changes? for review/i.test(t),
      hasSent: /sent for review/i.test(t),
      hasUnderReview: /Under review|Awaiting review|Pending publishing/i.test(t),
      hasV115: /1\.1\.5/.test(t),
    };
  });
  console.log(`\nfinal: ${JSON.stringify(final)}`);

  if (final.hasUnderReview || !final.hasSendBtn) {
    console.log(`\n✅ DONE — v1.1.5 sent for review!`);
  } else {
    console.log(`\n⚠️  not confirmed sent — may still be processing. Check Chrome window.`);
  }
  console.log(`Output: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
