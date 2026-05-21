/**
 * ship-cdp.js — finish v1.1.3 by attaching to a Chrome instance already
 * running on CDP port 9222 (started via
 * scripts/start_chrome_for_play_console.sh).
 *
 * Why CDP: when Playwright launches Chrome (persistent context) and the
 * window is closed, the Node script dies. With CDP mode, Chrome is owned
 * by the user-launched shell — Playwright just attaches. The script can
 * detach and the browser keeps running.
 *
 * The script opens a SEPARATE tab for automation so the user's sign-in tab
 * is untouched. After completion the script exits but Chrome stays alive.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const RELEASE_NAME = '1.1.3';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
</en-US>`;

const PROD_URL = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
const PUB_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship-cdp-${TS}`);
fs.mkdirSync(OUT, { recursive: true });
let stepN = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, label) {
  stepN++;
  const lbl = `${String(stepN).padStart(2, '0')}-${label}`;
  try { await page.screenshot({ path: path.join(OUT, `${lbl}.png`), fullPage: false }); } catch {}
  console.log(`📸 ${lbl}`);
}

async function snapshotElements(page) {
  return page.evaluate(() => {
    function walk(root, out, depth = 0) {
      if (depth > 8 || !root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) {
        if (el.shadowRoot) walk(el.shadowRoot, out, depth + 1);
        const tag = el.tagName.toLowerCase();
        const role = el.getAttribute && el.getAttribute('role');
        const text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 160);
        const href = el.getAttribute && el.getAttribute('href');
        const ariaLabel = el.getAttribute && el.getAttribute('aria-label');
        const isInteractive = ['button', 'a'].includes(tag) ||
                              ['button', 'menuitem', 'option', 'tab', 'link', 'textbox'].includes(role) ||
                              tag === 'input' || tag === 'textarea';
        if (!isInteractive) continue;
        if (text.length > 130 && tag !== 'a') continue;
        out.push({ tag, role, text, href, ariaLabel });
      }
    }
    const out = [];
    walk(document, out);
    return out;
  });
}

async function dump(page, label) {
  await shot(page, label);
  try {
    const els = await snapshotElements(page);
    fs.writeFileSync(path.join(OUT, `${stepN}-${label}.json`), JSON.stringify({ url: page.url(), els }, null, 2));
  } catch (e) { console.log(`  dump err: ${e.message.slice(0, 80)}`); }
}

async function shadowClickByText(page, text, opts = {}) {
  const exact = opts.exact !== false;
  const result = await page.evaluate(({ text, exact }) => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) {
        yield el;
        if (el.shadowRoot) yield* walk(el.shadowRoot);
      }
    }
    const candidates = [];
    for (const el of walk(document)) {
      const txt = (el.innerText || el.textContent || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      const matchTxt = exact ? (txt === text || aria === text) : (txt.includes(text) || aria.includes(text));
      if (!matchTxt) continue;
      const tag = el.tagName;
      const role = el.getAttribute('role');
      const isInteractive = ['BUTTON', 'A'].includes(tag) ||
                            ['button', 'menuitem', 'option', 'tab', 'link'].includes(role);
      if (!isInteractive) continue;
      candidates.push(el);
    }
    if (!candidates.length) return null;
    candidates.sort((a, b) => {
      const ra = a.getBoundingClientRect();
      const rb = b.getBoundingClientRect();
      return (ra.width * ra.height) - (rb.width * rb.height);
    });
    const el = candidates[0];
    el.scrollIntoView({ block: 'center' });
    el.click();
    return { tag: el.tagName, text: (el.innerText || '').slice(0, 80) };
  }, { text, exact });
  if (result) console.log(`✓ click "${text}" → ${result.tag} "${result.text}"`);
  else console.log(`✗ click "${text}" → not found`);
  await sleep(opts.afterMs || 3500);
  return !!result;
}

(async () => {
  // 1. Connect to existing CDP Chrome
  console.log('→ connect to Chrome on http://127.0.0.1:9222');
  let browser;
  try {
    browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  } catch (e) {
    console.error(`❌ could not connect: ${e.message}`);
    console.error('   Run: bash scripts/start_chrome_for_play_console.sh');
    process.exit(2);
  }
  const ctx = browser.contexts()[0];
  if (!ctx) { console.error('❌ no contexts'); process.exit(3); }
  console.log(`✓ connected, ${ctx.pages().length} existing tabs`);
  for (const p of ctx.pages()) console.log(`  tab: ${p.url().slice(0, 100)}`);

  // 2. Open a fresh tab for automation
  const page = await ctx.newPage();
  await page.setViewportSize({ width: 1400, height: 900 });

  // 3. Wait for signed-in state by going to Production URL and seeing if it loads or redirects
  console.log('\n→ checking sign-in state via Production URL');
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(5000);

  let signedIn = false;
  let lastUrl = '';
  while (!signedIn) {
    const url = page.url();
    if (url !== lastUrl) { console.log(`  url=${url.slice(0, 110)}`); lastUrl = url; }
    if (url.startsWith('https://play.google.com/console/') && !url.includes('accounts.google.com')) {
      try {
        const ok = await page.evaluate(() => /Publishing overview|Production|Release/i.test(document.body.innerText || ''));
        if (ok) { signedIn = true; break; }
      } catch {}
    }
    await sleep(4000);
  }
  console.log('✓ signed in');
  await dump(page, 'signed-in');

  // ── STEP 1: Publishing → discard old v1.0.3 ────────────────────────────
  console.log('\n=== STEP 1: discard old v1.0.3 pending change ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await dump(page, 'publishing-initial');

  console.log('→ open first "More actions" menu (v1.0.3 row)');
  const opened = await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    const btns = [];
    for (const el of walk(document)) {
      if (el.tagName !== 'BUTTON') continue;
      const aria = el.getAttribute('aria-label') || '';
      if (aria === 'More actions') btns.push(el);
    }
    if (!btns.length) return null;
    btns[0].scrollIntoView({ block: 'center' });
    btns[0].click();
    return { count: btns.length };
  });
  console.log(`  result: ${JSON.stringify(opened)}`);
  await sleep(2500);
  await dump(page, 'menu-open');

  // Dump menu items for debugging
  const menuItems = await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    const items = [];
    for (const el of walk(document)) {
      const role = el.getAttribute && el.getAttribute('role');
      if (role === 'menuitem' || role === 'option') {
        items.push((el.innerText || el.textContent || '').trim());
      }
    }
    return items;
  });
  console.log(`  menu items: ${JSON.stringify(menuItems)}`);

  let discarded = false;
  for (const label of ['Discard changes', 'Discard change', 'Discard', 'Remove']) {
    discarded = await shadowClickByText(page, label, { exact: false, afterMs: 2500 });
    if (discarded) break;
  }
  await dump(page, 'after-discard-click');

  // Confirm dialog
  for (const label of ['Discard', 'Confirm', 'OK', 'Yes']) {
    if (await shadowClickByText(page, label, { afterMs: 4000 })) break;
  }
  await dump(page, 'after-discard-confirm');

  // ── STEP 2: Production → Releases tab → find draft ────────────────────
  console.log('\n=== STEP 2: open Production Releases tab ===');
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await dump(page, 'production-track');

  console.log('→ click Releases tab');
  await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    for (const el of walk(document)) {
      const tag = el.tagName;
      const role = el.getAttribute && el.getAttribute('role');
      const txt = (el.innerText || el.textContent || '').trim();
      const aria = el.getAttribute && el.getAttribute('aria-label');
      if ((tag === 'TAB-BUTTON' || role === 'tab') && (txt === 'Releases' || aria === 'Releases')) {
        el.scrollIntoView({ block: 'center' });
        el.click();
        return;
      }
    }
  });
  await sleep(6000);
  await dump(page, 'releases-tab');

  // Find draft edit URL
  const editUrls = await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    const urls = new Set();
    for (const el of walk(document)) {
      const h = el.getAttribute && el.getAttribute('href');
      if (h && /\/tracks\/production\/releases\/[\w-]+/.test(h)) urls.add(h);
    }
    return Array.from(urls);
  });
  console.log(`  release hrefs: ${JSON.stringify(editUrls)}`);

  let editUrl = editUrls.find((h) => h.endsWith('/edit')) || editUrls[0];
  if (editUrl) {
    if (!editUrl.endsWith('/edit')) editUrl += '/edit';
    if (!editUrl.startsWith('http')) editUrl = `https://play.google.com${editUrl}`;
    console.log(`→ goto ${editUrl}`);
    await page.goto(editUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await sleep(8000);
  } else {
    console.log('→ no edit url found, trying text-click "Edit release"');
    await shadowClickByText(page, 'Edit release', { afterMs: 8000 });
  }
  await dump(page, 'draft-edit');

  // ── STEP 3: Add bundle from library ────────────────────────────────────
  console.log('\n=== STEP 3: add v1.1.3 bundle from library ===');
  const alreadyAttached = await page.evaluate(() =>
    /(\(10\)|version code 10|1\.1\.3 \(10\))/i.test(document.body.innerText || '')
  );
  console.log(`  already attached? ${alreadyAttached}`);

  if (!alreadyAttached) {
    await shadowClickByText(page, 'Add from library', { afterMs: 5000 });
    await dump(page, 'lib-modal');

    // pick the row with 1.1.3 (10)
    let picked = await shadowClickByText(page, '1.1.3 (10)', { exact: false, afterMs: 2500 });
    if (!picked) picked = await shadowClickByText(page, '1.1.3', { exact: false, afterMs: 2500 });
    if (!picked) picked = await shadowClickByText(page, '(10)', { exact: false, afterMs: 2500 });
    await dump(page, 'row-picked');

    for (const label of ['Add to release', 'Add', 'OK', 'Save changes']) {
      if (await shadowClickByText(page, label, { afterMs: 5000 })) break;
    }
    await dump(page, 'bundle-added');
  }

  // ── STEP 4: Fill release name + notes ──────────────────────────────────
  console.log('\n=== STEP 4: release name + notes ===');
  await page.evaluate((name) => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    let target = null;
    for (const el of walk(document)) {
      if (el.tagName !== 'INPUT') continue;
      if (el.type && el.type !== 'text') continue;
      const aria = el.getAttribute('aria-label') || '';
      if (/release name/i.test(aria)) { target = el; break; }
    }
    if (!target) {
      for (const el of walk(document)) {
        if (el.tagName === 'INPUT' && (!el.type || el.type === 'text')) {
          const r = el.getBoundingClientRect();
          if (r.width > 0 && r.height > 0) { target = el; break; }
        }
      }
    }
    if (!target) return false;
    target.scrollIntoView({ block: 'center' });
    target.focus();
    target.value = '';
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(target, name);
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, RELEASE_NAME);
  console.log(`  name set`);
  await dump(page, 'name-filled');

  await page.evaluate((notes) => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    let target = null;
    for (const el of walk(document)) {
      if (el.getAttribute && el.getAttribute('contenteditable') === 'true' && el.getAttribute('role') === 'textbox') {
        target = el; break;
      }
    }
    if (!target) {
      for (const el of walk(document)) {
        if (el.tagName === 'TEXTAREA') { target = el; break; }
      }
    }
    if (!target) return false;
    target.scrollIntoView({ block: 'center' });
    target.focus();
    if (target.tagName === 'TEXTAREA') {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
      setter.call(target, notes);
    } else {
      target.innerText = notes;
    }
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, RELEASE_NOTES);
  console.log('  notes set');
  await page.keyboard.press('Tab');
  await sleep(2000);
  await dump(page, 'notes-filled');

  // ── STEP 5: Next → Save → Review → Rollout ────────────────────────────
  console.log('\n=== STEP 5: progress to rollout ===');
  await shadowClickByText(page, 'Next', { afterMs: 4500 });
  await dump(page, 'after-next');

  await shadowClickByText(page, 'Save', { afterMs: 4500 });
  await dump(page, 'after-save');

  for (const lbl of ['Review release', 'Review']) {
    if (await shadowClickByText(page, lbl, { afterMs: 6000 })) break;
  }
  await dump(page, 'after-review');

  let rolled = false;
  for (const lbl of ['Start rollout to Production', 'Start rollout']) {
    rolled = await shadowClickByText(page, lbl, { afterMs: 5000 });
    if (rolled) break;
  }
  await dump(page, 'after-rollout-click');

  // Confirm
  for (const lbl of ['Rollout', 'Confirm', 'OK']) {
    if (await shadowClickByText(page, lbl, { afterMs: 5000 })) break;
  }
  await dump(page, 'after-rollout-confirm');

  // ── STEP 6: Publishing → Send for review ──────────────────────────────
  console.log('\n=== STEP 6: send for review ===');
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await dump(page, 'publishing-final');

  const bodyText = await page.evaluate(() => document.body.innerText || '');
  const has113 = /1\.1\.3|\(10\)/.test(bodyText);
  const has103 = /1\.0\.3|\(4\)/.test(bodyText);
  console.log(`  has v1.1.3 pending? ${has113}`);
  console.log(`  has v1.0.3 pending? ${has103}`);
  if (has103 && !has113) {
    console.error('❌ v1.1.3 NOT in pending queue. Stopping.');
    console.log('\nLeaving browser open for manual completion.');
    process.exit(0);
  }

  let sent = false;
  for (const lbl of ['Send 1 change for review', 'Send 2 changes for review', 'Send 3 changes for review', 'Send for review']) {
    sent = await shadowClickByText(page, lbl, { exact: false, afterMs: 5000 });
    if (sent) break;
  }
  await dump(page, 'send-clicked');

  for (const lbl of ['Send for review', 'Send', 'Confirm']) {
    if (await shadowClickByText(page, lbl, { afterMs: 5000 })) break;
  }
  await dump(page, 'send-confirmed');

  console.log(`\n✅ DONE — v1.1.3 submission attempted end-to-end.`);
  console.log(`   Output: ${OUT}`);
  console.log(`   Chrome stays open (CDP). You can verify in the browser.`);

  // Detach but leave Chrome alive
  await browser.close();
  process.exit(0);
})().catch(async (e) => {
  console.error('❌', e.stack || e.message);
  process.exit(1);
});
