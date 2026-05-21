// Tiny static dev server (replaces python -m http.server).
//   node serve.mjs            -> serves ./ on :8000
//   node serve.mjs 8080       -> custom port
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.argv[2]) || 8000;

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css":  "text/css; charset=utf-8",
  ".js":   "text/javascript; charset=utf-8",
  ".mjs":  "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg":  "image/svg+xml",
  ".png":  "image/png",
  ".jpg":  "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".ico":  "image/x-icon",
  ".xml":  "application/xml; charset=utf-8",
  ".txt":  "text/plain; charset=utf-8",
};

http.createServer((req, res) => {
  let url = decodeURIComponent(req.url.split("?")[0]);
  if (url.endsWith("/")) url += "index.html";
  const file = path.join(ROOT, url);
  if (!file.startsWith(ROOT)) { res.writeHead(403); return res.end("403"); }
  fs.readFile(file, (err, data) => {
    if (err) {
      fs.readFile(path.join(ROOT, "404.html"), (e2, d2) => {
        res.writeHead(404, { "Content-Type": "text/html; charset=utf-8" });
        res.end(e2 ? "Not found" : d2);
      });
      return;
    }
    res.writeHead(200, {
      "Content-Type": MIME[path.extname(file)] || "application/octet-stream",
      "Cache-Control": "no-cache",
    });
    res.end(data);
  });
}).listen(PORT, () => {
  console.log(`Localyze.ai dev server -> http://127.0.0.1:${PORT}/`);
});
