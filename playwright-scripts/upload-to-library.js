const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const AAB = path.resolve(__dirname, '..', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const LIB_URL = 'https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/bundle-explorer-selector';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  console.log(`AAB: ${(fs.statSync(AAB).size/1e6).toFixed(1)} MB`);
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  const p = c.pages().find(x => x.url().includes('/console/') && !x.url().includes('/about'));
  await p.bringToFront();
  await p.setViewportSize({ width: 1400, height: 1200 });

  console.log('→ goto bundle library');
  await p.goto(LIB_URL, { waitUntil:'networkidle', timeout: 60000 });
  await sleep(8000);
  await p.screenshot({ path: '/tmp/lib-up-1.png', fullPage: true });

  // Click "Upload new version" button
  console.log('→ click Upload new version');
  const clicked = await p.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      if (!['BUTTON','A','MATERIAL-BUTTON'].includes(el.tagName)) continue;
      const t = (el.innerText || '').trim();
      if (/Upload new version/i.test(t)) {
        const r = el.getBoundingClientRect();
        if (r.width === 0) continue;
        el.scrollIntoView({block:'center'}); el.click();
        return { text: t };
      }
    }
    return null;
  });
  console.log(`  ${JSON.stringify(clicked)}`);
  await sleep(4000);
  await p.screenshot({ path: '/tmp/lib-up-2.png', fullPage: true });

  // Find file input and upload
  console.log('→ find file input + upload');
  let uploaded = false;
  for (let i = 0; i < 8 && !uploaded; i++) {
    const inputs = await p.$$('input[type="file"]');
    console.log(`  attempt ${i+1}: ${inputs.length} inputs`);
    for (const fi of inputs) {
      try {
        const a = await fi.getAttribute('accept');
        if (a && /\.aab/i.test(a)) {
          console.log('  → setInputFiles');
          await fi.setInputFiles(AAB, { timeout: 180000 });
          console.log('✓ uploaded');
          uploaded = true;
          break;
        }
      } catch (e) { console.log(`  err: ${e.message.slice(0, 80)}`); }
    }
    if (!uploaded) await sleep(3000);
  }
  await sleep(8000);
  await p.screenshot({ path: '/tmp/lib-up-3.png', fullPage: true });

  // Wait for v1.1.6 in library
  console.log('→ wait for v1.1.6 in library');
  for (let i = 0; i < 144; i++) {
    await sleep(5000);
    const has = await p.evaluate(() => {
      const t = document.body.innerText || '';
      return /\b13\b[\s\S]{0,30}1\.1\.6/.test(t) || /1\.1\.6[\s\S]{0,30}\b13\b/.test(t);
    });
    if (has) { console.log(`✓ v1.1.6 in library after ~${(i+1)*5}s`); break; }
    if (i % 6 === 0) console.log(`  ${(i+1)*5}s elapsed`);
  }
  await p.screenshot({ path: '/tmp/lib-up-4.png', fullPage: true });
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
