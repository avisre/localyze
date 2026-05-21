# Localyze.ai — marketing website

The static marketing site for Localyze.ai, a private on-device AI assistant.

- **Stack:** plain HTML / CSS / vanilla JS. No build step required to serve.
- **Asset tooling:** Node + [Sharp](https://sharp.pixelplumbing.com/) for regenerating the OG image and the 5 mockup screenshots from SVG.
- **Privacy:** the site loads Google Fonts (Nunito). No analytics, no tracking SDKs.

## Run locally

```bash
npm install            # only needed if you want to regenerate images
node serve.mjs 8000    # serves the current directory on http://127.0.0.1:8000/
```

## Regenerate assets

```bash
npm run build:og              # writes /assets/img/og-image.png + favicons
npm run build:screenshots     # writes /assets/img/screenshots/*.png
npm run build                 # both
```

Source for the generated images lives in `assets/img/make-og.mjs` and `assets/img/make-screenshots.mjs`. The Localyze.ai logo (`assets/img/logo-mark.png`) is the input.

## Structure

```
.
├── index.html                       # homepage
├── pricing.html
├── download.html
├── privacy.html  terms.html  404.html
├── private-ai-assistant.html        # SEO landing pages
├── chatgpt-alternative-offline.html
├── local-ai-windows.html            # coming soon
├── local-ai-macos.html              # coming soon
├── local-ai-linux.html              # Ubuntu — available now
├── vs-chatgpt.html  vs-gemini.html  vs-copilot.html
├── blog/
│   ├── index.html
│   ├── agent-harness-explained.html
│   ├── harness-beats-raw-prompting.html
│   ├── local-models-and-harnesses.html
│   ├── why-on-device-ai-matters.html
│   ├── what-is-gemma-4-e4b.html
│   ├── cloud-ai-privacy-myth.html
│   └── how-to-run-llm-on-phone.html
├── assets/
│   ├── css/styles.css
│   ├── js/main.js
│   └── img/                         # logo, OG image, screenshots, favicons
├── robots.txt  sitemap.xml  manifest.json
├── package.json  serve.mjs
└── render.yaml                      # Render static-site config
```

## Deploy

This repo is set up for one-click deployment to [Render](https://render.com/):

1. Push to GitHub.
2. In Render, "New +" → "Blueprint" → pick this repo. The included `render.yaml` configures a static site with sensible security headers and asset caching.
3. Point your DNS (e.g. `localyze.ai`) at the Render service.

For any other static host (Cloudflare Pages, Netlify, S3+CloudFront, GitHub Pages): the publish directory is the repo root.

## License

© Localyze.ai. All rights reserved. Source available for transparency; the trademark and product are not open source.
