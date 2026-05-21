/**
 * adid-yes2.js — use Playwright locator API to find and click radio
 * properly (with page.getByLabel or locator(label)). Then Save.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-yes2-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });  // tall viewport so radios visible

  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration?source=publishing-overview', { waitUntil:'domcontentloaded' });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '01-initial.png'), fullPage: true });

  // Scroll down to bring radios into view
  await page.evaluate(() => {
    // find "Does your app use advertising ID?" text and scroll to it
    const els = document.querySelectorAll('*');
    for (const el of els) {
      if ((el.innerText || '').trim().startsWith('Does your app use advertising ID?')) {
        el.scrollIntoView({ block: 'center' });
        break;
      }
    }
  });
  await sleep(2500);
  await page.screenshot({ path: path.join(OUT, '02-scrolled.png'), fullPage: true });

  // Get the radio positions in viewport now
  const radioPos = await page.evaluate(() => {
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
      if (r.width === 0) continue;
      // Click slightly to the right of the radio (onto the label area)
      const labelEl = el.labels && el.labels[0];
      if (labelEl) {
        const lr = labelEl.getBoundingClientRect();
        if (/^Yes\b/i.test(lbl)) out.yes = { x: lr.x + 30, y: lr.y + lr.height/2 };
        if (/^No\b/i.test(lbl)) out.no = { x: lr.x + 30, y: lr.y + lr.height/2 };
      } else {
        if (/^Yes\b/i.test(lbl)) out.yes = { x: r.x + r.width/2, y: r.y + r.height/2 };
        if (/^No\b/i.test(lbl)) out.no = { x: r.x + r.width/2, y: r.y + r.height/2 };
      }
    }
    return out;
  });
  console.log(`positions: ${JSON.stringify(radioPos)}`);

  if (!radioPos.yes) { console.error('❌ Yes radio not visible'); process.exit(1); }

  // Click Yes via mouse
  console.log(`→ click Yes @${radioPos.yes.x},${radioPos.yes.y}`);
  await page.mouse.click(radioPos.yes.x, radioPos.yes.y);
  await sleep(3500);
  await page.screenshot({ path: path.join(OUT, '03-yes-clicked.png'), fullPage: true });

  // Check radio state
  const radioState = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'radio') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      else { let p = el.parentElement; for (let i=0;i<5&&p;i++) { const t=(p.innerText||'').trim(); if (t&&t.length<200){lbl=t;break;} p=p.parentElement; } }
      out.push({ label: lbl.slice(0,60), checked: el.checked, ariaChecked: el.getAttribute('aria-checked') });
    }
    return out;
  });
  console.log(`radio state after click: ${JSON.stringify(radioState)}`);

  // Check Save state
  const saveState = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      if ((el.innerText || '').trim() !== 'Save') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      return {
        disabled: el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true',
        x: r.x + r.width/2, y: r.y + r.height/2,
      };
    }
    return null;
  });
  console.log(`save state: ${JSON.stringify(saveState)}`);

  if (saveState && !saveState.disabled) {
    console.log(`→ click Save @${saveState.x},${saveState.y}`);
    await page.mouse.click(saveState.x, saveState.y);
    await sleep(8000);
    await page.screenshot({ path: path.join(OUT, '04-saved.png'), fullPage: true });
    console.log(`url: ${page.url()}`);
  } else {
    console.log(`❌ Save still disabled`);
    process.exit(2);
  }

  // Verify on publishing page
  console.log('\n=== verify ===');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'domcontentloaded' });
  await sleep(10000);
  await page.screenshot({ path: path.join(OUT, '05-publishing.png'), fullPage: true });

  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      stillBlocked: /1 issue affects/.test(t),
      hasV115: /1\.1\.5/.test(t),
      runningChecks: /Running quick checks/i.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  if (state.stillBlocked) {
    console.log('❌ still blocked after Yes declaration');
    process.exit(2);
  }

  console.log(`\n✅ Declaration saved. Send button: ${state.hasSendBtn ? 'visible' : 'gone'}`);

  // Click Send
  if (state.hasSendBtn) {
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
      await page.screenshot({ path: path.join(OUT, '06-send-clicked.png'), fullPage: true });
      // Confirm dialog
      for (let i = 0; i < 4; i++) {
        const c = await page.evaluate(() => {
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
        if (!c) break;
        console.log(`→ confirm: ${JSON.stringify(c)}`);
        await page.mouse.click(c.x, c.y);
        await sleep(5000);
      }
      await page.screenshot({ path: path.join(OUT, '07-confirmed.png'), fullPage: true });
    }
  }

  // Final check
  await page.reload({ waitUntil:'domcontentloaded' });
  await sleep(10000);
  await page.screenshot({ path: path.join(OUT, '08-final.png'), fullPage: true });
  const final = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasChanges: /Changes not yet sent for review/i.test(t),
      hasSent: /Under review|Awaiting review|Pending publishing/i.test(t),
      hasV115: /1\.1\.5/.test(t),
    };
  });
  console.log(`\nfinal: ${JSON.stringify(final)}`);

  if (final.hasSent || !final.hasChanges) {
    console.log(`\n✅ DONE — v1.1.5 sent for review!`);
  } else {
    console.log(`\n⚠️  state unclear. See screenshots in ${OUT}`);
  }
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
