/**
 * declare-adid.js — click "Update declaration" on advertising ID, set
 * "No, my app does not use advertising ID", save.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `adid-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function click(page, text, opts = {}) {
  const exact = !!opts.exact;
  const r = await page.evaluate(({ text, exact }) => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const cands = [];
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      const match = exact ? (txt === text || aria === text) : (txt.includes(text) || aria.includes(text));
      if (!match) continue;
      if (!['BUTTON','A','MATERIAL-BUTTON'].includes(el.tagName) && el.getAttribute('role') !== 'button') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      cands.push({ el, area: r.width * r.height });
    }
    if (!cands.length) return null;
    cands.sort((a, b) => b.area - a.area);
    const el = cands[0].el;
    el.scrollIntoView({ block: 'center' });
    el.click();
    return { tag: el.tagName, text: (el.innerText || '').slice(0, 60) };
  }, { text, exact });
  if (r) console.log(`✓ click "${text}" → ${r.tag} "${r.text}"`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!r;
}

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  let page = ctx.pages().find(p => p.url().includes('/console/') && !p.url().includes('/about'));
  if (!page) { console.error('❌ no tab'); process.exit(1); }
  await page.bringToFront();

  // Navigate to publishing
  console.log('→ publishing');
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/publishing', { waitUntil: 'domcontentloaded' });
  await sleep(7000);

  // Click "View 1 issue" link (already opens the panel)
  await click(page, 'View 1 issue', { exact: false, afterMs: 4000 });
  await page.screenshot({ path: path.join(OUT, '01-issue-open.png'), fullPage: true });

  // Click "Update declaration" - this should take us to the advertising ID form
  console.log('→ Update declaration');
  await click(page, 'Update declaration', { exact: true, afterMs: 8000 });
  await page.screenshot({ path: path.join(OUT, '02-adid-form.png'), fullPage: true });
  console.log(`  url: ${page.url()}`);

  // Dump form state
  const formState = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const radios = [];
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (el.tagName !== 'INPUT' && role !== 'radio') continue;
      if (el.type !== 'radio' && role !== 'radio') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      // try to find associated label
      const parent = el.closest('label, [role="row"], div');
      const label = parent ? (parent.innerText || '').trim().slice(0, 100) : '';
      radios.push({ aria: el.getAttribute('aria-label'), label, checked: el.checked, y: Math.round(r.y) });
    }
    return { url: location.href, radios, body: (document.body.innerText || '').slice(0, 3000) };
  });
  console.log(`url: ${formState.url}`);
  console.log(`\nradios found:`);
  for (const r of formState.radios) console.log(`  y=${r.y} checked=${r.checked} aria="${r.aria||''}" label="${r.label}"`);
  console.log(`\nbody excerpt:\n${formState.body.slice(0, 2500)}`);

  // Click "No, my app does not use advertising ID" radio
  console.log('\n→ select "No" radio');
  const noClicked = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    // find radio button labeled "No"
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      const isRadio = el.tagName === 'INPUT' && el.type === 'radio' || role === 'radio';
      if (!isRadio) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      // find nearby label
      const parent = el.closest('label, [role="row"], div');
      const t = parent ? (parent.innerText || '').trim() : '';
      if (/^No\b|does not use|doesn['']?t use/i.test(t)) {
        el.scrollIntoView({block:'center'}); el.click();
        return { label: t.slice(0, 80) };
      }
    }
    return null;
  });
  console.log(`  No click: ${JSON.stringify(noClicked)}`);
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '03-no-selected.png'), fullPage: true });

  // Click Save
  console.log('\n→ Save');
  await click(page, 'Save', { exact: true, afterMs: 6000 });
  await page.screenshot({ path: path.join(OUT, '04-saved.png'), fullPage: true });

  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch(e => { console.error('❌', e.stack || e.message); process.exit(1); });
