/**
 * adid-real-click.js — use Playwright's real .click() (with proper event
 * sequencing) on the radio buttons instead of HTMLElement.click().
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-real-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();

  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration?source=publishing-overview', { waitUntil:'domcontentloaded' });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '01-initial.png'), fullPage: true });

  // Get exact positions of Yes and No radios from DOM
  const radioPositions = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = {};
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'radio') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      else {
        let p = el.parentElement;
        for (let i=0;i<5&&p;i++) { const t=(p.innerText||'').trim(); if (t && t.length<200) {lbl=t;break;} p=p.parentElement;}
      }
      const r = el.getBoundingClientRect();
      const cx = r.x + r.width / 2;
      const cy = r.y + r.height / 2;
      if (/^Yes\b/i.test(lbl)) out.yes = { x: cx, y: cy };
      if (/^No\b/i.test(lbl)) out.no = { x: cx, y: cy };
    }
    return out;
  });
  console.log(`radios: ${JSON.stringify(radioPositions)}`);

  // Use page.mouse.click for real mouse events
  if (radioPositions.yes) {
    console.log(`→ real click Yes @${radioPositions.yes.x},${radioPositions.yes.y}`);
    await page.mouse.click(radioPositions.yes.x, radioPositions.yes.y);
    await sleep(3000);
    await page.screenshot({ path: path.join(OUT, '02-yes.png'), fullPage: true });
  }
  if (radioPositions.no) {
    console.log(`→ real click No @${radioPositions.no.x},${radioPositions.no.y}`);
    await page.mouse.click(radioPositions.no.x, radioPositions.no.y);
    await sleep(3000);
    await page.screenshot({ path: path.join(OUT, '03-no.png'), fullPage: true });
  }

  // Check Save state
  const saveInfo = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      if ((el.innerText || '').trim() !== 'Save') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      const disabled = el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true';
      return { disabled, x: r.x + r.width/2, y: r.y + r.height/2, w: r.width, h: r.height };
    }
    return null;
  });
  console.log(`Save: ${JSON.stringify(saveInfo)}`);

  if (saveInfo && !saveInfo.disabled) {
    console.log('→ real click Save');
    await page.mouse.click(saveInfo.x, saveInfo.y);
    await sleep(7000);
    await page.screenshot({ path: path.join(OUT, '04-saved.png'), fullPage: true });
    console.log(`url after save: ${page.url()}`);

    // Watch for any confirmation toast/banner
    const toast = await page.evaluate(() => {
      const t = document.body.innerText || '';
      const m = t.match(/saved|updated|complete/i);
      return m ? m[0] : null;
    });
    console.log(`toast: ${toast}`);
  } else {
    console.log('Save is disabled — try toggle Yes→No again');
  }

  // Now go to publishing and try Send
  console.log('\n→ publishing');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await sleep(10000);
  await page.screenshot({ path: path.join(OUT, '05-publishing.png'), fullPage: true });

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      stillBlocked: /1 issue affects/.test(t),
      hasIssue: /View \d+ issue/.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (state.stillBlocked) {
    console.log('❌ still blocked');
    process.exit(2);
  }

  // Try clicking "Send 2 changes for review"
  const sendInfo = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'BUTTON') continue;
      const t = (el.innerText || '').trim();
      if (!/Send \d+ changes? for review/.test(t)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      const disabled = el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true';
      return { disabled, text: t, x: r.x + r.width/2, y: r.y + r.height/2 };
    }
    return null;
  });
  console.log(`Send btn: ${JSON.stringify(sendInfo)}`);

  if (sendInfo && !sendInfo.disabled) {
    console.log('→ real click Send');
    await page.mouse.click(sendInfo.x, sendInfo.y);
    await sleep(5000);
    await page.screenshot({ path: path.join(OUT, '06-send.png'), fullPage: true });

    // Confirm dialog
    for (let i = 0; i < 4; i++) {
      const confirm = await page.evaluate(() => {
        function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
        for (const el of walk(document)) {
          if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
          const t = (el.innerText || '').trim();
          if (!['Send for review', 'Send', 'Confirm'].includes(t)) continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0) continue;
          // dialog button is small
          if (r.width > 250) continue;
          return { text: t, x: r.x + r.width/2, y: r.y + r.height/2 };
        }
        return null;
      });
      if (!confirm) break;
      console.log(`→ confirm: ${JSON.stringify(confirm)}`);
      await page.mouse.click(confirm.x, confirm.y);
      await sleep(4000);
    }
    await page.screenshot({ path: path.join(OUT, '07-confirmed.png'), fullPage: true });
  }

  // Final check
  await page.reload({ waitUntil:'domcontentloaded' });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '08-final.png'), fullPage: true });
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasV115InQueue: /Production[\s\S]{0,200}1\.1\.5/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasUnderReview: /Under review|Awaiting review|Pending publishing|sent for review/i.test(t),
      hasChangesNotYet: /Changes not yet sent for review/i.test(t),
    };
  });
  console.log(`final: ${JSON.stringify(final)}`);

  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
