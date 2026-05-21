const { chromium } = require('playwright');
(async () => {
  const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const c = b.contexts()[0];
  let page = null;
  for (const p of c.pages()) {
    if (p.url().includes('/releases/2/prepare')) { page = p; break; }
  }
  if (!page) page = c.pages().find(p => p.url().includes('/console/'));
  await page.bringToFront();
  await page.goto('https://play.google.com/console/u/0/developers/6492542126543167241/app/4974212910605033903/tracks/4697325931803269676/releases/2/prepare', { waitUntil:'domcontentloaded' });
  await new Promise(r=>setTimeout(r,7000));

  // Find all "Add from library" labelled buttons + their context
  const data = await page.evaluate(() => {
    function* walk(root) { if (!root) return; for (const el of root.querySelectorAll('*')) { yield el; if (el.shadowRoot) yield* walk(el.shadowRoot); } }
    const found = [];
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (t.includes('Add from library') && t.length < 80 && (el.tagName === 'BUTTON' || el.tagName === 'A')) {
        const r = el.getBoundingClientRect();
        // find ancestor section heading
        let p = el, heading = null;
        for (let i = 0; i < 12 && p; i++) {
          const sib = p.previousElementSibling;
          if (sib) {
            const sht = (sib.innerText || '').trim().slice(0, 50);
            if (sht && sht.length < 60) { heading = sht; break; }
          }
          p = p.parentElement;
        }
        found.push({ text: t.slice(0,60), rect: {x:Math.round(r.x), y:Math.round(r.y), w:Math.round(r.width), h:Math.round(r.height)}, heading });
      }
    }
    // Also find more_vert buttons in rows containing "1.1.3"
    const removeOptions = [];
    for (const el of walk(document)) {
      const t = (el.innerText || '').trim();
      if (/10\s*\(1\.1\.3\)/.test(t) && t.length < 200) {
        // walk siblings/parents for more_vert
        let p = el;
        for (let i = 0; i < 6 && p; i++) {
          if (p.querySelectorAll) {
            const mvs = p.querySelectorAll('button[aria-label="More actions"], button[aria-label*="more"], material-icon[aria-label*="More"]');
            for (const mv of mvs) {
              const r = mv.getBoundingClientRect();
              removeOptions.push({ aria: mv.getAttribute('aria-label'), rect:{x:Math.round(r.x), y:Math.round(r.y)}, parentText: t.slice(0, 80) });
            }
            if (mvs.length) break;
          }
          p = p.parentElement;
        }
        break;
      }
    }
    return { addFromLibrary: found, removeOptions };
  });
  console.log('=== "Add from library" buttons ===');
  for (const f of data.addFromLibrary) console.log(`  ${f.heading ? '['+f.heading+'] ' : ''}@${f.rect.x},${f.rect.y} size=${f.rect.w}x${f.rect.h}: "${f.text}"`);
  console.log('\n=== more_vert near v1.1.3 row ===');
  for (const m of data.removeOptions) console.log(`  ${m.aria} @${m.rect.x},${m.rect.y} (parent: "${m.parentText}")`);
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
