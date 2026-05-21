/**
 * inspect.js — discovery tool. Opens the Production track, then the
 * Publishing overview, dumps actionable elements (buttons, links, inputs)
 * with their text + selectors, plus screenshots. We READ the output and
 * decide the exact actions to take next.
 *
 * Usage: node playwright-scripts/inspect.js [extra-url]
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
const OUT = path.resolve(__dirname, '..', 'output', 'playwright', `inspect-${TS}`);
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function dump(page, label) {
  const shotPath = path.join(OUT, `${label}.png`);
  try { await page.screenshot({ path: shotPath, fullPage: true }); } catch {}

  const data = await page.evaluate(() => {
    function walk(root, out, depth = 0) {
      if (depth > 6) return;
      if (!root) return;
      const all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (const el of all) {
        if (el.shadowRoot) walk(el.shadowRoot, out, depth + 1);
        const tag = el.tagName.toLowerCase();
        const role = el.getAttribute && el.getAttribute('role');
        const text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 120);
        const href = el.getAttribute && el.getAttribute('href');
        const type = el.getAttribute && el.getAttribute('type');
        const ariaLabel = el.getAttribute && el.getAttribute('aria-label');
        const isInteractive = ['button', 'a'].includes(tag) ||
                              ['button', 'menuitem', 'option', 'tab', 'link', 'textbox'].includes(role) ||
                              tag === 'input' || tag === 'textarea';
        if (!isInteractive) continue;
        // Skip very long elements (containers)
        if (text.length > 100 && tag !== 'a') continue;
        out.push({ tag, role, text, href, type, ariaLabel });
      }
    }
    const out = [];
    walk(document, out);
    return {
      url: location.href,
      title: document.title,
      bodyText: (document.body.innerText || '').slice(0, 2000),
      elements: out,
    };
  });

  const txtPath = path.join(OUT, `${label}.json`);
  fs.writeFileSync(txtPath, JSON.stringify(data, null, 2));
  console.log(`\n=== ${label} ===`);
  console.log(`URL: ${data.url}`);
  console.log(`Title: ${data.title}`);
  console.log(`Elements: ${data.elements.length}`);
  console.log(`Body text (first 1500 chars):\n${data.bodyText.slice(0, 1500)}`);
  console.log(`\nInteractive elements:`);
  for (const el of data.elements.slice(0, 80)) {
    if (!el.text && !el.href && !el.ariaLabel) continue;
    const parts = [`${el.tag}${el.role ? `[${el.role}]` : ''}`];
    if (el.text) parts.push(`"${el.text}"`);
    if (el.ariaLabel) parts.push(`aria="${el.ariaLabel.slice(0, 60)}"`);
    if (el.href) parts.push(`href=${el.href.slice(0, 100)}`);
    if (el.type) parts.push(`type=${el.type}`);
    console.log(`  ${parts.join(' ')}`);
  }
  console.log('');
}

(async () => {
  const opts = { headless: false, viewport: { width: 1400, height: 900 } };
  if (fs.existsSync(PINNED)) opts.executablePath = PINNED;
  const ctx = await chromium.launchPersistentContext(PROFILE, opts);
  const page = ctx.pages()[0] || (await ctx.newPage());

  const urls = [
    `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/production`,
    `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/publishing`,
    `https://play.google.com/console/u/0/developers/${DEVELOPER_ID}/app/${APP_ID}/tracks/latest-releases-and-bundles`,
  ];

  for (let i = 0; i < urls.length; i++) {
    const u = urls[i];
    console.log(`\n→ goto ${u}`);
    await page.goto(u, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await sleep(8000);
    const label = ['production-track', 'publishing', 'latest-releases'][i];
    await dump(page, `${String(i + 1).padStart(2, '0')}-${label}`);
  }

  console.log(`\n✅ done — output in ${OUT}`);
  console.log(`   keeping browser open. ctrl-c to exit.`);
  await new Promise(() => {});
})().catch((e) => { console.error('❌', e.stack || e.message); process.exit(1); });
