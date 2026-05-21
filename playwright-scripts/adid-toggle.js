/**
 * adid-toggle.js — toggle Yes then No on the adid form to trigger state
 * change, then Save.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-toggle-${TS}`);
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

  // Navigate directly to adid declaration form
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration?source=publishing-overview', { waitUntil:'domcontentloaded' });
  await sleep(8000);
  await shot(page, '01-form');

  console.log('→ click Yes radio');
  await page.evaluate(() => {
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
        el.scrollIntoView({block:'center'}); el.click();
        return;
      }
    }
  });
  await sleep(3000);
  await shot(page, '02-yes');

  console.log('→ click No radio');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'radio') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      else {
        let p = el.parentElement;
        for (let i=0;i<5&&p;i++) { const t=(p.innerText||'').trim(); if (t && t.length<200) {lbl=t;break;} p=p.parentElement;}
      }
      if (/^No\b/i.test(lbl)) {
        el.scrollIntoView({block:'center'}); el.click();
        return;
      }
    }
  });
  await sleep(3000);
  await shot(page, '03-no');

  // Check Save button state
  const saveState = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      if ((el.innerText || '').trim() !== 'Save') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      return {
        disabled: el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true',
        text: (el.innerText || '').trim(),
        area: r.width * r.height,
      };
    }
    return null;
  });
  console.log(`Save button state: ${JSON.stringify(saveState)}`);

  // Click Save
  console.log('→ Save');
  const saved = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      if ((el.innerText || '').trim() !== 'Save') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    cands.sort((a,b)=>b.area-a.area);
    if (cands.length) { cands[0].el.scrollIntoView({block:'center'}); cands[0].el.click(); return true; }
    return false;
  });
  console.log(`  saved: ${saved}`);
  await sleep(7000);
  await shot(page, '04-saved');
  console.log(`url: ${page.url()}`);

  // Go to publishing
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await sleep(10000);
  await shot(page, '05-publishing');

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      stillBlocked: /1 issue affects/.test(t),
      hasV115: /1\.1\.5/.test(t),
      sentBanner: /sent for review|Awaiting review|Pending publishing|under review/i.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (state.stillBlocked) {
    console.log('❌ still blocked after adid save');
    process.exit(2);
  }

  // Click Send 2 changes for review
  console.log('→ Send 2 changes for review');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'BUTTON') continue;
      const t = (el.innerText || '').trim();
      if (/Send \d+ changes? for review/.test(t)) {
        const disabled = el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true';
        if (disabled) continue;
        el.scrollIntoView({block:'center'}); el.click();
        return;
      }
    }
  });
  await sleep(5000);
  await shot(page, '06-send-clicked');

  // Confirm dialog
  for (let i = 0; i < 5; i++) {
    const r = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
        const t = (el.innerText || '').trim();
        if (['Send for review', 'Send', 'Confirm'].includes(t)) {
          const rect = el.getBoundingClientRect();
          if (rect.width === 0 || rect.width > 300) continue;  // dialog buttons are smaller
          el.click();
          return { text: t };
        }
      }
      return null;
    });
    if (!r) break;
    console.log(`  confirm: ${JSON.stringify(r)}`);
    await sleep(4000);
  }
  await shot(page, '07-confirmed');

  // Final state
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasV115Pending: /Production[\s\S]*?1\.1\.5/.test(t),
      sentBanner: /sent for review|Awaiting review|Pending publishing|under review/i.test(t),
      sendBtnGone: !/Send \d+ changes? for review/.test(t),
    };
  });
  console.log(`final: ${JSON.stringify(final)}`);

  if (final.sentBanner || final.sendBtnGone) {
    console.log(`\n✅ DONE — v1.1.5 successfully sent for review!`);
  } else {
    console.log(`\n⚠️  Sent click executed but no confirmation banner. Check manually.`);
  }
  console.log(`Output: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
