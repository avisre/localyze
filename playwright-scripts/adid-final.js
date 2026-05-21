/**
 * adid-final.js — full flow: View issues → Update declaration → No radio → Save → publishing → Send.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-final-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  try { await page.screenshot({ path: path.join(OUT, `${label}.png`), fullPage: true }); } catch {}
  console.log(`📸 ${label}`);
}

async function clickInDom(page, predicate, opts = {}) {
  const r = await page.evaluate((predStr) => {
    const predicate = new Function('el', `return (${predStr})(el);`);
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      try {
        if (!predicate(el)) continue;
      } catch { continue; }
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      el.scrollIntoView({ block: 'center' });
      el.click();
      return { tag: el.tagName, text: (el.innerText || '').trim().slice(0, 60), aria: el.getAttribute('aria-label') };
    }
    return null;
  }, predicate.toString());
  console.log(`  click: ${JSON.stringify(r)}`);
  await sleep(opts.afterMs || 3000);
  return r;
}

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();

  console.log('\n=== STEP 1: open publishing ===');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil: 'domcontentloaded' });
  await sleep(7000);
  await shot(page, '01-publishing');

  console.log('\n=== STEP 2: click View issues ===');
  await clickInDom(page, (el) => {
    if (!['BUTTON','A'].includes(el.tagName)) return false;
    return /^View issues/i.test((el.innerText || '').trim());
  }, { afterMs: 4500 });
  await shot(page, '02-issue-panel');

  console.log('\n=== STEP 3: click Update declaration ===');
  // The link is an <a> tag with text "Update declaration"
  await clickInDom(page, (el) => {
    if (el.tagName !== 'A') return false;
    return (el.innerText || '').trim() === 'Update declaration';
  }, { afterMs: 9000 });
  await shot(page, '03-adid-form');
  console.log(`  url: ${page.url()}`);

  console.log('\n=== STEP 4: select "No" radio ===');
  // The radios are usually <input type="radio"> with associated labels
  const radioDump = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      const isRadio = (el.tagName === 'INPUT' && el.type === 'radio') || el.getAttribute('role') === 'radio';
      if (!isRadio) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      // get nearest text
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      if (!lbl) {
        const lblId = el.getAttribute('aria-labelledby');
        if (lblId) { const e = document.getElementById(lblId); if (e) lbl = (e.innerText || '').trim(); }
      }
      if (!lbl) {
        // walk siblings or parent
        let p = el.parentElement;
        for (let i = 0; i < 5 && p; i++) {
          const t = (p.innerText || '').trim();
          if (t && t.length < 200) { lbl = t; break; }
          p = p.parentElement;
        }
      }
      out.push({ y: Math.round(r.y), label: lbl.slice(0, 150), checked: el.checked || el.getAttribute('aria-checked') === 'true' });
    }
    return out;
  });
  console.log(`  visible radios:`);
  for (const r of radioDump) console.log(`    y=${r.y} checked=${r.checked} "${r.label}"`);

  // Click the radio whose label starts with "No"
  await clickInDom(page, (el) => {
    const isRadio = (el.tagName === 'INPUT' && el.type === 'radio') || el.getAttribute('role') === 'radio';
    if (!isRadio) return false;
    // get nearest text
    let lbl = '';
    if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
    if (!lbl) {
      let p = el.parentElement;
      for (let i = 0; i < 5 && p; i++) {
        const t = (p.innerText || '').trim();
        if (t && t.length < 200) { lbl = t; break; }
        p = p.parentElement;
      }
    }
    return /^No\b/i.test(lbl) || /does not use/i.test(lbl) || /doesn['']?t use/i.test(lbl);
  }, { afterMs: 3000 });
  await shot(page, '04-no-selected');

  // Click Save
  console.log('\n=== STEP 5: Save declaration ===');
  await clickInDom(page, (el) => {
    if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) return false;
    return (el.innerText || '').trim() === 'Save';
  }, { afterMs: 8000 });
  await shot(page, '05-after-save');
  console.log(`  url: ${page.url()}`);

  console.log('\n=== STEP 6: publishing → send for review ===');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil: 'domcontentloaded' });
  await sleep(10000);
  await shot(page, '06-publishing');

  // Check state
  const state = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      stillBlocked: /1 issue affects/.test(t),
      hasV115: /1\.1\.5|\(12\)/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
    };
  });
  console.log(`  state: ${JSON.stringify(state)}`);

  if (state.stillBlocked) {
    console.log('  ⚠️  still blocked, declaration may not have saved');
  }

  // Click Send N changes for review
  await clickInDom(page, (el) => {
    if (el.tagName !== 'BUTTON') return false;
    return /Send \d+ changes? for review/.test((el.innerText || '').trim());
  }, { afterMs: 6000 });
  await shot(page, '07-send-clicked');

  // Confirm dialog
  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    const r = await clickInDom(page, (el) => {
      if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) return false;
      return (el.innerText || '').trim() === lbl;
    }, { afterMs: 5000 });
    if (r) break;
  }
  await shot(page, '08-sent');

  // Verify submission
  const finalState = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      hasV115Pending: /Production[\s\S]*?1\.1\.5/.test(t),
      hasSentBanner: /sent for review|Awaiting review|under review|Pending publishing/i.test(t),
    };
  });
  console.log(`  final: ${JSON.stringify(finalState)}`);

  console.log(`\n✅ DONE. Output: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
