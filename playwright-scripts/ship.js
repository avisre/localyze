/**
 * ship.js — finish the v1.1.3 Production submission end-to-end.
 *
 * Strategy:
 *   1. Launch persistent Chrome. If signed out, wait up to 5 min for user
 *      to sign in (URL leaves accounts.google.com → play.google.com).
 *   2. Publishing overview: discard or "Save for later" the old rejected
 *      v1.0.3 pending change so we don't re-submit it.
 *   3. Production track Releases tab: find the v1.1.3 draft → open it.
 *   4. Add bundle from library (the v1.1.3 versionCode 10 .aab) → fill
 *      release name "1.1.3" + en-US notes.
 *   5. Next → Save → Review release → Start rollout → Confirm.
 *   6. Publishing overview → Send N changes for review → Confirm.
 *
 * After every navigation/click, dump page state to disk so we can read
 * back what happened and recover from mismatched selectors.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROFILE = path.join(os.homedir(), '.cache', 'play-console-profile');
const PINNED = path.join(os.homedir(), '.cache', 'ms-playwright', 'chromium-1217', 'chrome-linux64', 'chrome');
const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';
const RELEASE_NAME = '1.1.3';
const RELEASE_NOTES = `<en-US>
Major update — Gemma 3n E2B is now the on-device model.
1.4–1.8x faster end-to-end responses and roughly half the RAM
compared with the previous build. Model picker removed: one model,
auto-selected for every device. Thinking mode now defaults to OFF.
</en-US>`;

const PROD_URL  = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
const PUB_URL   = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `ship-${TS}`);
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

async function dumpState(page, label) {
  await shot(page, label);
  const els = await snapshotElements(page);
  fs.writeFileSync(path.join(OUT, `${stepN}-${label}.json`), JSON.stringify({ url: page.url(), els }, null, 2));
}

/** Click an element in shadow-pierced DOM whose visible text exactly equals `text`. */
async function shadowClickByText(page, text, opts = {}) {
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
      const matchTxt = exact ? txt === text : (txt.includes(text) || aria.includes(text));
      if (!matchTxt) continue;
      const tag = el.tagName;
      const role = el.getAttribute('role');
      const isInteractive = ['BUTTON', 'A'].includes(tag) ||
                            ['button', 'menuitem', 'option', 'tab', 'link'].includes(role) ||
                            el.onclick !== null;
      if (!isInteractive) continue;
      candidates.push(el);
    }
    if (!candidates.length) return null;
    // Prefer the smallest matching element (most specific)
    candidates.sort((a, b) => {
      const ra = a.getBoundingClientRect();
      const rb = b.getBoundingClientRect();
      return (ra.width * ra.height) - (rb.width * rb.height);
    });
    const el = candidates[0];
    el.scrollIntoView({ block: 'center', behavior: 'instant' });
    el.click();
    return { tag: el.tagName, text: (el.innerText || '').slice(0, 80), aria: el.getAttribute('aria-label') };
  }, { text, exact: opts.exact !== false });
  if (result) {
    console.log(`✓ click "${text}" → ${result.tag} "${result.text}"`);
  } else {
    console.log(`✗ click "${text}" → not found`);
  }
  await sleep(opts.afterMs || 3500);
  return !!result;
}

/** Click an element matching aria-label regex */
async function shadowClickAria(page, regex, opts = {}) {
  const reSrc = regex.source;
  const result = await page.evaluate((reSrc) => {
    const re = new RegExp(reSrc);
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) {
        yield el;
        if (el.shadowRoot) yield* walk(el.shadowRoot);
      }
    }
    for (const el of walk(document)) {
      const aria = el.getAttribute('aria-label') || '';
      if (!re.test(aria)) continue;
      el.scrollIntoView({ block: 'center', behavior: 'instant' });
      el.click();
      return { tag: el.tagName, aria };
    }
    return null;
  }, reSrc);
  if (result) console.log(`✓ click aria=${regex} → ${result.tag} "${result.aria}"`);
  else console.log(`✗ click aria=${regex} → not found`);
  await sleep(opts.afterMs || 3500);
  return !!result;
}

(async () => {
  const opts = { headless: false, viewport: { width: 1400, height: 900 } };
  if (fs.existsSync(PINNED)) opts.executablePath = PINNED;
  const ctx = await chromium.launchPersistentContext(PROFILE, opts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  console.log(`\n→ goto ${PROD_URL}`);
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(5000);

  // Wait for signed-in state (URL stays on play.google.com/console).
  // NO TIMEOUT — keep waiting until the user signs in, since 2FA can take
  // a while. Also DO NOT close the browser on failure.
  console.log('\n→ waiting for signed-in state (no timeout — DO NOT CLOSE this Chrome window)');
  console.log('  In the Chrome window: click your Google account, enter password, complete 2FA.');
  console.log('  Once Play Console loads, this script auto-proceeds.');
  let signedIn = false;
  let lastUrl = '';
  while (!signedIn) {
    let url;
    try { url = page.url(); } catch (e) {
      console.log(`  page error: ${e.message.slice(0, 100)} — opening new page`);
      try {
        const pages = ctx.pages();
        if (pages.length) {
          // reattach to current page if old one was closed
        } else {
          await ctx.newPage();
        }
        url = ctx.pages()[0].url();
      } catch { url = ''; }
    }
    if (url !== lastUrl) { console.log(`  url=${url.slice(0, 100)}`); lastUrl = url; }
    if (url.startsWith('https://play.google.com/console/') && !url.includes('accounts.google.com')) {
      try {
        const ok = await page.evaluate(() => /Publishing overview|Production/i.test(document.body.innerText || ''));
        if (ok) { signedIn = true; break; }
      } catch {}
    }
    await sleep(3000);
  }
  console.log('✓ signed in');
  await dumpState(page, 'signed-in');

  // ───────────────────────────────────────────────────────────────────────
  // 1. Discard the old rejected v1.0.3 pending change
  // ───────────────────────────────────────────────────────────────────────
  console.log(`\n=== STEP 1: handle pending v1.0.3 change ===`);
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await dumpState(page, 'publishing-initial');

  // Click first "more_vert" button (the v1.0.3 change menu).
  // From inspect.json we know aria-label="More actions" on both more_vert buttons.
  console.log('→ open first change-card menu (v1.0.3)');
  const firstMenu = await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) {
        yield el;
        if (el.shadowRoot) yield* walk(el.shadowRoot);
      }
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
  console.log(`  result: ${JSON.stringify(firstMenu)}`);
  await sleep(2500);
  await dumpState(page, 'first-menu-open');

  // The menu should now have a "Discard" or "Remove" option. Try those texts.
  console.log('→ click "Discard" in menu');
  let discarded = await shadowClickByText(page, 'Discard', { afterMs: 2500 });
  if (!discarded) discarded = await shadowClickByText(page, 'Remove', { afterMs: 2500 });
  if (!discarded) {
    // dump current menu items
    console.log('  menu options visible:');
    const opts = await page.evaluate(() => {
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
    console.log(`  items: ${JSON.stringify(opts)}`);
  }
  await dumpState(page, 'after-discard');

  // Confirm dialog (if any)
  await shadowClickByText(page, 'Discard', { afterMs: 3500 });
  await shadowClickByText(page, 'Confirm', { afterMs: 3500 });
  await dumpState(page, 'after-discard-confirm');

  // ───────────────────────────────────────────────────────────────────────
  // 2. Production track → Releases tab → find draft
  // ───────────────────────────────────────────────────────────────────────
  console.log(`\n=== STEP 2: open Production track Releases tab ===`);
  await page.goto(PROD_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await dumpState(page, 'production-track');

  // Click "Releases" tab
  console.log('→ click "Releases" tab');
  const tabClicked = await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    for (const el of walk(document)) {
      if (el.tagName !== 'TAB-BUTTON' && el.getAttribute('role') !== 'tab') continue;
      const txt = (el.innerText || el.textContent || '').trim();
      const aria = el.getAttribute('aria-label') || '';
      if (txt === 'Releases' || aria === 'Releases') {
        el.scrollIntoView({ block: 'center' });
        el.click();
        return { tag: el.tagName, aria };
      }
    }
    return null;
  });
  console.log(`  result: ${JSON.stringify(tabClicked)}`);
  await sleep(6000);
  await dumpState(page, 'releases-tab');

  // Find the draft edit link. On the Releases tab, the draft row typically
  // has an "Edit release" link/button. Find any anchor pointing to a
  // specific release id under /tracks/production/releases/...
  console.log('→ scrape draft edit href');
  const draftHref = await page.evaluate(() => {
    function* walk(root) {
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); }
    }
    const hrefs = new Set();
    for (const el of walk(document)) {
      const h = el.getAttribute && el.getAttribute('href');
      if (!h) continue;
      const m = h.match(/\/tracks\/production\/releases\/([\w-]+)(\/edit)?$/);
      if (m) hrefs.add(h);
    }
    return Array.from(hrefs);
  });
  console.log(`  found hrefs: ${JSON.stringify(draftHref)}`);

  let editUrl = null;
  if (draftHref.length) {
    // Prefer one that ends with /edit
    const withEdit = draftHref.find((h) => h.endsWith('/edit'));
    editUrl = withEdit || draftHref[0];
    if (!editUrl.endsWith('/edit')) editUrl += '/edit';
    if (!editUrl.startsWith('http')) editUrl = `https://play.google.com${editUrl}`;
  }

  if (!editUrl) {
    // Fallback: click "Edit release" text directly
    console.log('→ no edit href found, trying "Edit release" click');
    const ok = await shadowClickByText(page, 'Edit release', { afterMs: 6000 });
    if (!ok) {
      console.error('❌ could not find draft edit entry');
      await dumpState(page, 'draft-not-found');
      await new Promise(() => {});
    }
  } else {
    console.log(`→ goto draft edit: ${editUrl}`);
    await page.goto(editUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await sleep(8000);
  }
  await dumpState(page, 'draft-edit-form');

  // ───────────────────────────────────────────────────────────────────────
  // 3. Add bundle from library
  // ───────────────────────────────────────────────────────────────────────
  console.log(`\n=== STEP 3: add v1.1.3 bundle from library ===`);
  // Check if a bundle is already attached
  const alreadyAttached = await page.evaluate(() =>
    /App bundles[\s\S]*?1\.1\.3|versionCode\s*10|\(10\)/.test(document.body.innerText || '')
  );
  console.log(`  bundle already attached? ${alreadyAttached}`);

  if (!alreadyAttached) {
    console.log('→ click "Add from library"');
    await shadowClickByText(page, 'Add from library', { afterMs: 5000 });
    await dumpState(page, 'lib-modal');

    // The modal shows rows for bundles. Pick the one with "1.1.3" or
    // version code "10".
    console.log('→ pick v1.1.3 row');
    let picked = await shadowClickByText(page, '1.1.3', { exact: false, afterMs: 2500 });
    if (!picked) picked = await shadowClickByText(page, '(10)', { exact: false, afterMs: 2500 });
    await dumpState(page, 'lib-row-picked');

    console.log('→ confirm "Add to release"');
    let added = await shadowClickByText(page, 'Add to release', { afterMs: 5000 });
    if (!added) added = await shadowClickByText(page, 'Add', { afterMs: 5000 });
    await dumpState(page, 'bundle-added');
  }

  // ───────────────────────────────────────────────────────────────────────
  // 4. Fill release name + notes
  // ───────────────────────────────────────────────────────────────────────
  console.log(`\n=== STEP 4: release name + notes ===`);
  console.log('→ release name');
  try {
    // Find a visible text input that's labeled "Release name" or the first
    // text input in the form
    const filled = await page.evaluate((name) => {
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
        const label = (el.labels && el.labels[0] && el.labels[0].innerText) || '';
        if (/release name/i.test(aria) || /release name/i.test(label)) { target = el; break; }
      }
      if (!target) {
        // first visible text input
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
      target.select && target.select();
      target.value = '';
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(target, name);
      target.dispatchEvent(new Event('input', { bubbles: true }));
      target.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }, RELEASE_NAME);
    console.log(`  filled: ${filled}`);
  } catch (e) { console.log(`  err: ${e.message.slice(0, 80)}`); }
  await dumpState(page, 'name-filled');

  console.log('→ release notes');
  // Play Console wraps notes in a contenteditable inside gmpx-release-notes-editor
  try {
    const filled = await page.evaluate((notes) => {
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
    console.log(`  filled: ${filled}`);
  } catch (e) { console.log(`  err: ${e.message.slice(0, 80)}`); }
  await page.keyboard.press('Tab');
  await sleep(2000);
  await dumpState(page, 'notes-filled');

  // ───────────────────────────────────────────────────────────────────────
  // 5. Next → Save → Review → Rollout
  // ───────────────────────────────────────────────────────────────────────
  console.log(`\n=== STEP 5: progress to rollout ===`);
  await shadowClickByText(page, 'Next', { afterMs: 4500 });
  await dumpState(page, 'after-next');

  await shadowClickByText(page, 'Save', { afterMs: 4500 });
  await dumpState(page, 'after-save');

  let reviewed = await shadowClickByText(page, 'Review release', { afterMs: 6000 });
  if (!reviewed) reviewed = await shadowClickByText(page, 'Review', { afterMs: 6000 });
  await dumpState(page, 'after-review');

  let rolled = await shadowClickByText(page, 'Start rollout to Production', { afterMs: 5000 });
  if (!rolled) rolled = await shadowClickByText(page, 'Start rollout', { afterMs: 5000 });
  await dumpState(page, 'after-rollout-click');

  // Confirm dialog
  await shadowClickByText(page, 'Rollout', { afterMs: 5000 });
  await shadowClickByText(page, 'Confirm', { afterMs: 5000 });
  await dumpState(page, 'after-rollout-confirm');

  // ───────────────────────────────────────────────────────────────────────
  // 6. Publishing overview → Send for review
  // ───────────────────────────────────────────────────────────────────────
  console.log(`\n=== STEP 6: Publishing overview send for review ===`);
  await page.goto(PUB_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await dumpState(page, 'publishing-final');

  // Check that v1.1.3 / (10) is now in the pending list, NOT v1.0.3 / (4)
  const bodyText = await page.evaluate(() => document.body.innerText || '');
  const has113 = /1\.1\.3|\(10\)/.test(bodyText);
  const has103 = /1\.0\.3|\(4\)/.test(bodyText);
  console.log(`  has v1.1.3 pending? ${has113}`);
  console.log(`  has v1.0.3 pending? ${has103}`);

  if (has103 && !has113) {
    console.error('❌ v1.1.3 NOT in pending queue. Aborting send.');
    await new Promise(() => {});
  }

  let sent = await shadowClickByText(page, 'Send 1 change for review', { exact: false, afterMs: 5000 });
  if (!sent) sent = await shadowClickByText(page, 'Send 2 changes for review', { exact: false, afterMs: 5000 });
  if (!sent) sent = await shadowClickByText(page, 'Send for review', { exact: false, afterMs: 5000 });
  await dumpState(page, 'send-clicked');

  await shadowClickByText(page, 'Send for review', { afterMs: 5000 });
  await shadowClickByText(page, 'Send', { afterMs: 5000 });
  await shadowClickByText(page, 'Confirm', { afterMs: 5000 });
  await dumpState(page, 'send-confirmed');

  console.log(`\n✅ DONE — v1.1.3 submission attempted end-to-end.`);
  console.log(`   Screenshots/dumps: ${OUT}`);
  console.log(`   Keeping browser open for verification.`);
  await new Promise(() => {});
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
