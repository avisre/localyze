/**
 * inspect-fs-declaration.js — click "Go to declaration" on the release
 * preview, then dump the declaration form so we know what to fill.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `fs-decl-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const ctx = browser.contexts()[0];
  // Find the existing tab on the preview/review page
  let page = ctx.pages().find((p) => /\/releases\/\d+\/review/.test(p.url()));
  if (!page) page = ctx.pages().find((p) => p.url().includes('play.google.com/console'));
  if (!page) { console.error('❌ no Play Console tab'); process.exit(1); }
  await page.bringToFront();
  console.log(`current url: ${page.url()}`);

  // Ensure errors are expanded
  await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if (el.tagName === 'BUTTON' && aria === 'Show more validation details') el.click();
    }
  });
  await sleep(2500);
  await page.screenshot({ path: path.join(OUT, '01-preview-expanded.png'), fullPage: true });

  // Click "Go to declaration" link
  console.log('→ click "Go to declaration"');
  const declHref = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    for (const el of walk(document)) {
      const txt = (el.innerText || '').trim();
      if (txt === 'Go to declaration' && (el.tagName === 'A' || el.tagName === 'BUTTON')) {
        const href = el.getAttribute('href');
        el.scrollIntoView({block:'center'});
        el.click();
        return href || '[clicked, no href]';
      }
    }
    return null;
  });
  console.log(`  href: ${declHref}`);
  await sleep(7000);
  await page.screenshot({ path: path.join(OUT, '02-declaration-page.png'), fullPage: true });
  console.log(`now at: ${page.url()}`);

  // Dump the declaration form fields
  const els = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const out = [];
    for (const el of walk(document)) {
      const tag = el.tagName;
      const role = el.getAttribute && el.getAttribute('role');
      const text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 200);
      const aria = el.getAttribute && el.getAttribute('aria-label');
      const type = el.getAttribute && el.getAttribute('type');
      const name = el.getAttribute && el.getAttribute('name');
      const value = el.value;
      const checked = el.checked;
      const interactive = ['button','a','input','textarea','select'].includes(tag.toLowerCase()) ||
                          ['button','menuitem','option','tab','radio','checkbox','textbox','combobox'].includes(role);
      if (!interactive) continue;
      if (text.length > 180 && !['INPUT','TEXTAREA','SELECT'].includes(tag)) continue;
      out.push({ tag, role, text, aria, type, name, value, checked });
    }
    return out;
  });
  console.log(`\n=== Declaration form elements ===`);
  for (const el of els) {
    const parts = [`${el.tag}${el.role ? `[${el.role}]` : ''}`];
    if (el.type) parts.push(`type=${el.type}`);
    if (el.name) parts.push(`name=${el.name}`);
    if (el.value) parts.push(`value="${(el.value+'').slice(0,40)}"`);
    if (el.checked !== undefined && el.checked !== null) parts.push(`checked=${el.checked}`);
    if (el.aria) parts.push(`aria="${el.aria.slice(0,80)}"`);
    if (el.text) parts.push(`"${el.text.slice(0,80)}"`);
    console.log(`  ${parts.join(' ')}`);
  }

  // Also dump body text
  const body = await page.evaluate(() => (document.body.innerText || '').slice(0, 6000));
  console.log(`\n=== Body text (first 5000 chars) ===`);
  console.log(body.slice(0, 5000));

  fs.writeFileSync(path.join(OUT, 'form.json'), JSON.stringify({ url: page.url(), elements: els, body }, null, 2));
  console.log(`\nOutput: ${OUT}`);
  await browser.close();
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
