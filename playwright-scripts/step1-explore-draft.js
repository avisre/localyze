/**
 * step1-explore-draft.js — Navigate Production → Releases tab → find the
 * v1.1.3 draft's edit URL and the v1.0.3 active release URL. Dump everything.
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROFILE = path.join(os.homedir(), '.cache', 'play-console-profile');
const PINNED = path.join(os.homedir(), '.cache', 'ms-playwright', 'chromium-1217', 'chrome-linux64', 'chrome');
const DEVELOPER_ID = '6492542126543167241';
const APP_ID = '4974212910605033903';

const TS = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `step1-${TS}`);
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function dump(page, label) {
  const shotPath = path.join(OUT, `${label}.png`);
  try { await page.screenshot({ path: shotPath, fullPage: true }); } catch {}
  const data = await page.evaluate(() => {
    function walk(root, out, depth = 0) {
      if (depth > 6 || !root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) {
        if (el.shadowRoot) walk(el.shadowRoot, out, depth + 1);
        const tag = el.tagName.toLowerCase();
        const role = el.getAttribute && el.getAttribute('role');
        const text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 140);
        const href = el.getAttribute && el.getAttribute('href');
        const ariaLabel = el.getAttribute && el.getAttribute('aria-label');
        const isInteractive = ['button', 'a'].includes(tag) ||
                              ['button', 'menuitem', 'option', 'tab', 'link', 'textbox'].includes(role) ||
                              tag === 'input' || tag === 'textarea';
        if (!isInteractive) continue;
        if (text.length > 110 && tag !== 'a') continue;
        out.push({ tag, role, text, href, ariaLabel });
      }
    }
    const out = [];
    walk(document, out);
    return {
      url: location.href,
      title: document.title,
      bodyText: (document.body.innerText || '').slice(0, 3000),
      elements: out,
    };
  });
  fs.writeFileSync(path.join(OUT, `${label}.json`), JSON.stringify(data, null, 2));
  console.log(`\n=== ${label} === ${data.url}`);
  console.log(`Body excerpt:\n${data.bodyText.slice(0, 2000)}`);
  console.log(`\nInteractive (excluding nav):`);
  // Filter out the always-present nav rail items
  const navHrefRe = /\/(app-dashboard|statistics|publishing|protect-with-play|test-and-release|monitor|grow-overview|monetize|pre-launch-report|releases\/internal-app-sharing|pre-registration|advanced-distribution|devices|user-feedback|vitals|policy-center|app-content|teacher-approved|store-listings|store-listing-experiments|store-settings|reporting|app-translations|app-translation-embed|deeplinks|paid-app|one-time-products|subscriptions|monetization-studio|price-experiments|promotions|monetization-setup|app-list)/;
  for (const el of data.elements) {
    if (el.href && navHrefRe.test(el.href)) continue;
    if (!el.text && !el.href && !el.ariaLabel) continue;
    const parts = [`${el.tag}${el.role ? `[${el.role}]` : ''}`];
    if (el.text) parts.push(`"${el.text}"`);
    if (el.ariaLabel) parts.push(`aria="${el.ariaLabel.slice(0, 70)}"`);
    if (el.href) parts.push(`href=${el.href.slice(0, 120)}`);
    console.log(`  ${parts.join(' ')}`);
  }
}

(async () => {
  const opts = { headless: false, viewport: { width: 1400, height: 900 } };
  if (fs.existsSync(PINNED)) opts.executablePath = PINNED;
  const ctx = await chromium.launchPersistentContext(PROFILE, opts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  const prodUrl = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`;
  console.log(`→ ${prodUrl}`);
  await page.goto(prodUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(8000);
  await dump(page, '01-production');

  // Click "Releases" tab — use role=tab with name
  console.log('\n→ click Releases tab');
  const clicked = await page.evaluate(() => {
    const tabs = document.querySelectorAll('[role="tab"], tab-button');
    for (const t of tabs) {
      const txt = (t.innerText || t.textContent || '').trim();
      const aria = t.getAttribute('aria-label');
      if (txt === 'Releases' || aria === 'Releases') {
        t.scrollIntoView({ block: 'center' });
        t.click();
        return { tag: t.tagName, text: txt, aria };
      }
    }
    return null;
  });
  console.log(`  clicked: ${JSON.stringify(clicked)}`);
  await sleep(6000);
  await dump(page, '02-releases-tab');

  // Also dump publishing page
  const pubUrl = `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`;
  console.log(`\n→ ${pubUrl}`);
  await page.goto(pubUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await sleep(7000);
  await dump(page, '03-publishing');

  // Click more_vert on the FIRST change card (v1.0.3) to see what options exist
  console.log('\n→ click first more_vert (v1.0.3 change menu)');
  const menuClicked = await page.evaluate(() => {
    // find buttons with text "more_vert" inside the changes section
    const btns = Array.from(document.querySelectorAll('button')).filter(b => {
      const t = (b.innerText || b.textContent || '').trim();
      return t === 'more_vert' || (b.getAttribute('aria-label') || '').includes('More actions');
    });
    if (!btns.length) return null;
    btns[0].scrollIntoView({ block: 'center' });
    btns[0].click();
    return { count: btns.length, ariaLabel: btns[0].getAttribute('aria-label') };
  });
  console.log(`  clicked: ${JSON.stringify(menuClicked)}`);
  await sleep(2500);
  await dump(page, '04-more-vert-menu');

  console.log(`\n✅ done — output in ${OUT}`);
  console.log(`   keeping browser open. ctrl-c to exit.`);
  await new Promise(() => {});
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
