const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const AAB = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const DRAFT_URL = 'https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/tracks/4697325931803269676/releases/2/prepare';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  const p = c.pages().find(x => x.url().includes('/console/') && !x.url().includes('/about'));
  await p.bringToFront();
  await p.setViewportSize({ width: 1400, height: 1200 });
  
  console.log('→ goto /prepare');
  await p.goto(DRAFT_URL, { waitUntil: 'networkidle', timeout: 60000 });
  await sleep(8000);
  await p.screenshot({ path: '/tmp/up-fresh-1.png', fullPage: true });
  console.log(`URL: ${p.url()}`);

  // Check what's there
  const state = await p.evaluate(() => {
    const t = document.body.innerText || '';
    return {
      isInReview: /In review/.test(t),
      hasUpload: /Drop app bundles here|Upload/.test(t),
      has115: /12\s*\(1\.1\.5\)/.test(t),
      has116: /13\s*\(1\.1\.6\)/.test(t),
      url: location.href,
    };
  });
  console.log(`state: ${JSON.stringify(state)}`);

  // Remove v1.1.5 if present
  if (state.has115) {
    console.log('→ remove v1.1.5');
    await p.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      const re = /12\s*\(1\.1\.5\)/;
      let row = null;
      for (const el of walk(document)) {
        const t = (el.innerText || '').trim();
        const role = el.getAttribute && el.getAttribute('role');
        if ((role === 'row' || el.tagName === 'TR') && re.test(t)) { row = el; break; }
      }
      if (!row) return;
      function* desc(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* desc(el.shadowRoot); } }
      for (const el of desc(row)) {
        const aria = el.getAttribute('aria-label') || '';
        if (aria === 'Manage artifact') { el.click(); return; }
      }
    });
    await sleep(2500);
    await p.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role !== 'menuitem' && role !== 'option') continue;
        if ((el.innerText || '').trim() === 'Remove app bundle') { el.click(); return; }
      }
    });
    await sleep(2500);
    // Dialog confirm — "Remove" button inside dialog
    await p.evaluate(() => {
      function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
      for (const el of walk(document)) {
        const role = el.getAttribute && el.getAttribute('role');
        if (role !== 'dialog' && role !== 'alertdialog') continue;
        const btns = el.querySelectorAll('button, material-button');
        for (const btn of btns) {
          if ((btn.innerText || '').trim() === 'Remove') { btn.click(); return; }
        }
      }
    });
    await sleep(6000);
    await p.screenshot({ path: '/tmp/up-fresh-2.png', fullPage: true });
  }

  // Now try upload
  console.log('→ try upload');
  let uploaded = false;
  for (let i = 0; i < 6 && !uploaded; i++) {
    const inputs = await p.$$('input[type="file"]');
    console.log(`  attempt ${i+1}: ${inputs.length} inputs`);
    for (const fi of inputs) {
      try {
        const a = await fi.getAttribute('accept');
        if (a && /\.aab/i.test(a)) {
          console.log('  setInputFiles');
          await fi.setInputFiles(AAB, { timeout: 180000 });
          uploaded = true;
          break;
        }
      } catch (e) { console.log(`  err: ${e.message.slice(0, 80)}`); }
    }
    if (!uploaded) await sleep(3000);
  }
  if (!uploaded) {
    console.error('❌ no .aab input');
    // last resort: check ALL inputs again
    const all = await p.$$('input');
    console.log(`  total inputs: ${all.length}`);
    process.exit(2);
  }
  await sleep(5000);
  await p.screenshot({ path: '/tmp/up-fresh-3.png', fullPage: true });

  console.log('→ wait for v1.1.6 row');
  let attached = false;
  for (let i = 0; i < 144; i++) {
    await sleep(5000);
    const s = await p.evaluate(() => {
      const t = document.body.innerText || '';
      return {
        hasRow: /13\s*\(1\.1\.6\)/.test(t),
        spinner: /Uploading|Optimizing|Processing|optimized for distribution/i.test(t),
      };
    });
    if (i % 6 === 0) console.log(`  ${(i+1)*5}s: hasRow=${s.hasRow} spinner=${s.spinner}`);
    if (s.hasRow && !s.spinner) { attached = true; break; }
  }
  await p.screenshot({ path: '/tmp/up-fresh-4.png', fullPage: true });
  console.log(`attached: ${attached}`);

  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
