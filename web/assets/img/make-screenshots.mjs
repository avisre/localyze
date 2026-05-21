// Generates the 5 marketing screenshot mockups as PNG from SVG.
// Minimalist aesthetic: lots of whitespace, single accent, no clutter.
//
// Run from web/assets/img/:
//   node make-screenshots.mjs

import sharp from "sharp";
import path from "node:path";
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const LOGO = path.join(HERE, "logo-mark.png");
const OUT = path.join(HERE, "screenshots");
await fs.mkdir(OUT, { recursive: true });

// Design tokens (mirror the app)
const T = {
  bg: "#F5F5F7",
  surface: "#FFFFFF",
  hairline: "#D2D2D7",
  ink: "#1D1D1F",
  muted: "#6E6E73",
  primary: "#34C759",
  soft: "#E7F8EC",
  font: "-apple-system,'Segoe UI',Roboto,sans-serif",
};

// ---- shared helpers ----
const escape = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const text = (x, y, str, opts = {}) => {
  const { size = 28, weight = 400, fill = T.ink, anchor = "start", letter = 0 } = opts;
  return `<text x="${x}" y="${y}" font-family="${T.font}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}" letter-spacing="${letter}">${escape(str)}</text>`;
};

const rect = (x, y, w, h, opts = {}) => {
  const { fill = T.surface, stroke = "none", r = 0, sw = 1 } = opts;
  return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" ry="${r}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"/>`;
};

// ---- phone-chat ----
function phoneChat() {
  const W = 1080, H = 2160;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <rect width="100%" height="100%" fill="${T.bg}"/>
    <!-- status bar -->
    ${text(60, 70, "9:41", { size: 36, weight: 700 })}
    ${text(W - 60, 70, "5G ●", { size: 30, weight: 600, anchor: "end" })}
    <!-- app header -->
    ${rect(0, 110, W, 200, { fill: T.surface })}
    <rect x="60" y="160" width="100" height="100" rx="22" fill="${T.soft}"/>
    ${text(190, 220, "Localyze.ai", { size: 50, weight: 800 })}
    ${text(190, 270, "On-device · Gemma 4 E4B", { size: 26, fill: T.muted })}
    <circle cx="${W - 110}" cy="210" r="50" fill="${T.soft}"/>
    ${text(W - 110, 226, "+", { size: 56, weight: 600, fill: T.primary, anchor: "middle" })}
    <!-- offline pill -->
    <rect x="${W/2 - 220}" y="380" width="440" height="60" rx="30" fill="${T.soft}"/>
    <circle cx="${W/2 - 180}" cy="410" r="8" fill="${T.primary}"/>
    ${text(W/2 - 140, 422, "Offline · on-device", { size: 28, weight: 700, fill: T.primary })}
    <!-- chat bubbles -->
    <rect x="200" y="500" width="780" height="180" rx="36" fill="${T.primary}"/>
    ${text(240, 560, "Summarise the meeting notes I", { size: 32, weight: 500, fill: "#fff" })}
    ${text(240, 612, "shared — keep everything local.", { size: 32, weight: 500, fill: "#fff" })}

    <rect x="100" y="720" width="820" height="320" rx="36" fill="${T.surface}" stroke="${T.hairline}"/>
    ${text(140, 780, "Three takeaways:", { size: 32, weight: 600 })}
    ${text(140, 832, "1. Ship onboarding next week", { size: 30 })}
    ${text(140, 880, "2. Privacy copy needs review", { size: 30 })}
    ${text(140, 928, "3. Analytics stays opt-in", { size: 30 })}
    ${text(140, 1000, "None of this left your phone.", { size: 28, fill: T.muted })}

    <rect x="280" y="1080" width="700" height="140" rx="36" fill="${T.primary}"/>
    ${text(320, 1140, "Draft a short reply to Maya.", { size: 32, weight: 500, fill: "#fff" })}

    <!-- typing -->
    <rect x="100" y="1260" width="240" height="100" rx="36" fill="${T.surface}" stroke="${T.hairline}"/>
    <circle cx="160" cy="1310" r="8" fill="${T.muted}"/>
    <circle cx="200" cy="1310" r="8" fill="${T.muted}"/>
    <circle cx="240" cy="1310" r="8" fill="${T.muted}"/>

    <!-- input -->
    ${rect(60, H - 180, W - 120, 120, { r: 60, fill: T.surface, stroke: T.hairline })}
    ${text(110, H - 100, "Message Localyze.ai…", { size: 30, fill: T.muted })}
    <circle cx="${W - 130}" cy="${H - 120}" r="50" fill="${T.primary}"/>
    ${text(W - 130, H - 105, "↑", { size: 50, weight: 700, fill: "#fff", anchor: "middle" })}
    <!-- home indicator -->
    <rect x="${W/2 - 110}" y="${H - 30}" width="220" height="8" rx="4" fill="${T.ink}"/>
  </svg>`;
}

// ---- phone-library ----
function phoneLibrary() {
  const W = 1080, H = 2160;
  const items = [
    { title: "Project Atlas", preview: "Want me to draft the agenda?", date: "Today", pinned: true },
    { title: "Trip to Kyoto", preview: "Saved your itinerary.", date: "Mon" },
    { title: "Resume review", preview: "Tightened the summary.", date: "Mon" },
    { title: "Reading notes", preview: "Themes: trust, latency, calm.", date: "Sun" },
    { title: "German practice", preview: "You're getting faster.", date: "Sat" },
    { title: "Workout split", preview: "Easier on the knees.", date: "Fri" },
  ];
  let y = 540;
  const rows = items.map((it) => {
    const block = `
      <rect x="60" y="${y}" width="${W - 120}" height="200" rx="32" fill="${T.surface}" stroke="${T.hairline}"/>
      <circle cx="120" cy="${y + 100}" r="14" fill="${T.primary}"/>
      ${text(170, y + 92, it.title, { size: 36, weight: 700 })}
      ${text(170, y + 144, it.preview, { size: 28, fill: T.muted })}
      ${text(W - 100, y + 92, it.date, { size: 26, fill: T.muted, anchor: "end" })}
      ${it.pinned ? `<text x="${W - 100}" y="${y + 144}" font-family="${T.font}" font-size="32" fill="${T.primary}" text-anchor="end">📌</text>` : ""}
    `;
    y += 230;
    return block;
  }).join("\n");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <rect width="100%" height="100%" fill="${T.bg}"/>
    ${text(60, 70, "9:41", { size: 36, weight: 700 })}
    ${text(W - 60, 70, "5G ●", { size: 30, weight: 600, anchor: "end" })}
    ${text(60, 230, "Library", { size: 80, weight: 800 })}
    ${text(60, 290, "Saved conversations · on-device", { size: 28, fill: T.muted })}
    <rect x="60" y="360" width="${W - 120}" height="120" rx="60" fill="${T.surface}" stroke="${T.hairline}"/>
    ${text(110, 432, "🔍   Search conversations", { size: 30, fill: T.muted })}
    ${rows}

    <!-- bottom tab bar -->
    <rect x="0" y="${H - 200}" width="${W}" height="200" fill="${T.surface}"/>
    <line x1="0" y1="${H - 200}" x2="${W}" y2="${H - 200}" stroke="${T.hairline}"/>
    ${text(W * 0.166, H - 100, "Chat", { size: 30, fill: T.muted, anchor: "middle" })}
    ${text(W * 0.5, H - 100, "Library", { size: 30, weight: 700, fill: T.primary, anchor: "middle" })}
    ${text(W * 0.833, H - 100, "Settings", { size: 30, fill: T.muted, anchor: "middle" })}
    <rect x="${W/2 - 110}" y="${H - 30}" width="220" height="8" rx="4" fill="${T.ink}"/>
  </svg>`;
}

// ---- phone-privacy ----
function phonePrivacy() {
  const W = 1080, H = 2160;
  const toggle = (y, label, hint, on) => `
    <rect x="60" y="${y}" width="${W - 120}" height="180" rx="32" fill="${T.surface}" stroke="${T.hairline}"/>
    ${text(120, y + 80, label, { size: 36, weight: 700 })}
    ${text(120, y + 130, hint, { size: 26, fill: T.muted })}
    <rect x="${W - 220}" y="${y + 60}" width="120" height="60" rx="30" fill="${on ? T.primary : "#C7C7CC"}"/>
    <circle cx="${on ? W - 130 : W - 190}" cy="${y + 90}" r="26" fill="#fff"/>
  `;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <rect width="100%" height="100%" fill="${T.bg}"/>
    ${text(60, 70, "9:41", { size: 36, weight: 700 })}
    ${text(W - 60, 70, "5G ●", { size: 30, weight: 600, anchor: "end" })}
    ${text(60, 230, "Privacy", { size: 80, weight: 800 })}
    ${text(60, 290, "Everything stays on this device", { size: 28, fill: T.muted })}

    <!-- explainer card -->
    <rect x="60" y="360" width="${W - 120}" height="280" rx="36" fill="${T.soft}"/>
    <circle cx="170" cy="500" r="60" fill="${T.primary}"/>
    ${text(170, 525, "✓", { size: 70, weight: 700, fill: "#fff", anchor: "middle" })}
    ${text(280, 460, "Local-first by design", { size: 36, weight: 700 })}
    ${text(280, 510, "No chats, voice, or files are", { size: 28, fill: T.ink })}
    ${text(280, 550, "uploaded for the core assistant.", { size: 28, fill: T.ink })}

    ${toggle(700, "Memory", "Off — assistant has no recall", false)}
    ${toggle(910, "Web search", "Off — uses no internet", false)}
    ${toggle(1120, "Notifications", "Off — silent by default", false)}
    ${toggle(1330, "Encrypted backups", "On — stored where you pick", true)}

    <!-- delete button -->
    <rect x="60" y="1620" width="${W - 120}" height="140" rx="40" fill="${T.primary}"/>
    ${text(W/2, 1700, "Delete all data", { size: 38, weight: 700, fill: "#fff", anchor: "middle" })}
    ${text(W/2, 1810, "Irreversible. Wipes chats, memory, model.", { size: 26, fill: T.muted, anchor: "middle" })}

    <rect x="${W/2 - 110}" y="${H - 30}" width="220" height="8" rx="4" fill="${T.ink}"/>
  </svg>`;
}

// ---- desktop frame helper ----
function desktopWindow(title, body) {
  const W = 2560, H = 1600;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <rect width="100%" height="100%" fill="${T.bg}"/>
    <!-- window chrome -->
    <rect x="40" y="40" width="${W - 80}" height="${H - 80}" rx="32" fill="${T.surface}" stroke="${T.hairline}"/>
    <rect x="40" y="40" width="${W - 80}" height="80" rx="32" fill="${T.surface}"/>
    <line x1="40" y1="120" x2="${W - 40}" y2="120" stroke="${T.hairline}"/>
    <circle cx="80" cy="80" r="12" fill="#FF5F57"/>
    <circle cx="120" cy="80" r="12" fill="#FEBC2E"/>
    <circle cx="160" cy="80" r="12" fill="#28C840"/>
    ${text(W/2, 92, title, { size: 24, weight: 600, fill: T.muted, anchor: "middle" })}
    ${body}
  </svg>`;
}

// ---- desktop-chat ----
function desktopChat() {
  const W = 2560, H = 1600, sb = 480;
  const convoRow = (y, title, sub, active) => `
    <rect x="80" y="${y}" width="${sb - 80}" height="100" rx="16" fill="${active ? T.soft : "transparent"}"/>
    ${text(120, y + 50, title, { size: 28, weight: 700, fill: active ? T.primary : T.ink })}
    ${text(120, y + 82, sub, { size: 22, fill: T.muted })}
  `;
  const body = `
    <!-- sidebar -->
    <rect x="40" y="120" width="${sb}" height="${H - 160}" fill="#FAFAFA"/>
    ${text(120, 200, "Localyze.ai", { size: 34, weight: 800 })}
    ${text(120, 240, "On-device", { size: 22, fill: T.muted })}
    <rect x="80" y="280" width="${sb - 80}" height="80" rx="20" fill="${T.primary}"/>
    ${text(sb/2 + 20, 332, "+  New chat", { size: 26, weight: 700, fill: "#fff", anchor: "middle" })}
    ${convoRow(400, "Project Atlas", "Want me to draft the agenda…", true)}
    ${convoRow(520, "Trip to Kyoto", "Saved your itinerary.", false)}
    ${convoRow(640, "Resume review", "Tightened the summary.", false)}
    ${convoRow(760, "Reading notes", "Themes: trust, latency, calm.", false)}
    ${convoRow(880, "German practice", "You're getting faster.", false)}
    ${convoRow(1000, "Workout split", "Easier on the knees.", false)}

    <!-- tabs at bottom of sidebar -->
    <line x1="40" y1="${H - 200}" x2="${sb + 40}" y2="${H - 200}" stroke="${T.hairline}"/>
    ${text(120 + 40, H - 150, "Chat", { size: 24, weight: 700, fill: T.primary })}
    ${text(120 + 40, H - 110, "Library", { size: 24, fill: T.muted })}
    ${text(120 + 40, H - 70, "Settings", { size: 24, fill: T.muted })}

    <!-- main pane -->
    ${text(sb + 120, 200, "Project Atlas — kickoff", { size: 34, weight: 700 })}
    <rect x="${W - 540}" y="160" width="450" height="60" rx="30" fill="${T.soft}"/>
    <circle cx="${W - 510}" cy="190" r="8" fill="${T.primary}"/>
    ${text(W - 480, 200, "Model: Gemma 4 E4B (on-device)", { size: 22, weight: 700, fill: T.primary })}

    <!-- bubbles -->
    <rect x="${sb + 120}" y="320" width="900" height="240" rx="24" fill="#F5F5F7"/>
    ${text(sb + 160, 372, "Localyze.ai", { size: 22, weight: 700, fill: T.muted })}
    ${text(sb + 160, 422, "Summarising the notes you shared — none of this is being", { size: 26 })}
    ${text(sb + 160, 462, "sent anywhere. Three time-sensitive items:", { size: 26 })}
    ${text(sb + 180, 502, "• Onboarding flow ships next Tuesday", { size: 24 })}
    ${text(sb + 180, 538, "• Privacy copy review owed to Maya by Friday", { size: 24 })}

    <rect x="${W - 1120}" y="620" width="980" height="140" rx="24" fill="${T.primary}"/>
    ${text(W - 1080, 672, "You", { size: 22, weight: 700, fill: "rgba(255,255,255,0.85)" })}
    ${text(W - 1080, 720, "Yes — and draft a short reply to Maya.", { size: 26, fill: "#fff" })}

    <rect x="${sb + 120}" y="820" width="900" height="200" rx="24" fill="#F5F5F7"/>
    ${text(sb + 160, 872, "Localyze.ai", { size: 22, weight: 700, fill: T.muted })}
    ${text(sb + 160, 920, "Drafted. Tone is friendly and direct.", { size: 26 })}
    ${text(sb + 160, 960, "Want me to attach the action items or keep it inline?", { size: 26 })}

    <!-- input -->
    <rect x="${sb + 120}" y="${H - 280}" width="${W - sb - 240}" height="120" rx="60" fill="${T.surface}" stroke="${T.hairline}"/>
    ${text(sb + 180, H - 205, "Message Localyze.ai…", { size: 26, fill: T.muted })}
    <circle cx="${W - 200}" cy="${H - 220}" r="44" fill="${T.primary}"/>
    ${text(W - 200, H - 205, "↑", { size: 44, weight: 700, fill: "#fff", anchor: "middle" })}
    ${text(W/2, H - 100, "Press Enter to send. Nothing leaves your device.", { size: 22, fill: T.muted, anchor: "middle" })}
  `;
  return desktopWindow("Localyze.ai", body);
}

// ---- desktop-settings ----
function desktopSettings() {
  const W = 2560, H = 1600, sb = 480;
  const row = (y, label, value, danger=false) => `
    <line x1="${sb + 120}" y1="${y - 20}" x2="${W - 120}" y2="${y - 20}" stroke="${T.hairline}"/>
    ${text(sb + 160, y + 30, label, { size: 28, fill: danger ? "#FF3B30" : T.ink })}
    ${text(W - 160, y + 30, value, { size: 26, fill: T.muted, anchor: "end" })}
  `;
  const sectionTitle = (y, t) => text(sb + 160, y, t, { size: 22, weight: 800, fill: T.muted, letter: 1.5 });

  const body = `
    <!-- sidebar -->
    <rect x="40" y="120" width="${sb}" height="${H - 160}" fill="#FAFAFA"/>
    ${text(120, 200, "Localyze.ai", { size: 34, weight: 800 })}
    ${text(120, 240, "On-device", { size: 22, fill: T.muted })}
    <line x1="40" y1="${H - 200}" x2="${sb + 40}" y2="${H - 200}" stroke="${T.hairline}"/>
    ${text(120 + 40, H - 150, "Chat", { size: 24, fill: T.muted })}
    ${text(120 + 40, H - 110, "Library", { size: 24, fill: T.muted })}
    ${text(120 + 40, H - 70, "Settings", { size: 24, weight: 700, fill: T.primary })}

    <!-- main pane -->
    ${text(sb + 120, 200, "Settings", { size: 48, weight: 800 })}
    ${text(sb + 120, 248, "Everything below stays on this device.", { size: 26, fill: T.muted })}

    ${sectionTitle(360, "MODEL")}
    <rect x="${sb + 120}" y="390" width="${W - sb - 240}" height="240" rx="24" fill="${T.surface}" stroke="${T.hairline}"/>
    ${row(410, "Engine", "Gemma 4 E4B")}
    ${row(490, "Context window", "32k tokens")}
    ${row(570, "Precision", "INT4")}

    ${sectionTitle(720, "MEMORY")}
    <rect x="${sb + 120}" y="750" width="${W - sb - 240}" height="160" rx="24" fill="${T.surface}" stroke="${T.hairline}"/>
    ${row(770, "Long-term memory", "Off")}
    ${row(850, "Per-conversation pinning", "On")}

    ${sectionTitle(1000, "PRIVACY")}
    <rect x="${sb + 120}" y="1030" width="${W - sb - 240}" height="240" rx="24" fill="${T.surface}" stroke="${T.hairline}"/>
    ${row(1050, "Web search", "Off")}
    ${row(1130, "Diagnostics", "On (anonymous, local-only)")}
    ${row(1210, "Delete all data", "→", true)}
  `;
  return desktopWindow("Localyze.ai — Settings", body);
}

// ---- run all ----
const tasks = [
  { name: "phone-chat.png",     svg: phoneChat() },
  { name: "phone-library.png",  svg: phoneLibrary() },
  { name: "phone-privacy.png",  svg: phonePrivacy() },
  { name: "desktop-chat.png",   svg: desktopChat() },
  { name: "desktop-settings.png", svg: desktopSettings() },
];

const logoBuf = await sharp(LOGO).resize(120, 120).png().toBuffer();

for (const t of tasks) {
  const out = path.join(OUT, t.name);
  const pipeline = sharp(Buffer.from(t.svg));
  // Composite the real logo into the phone header for the chat screen
  if (t.name === "phone-chat.png") {
    await pipeline
      .composite([{ input: logoBuf, top: 150, left: 50 }])
      .png({ compressionLevel: 9 })
      .toFile(out);
  } else {
    await pipeline.png({ compressionLevel: 9 }).toFile(out);
  }
  console.log("Wrote", out);
}
