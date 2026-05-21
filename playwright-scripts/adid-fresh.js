/**
 * adid-fresh.js — hard-refresh the form, scroll to radios, use native
 * label click (which is the proper way for Material radio).
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-fresh-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  await page.bringToFront();
  await page.setViewportSize({ width: 1400, height: 1200 });

  // Hard navigate to App content overview first to reset state
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/overview', { waitUntil:'networkidle', timeout: 60000 });
  await sleep(5000);
  // Look for advertising ID link
  console.log('→ App content overview');
  await page.screenshot({ path: path.join(OUT, '01-overview.png'), fullPage: true });

  // Click on the Advertising ID row's "Manage" or arrow link
  const adidLink = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (/^Advertising ID/i.test(t) && t.length < 200) {
        // Find the link/button inside this row
        let p = el;
        for (let i=0;i<6&&p;i++) {
          const a = p.querySelector && p.querySelector('a');
          if (a) {
            const href = a.getAttribute('href');
            return { href, parent: t.slice(0, 60) };
          }
          p = p.parentElement;
        }
      }
    }
    return null;
  });
  console.log(`adid link: ${JSON.stringify(adidLink)}`);

  // Navigate via the link if found, else direct URL
  const targetUrl = adidLink && adidLink.href
    ? (adidLink.href.startsWith('http') ? adidLink.href : `https://play.google.com${adidLink.href}`)
    : 'https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/app-content/ad-id-declaration';

  await page.goto(targetUrl, { waitUntil: 'networkidle', timeout: 60000 });
  await sleep(7000);
  await page.screenshot({ path: path.join(OUT, '02-form-fresh.png'), fullPage: true });

  // Initial radio state
  const initial = await page.evaluate(() => {
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
  console.log(`initial radios: ${JSON.stringify(initial)}`);

  // Click Yes label by directly calling the label's click() — this is the
  // standard way for Material radios.
  console.log('→ click Yes LABEL');
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (el.tagName !== 'LABEL') continue;
      const t = (el.innerText || '').trim();
      if (/^Yes\b/i.test(t)) {
        el.scrollIntoView({block:'center'});
        el.click();
        return;
      }
    }
  });
  await sleep(3500);
  await page.screenshot({ path: path.join(OUT, '03-after-yes.png'), fullPage: true });

  const after = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT' || el.type !== 'radio') continue;
      let lbl = '';
      if (el.labels && el.labels[0]) lbl = (el.labels[0].innerText || '').trim();
      else { let p = el.parentElement; for (let i=0;i<5&&p;i++) { const t=(p.innerText||'').trim(); if (t&&t.length<200){lbl=t;break;} p=p.parentElement; } }
      out.push({ label: lbl.slice(0,60), checked: el.checked });
    }
    return out;
  });
  console.log(`after Yes click: ${JSON.stringify(after)}`);

  // Check Save state
  const saveCheck = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      if ((el.innerText || '').trim() !== 'Save') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      return {
        disabled: el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true',
        cls: el.className,
        ariaDisabled: el.getAttribute('aria-disabled'),
      };
    }
    return null;
  });
  console.log(`Save state: ${JSON.stringify(saveCheck)}`);

  if (!saveCheck.disabled) {
    console.log('→ Save');
    const ok = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
        if ((el.innerText || '').trim() !== 'Save') continue;
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({block:'center'}); el.click(); return true;
      }
      return false;
    });
    console.log(`  save click: ${ok}`);
    await sleep(8000);
    await page.screenshot({ path: path.join(OUT, '04-saved.png'), fullPage: true });
    console.log(`url: ${page.url()}`);
  } else {
    console.log(`❌ Save disabled. The backend already thinks state matches form.`);
    console.log(`   This means the saved declaration IS already "Yes" but Play's check is stale.`);
    console.log(`   Waiting 30s + retry to see if pre-flight resolves...`);
    await sleep(30000);
  }

  // Verify on publishing
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil:'networkidle', timeout: 60000 });
  await sleep(8000);
  await page.screenshot({ path: path.join(OUT, '05-publishing.png'), fullPage: true });

  const finalState = await page.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      stillBlocked: /1 issue affects/.test(t),
      hasSendBtn: /Send \d+ changes? for review/.test(t),
      hasChecks: /Running quick checks/i.test(t),
    };
  });
  console.log(`final state: ${JSON.stringify(finalState)}`);

  if (!finalState.stillBlocked && finalState.hasSendBtn) {
    console.log('→ Send for review');
    const sent = await page.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        if (el.tagName !== 'BUTTON') continue;
        const t = (el.innerText || '').trim();
        if (!/Send \d+ changes? for review/.test(t)) continue;
        const disabled = el.disabled || el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true';
        if (disabled) continue;
        el.scrollIntoView({block:'center'}); el.click();
        return { text: t };
      }
      return null;
    });
    console.log(`  sent: ${JSON.stringify(sent)}`);
    await sleep(5000);
    await page.screenshot({ path: path.join(OUT, '06-sent.png'), fullPage: true });

    // Confirm dialog
    for (let i = 0; i < 4; i++) {
      const c = await page.evaluate(() => {
        function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
        for (const el of walk(document)) {
          if (!['BUTTON','MATERIAL-BUTTON'].includes(el.tagName)) continue;
          const t = (el.innerText || '').trim();
          if (!['Send for review','Send','Confirm'].includes(t)) continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.width > 280) continue;
          el.click();
          return { t };
        }
        return null;
      });
      if (!c) break;
      console.log(`  confirm: ${JSON.stringify(c)}`);
      await sleep(4000);
    }
    await page.screenshot({ path: path.join(OUT, '07-confirmed.png'), fullPage: true });
  }

  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
