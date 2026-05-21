// Generates og-image.png (1200x630) and favicons from the Localyze.ai
// logo. Composes the card as SVG, then rasterises with sharp.
//
// Run from web/assets/img/:
//   node make-og.mjs

import sharp from "sharp";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const LOGO = path.join(HERE, "logo-mark.png");
const OG_OUT = path.join(HERE, "og-image.png");
const FAV32 = path.join(HERE, "favicon-32.png");
const FAV192 = path.join(HERE, "favicon-192.png");

const W = 1200;
const H = 630;

const logoBuf = await sharp(LOGO).resize(220, 220).png().toBuffer();

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
  <defs>
    <radialGradient id="wash" cx="80%" cy="-10%" r="55%">
      <stop offset="0%" stop-color="#36D431" stop-opacity="0.18"/>
      <stop offset="100%" stop-color="#36D431" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="100%" height="100%" fill="#F5F5F7"/>
  <rect width="100%" height="100%" fill="url(#wash)"/>

  <!-- eyebrow pill -->
  <rect x="340" y="160" width="200" height="40" rx="20" fill="#E7F8EC"/>
  <text x="440" y="186" text-anchor="middle"
        font-family="-apple-system,'Segoe UI',Roboto,sans-serif"
        font-size="20" font-weight="700" fill="#1FAF45"
        letter-spacing="2">ON-DEVICE AI</text>

  <!-- headline -->
  <text x="340" y="288"
        font-family="-apple-system,'Segoe UI',Roboto,sans-serif"
        font-size="80" font-weight="800" fill="#1D1D1F">Localyze.ai</text>

  <!-- subhead, two lines -->
  <text x="340" y="362"
        font-family="-apple-system,'Segoe UI',Roboto,sans-serif"
        font-size="32" font-weight="400" fill="#1D1D1F">Private AI that runs entirely on your</text>
  <text x="340" y="404"
        font-family="-apple-system,'Segoe UI',Roboto,sans-serif"
        font-size="32" font-weight="400" fill="#1D1D1F">device. No cloud. No tracking.</text>

  <!-- platforms line -->
  <text x="340" y="478"
        font-family="-apple-system,'Segoe UI',Roboto,sans-serif"
        font-size="22" font-weight="700" fill="#34C759"
        letter-spacing="1">Android  ·  Windows  ·  macOS  ·  Linux</text>

  <!-- hairline + url -->
  <line x1="80" y1="550" x2="${W - 80}" y2="550" stroke="#D2D2D7" stroke-width="1"/>
  <text x="80" y="588"
        font-family="-apple-system,'Segoe UI',Roboto,sans-serif"
        font-size="22" font-weight="600" fill="#6E6E73">localyze.ai</text>
</svg>`;

await sharp(Buffer.from(svg))
  .composite([{ input: logoBuf, top: (H - 220) / 2 - 40, left: 80 }])
  .png({ compressionLevel: 9, palette: false })
  .toFile(OG_OUT);
console.log("Wrote", OG_OUT);

await sharp(LOGO).resize(32, 32).png().toFile(FAV32);
console.log("Wrote", FAV32);
await sharp(LOGO).resize(192, 192).png().toFile(FAV192);
console.log("Wrote", FAV192);
