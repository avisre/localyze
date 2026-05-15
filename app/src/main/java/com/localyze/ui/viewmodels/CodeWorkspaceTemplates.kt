package com.localyze.ui.viewmodels

private data class ProductSeed(
    val name: String,
    val description: String,
    val price: Int,
    val category: String,
    val accent: String
)

private data class StoreProfile(
    val brand: String,
    val category: String,
    val audience: String,
    val offer: String,
    val primary: String,
    val secondary: String,
    val ink: String,
    val paper: String,
    val products: List<ProductSeed>
)

private data class WebsiteProfile(
    val brand: String,
    val overline: String,
    val headline: String,
    val body: String,
    val primary: String,
    val secondary: String,
    val accent: String,
    val cta: String,
    val secondaryCta: String,
    val metrics: List<Pair<String, String>>,
    val cards: List<Pair<String, String>>
)

internal fun buildWebsiteTemplateForInstruction(instruction: String): String {
    val lower = instruction.lowercase()
    val commerceKeywords = listOf(
        "ecommerce", "e-commerce", "store", "shop", "product", "cart", "checkout",
        "shoe", "skincare", "fashion", "coffee", "jewelry", "fitness", "pet"
    )
    if (commerceKeywords.any { lower.contains(it) }) {
        return buildEcommerceLandingPageTemplate(instruction)
    }

    val profile = websiteProfileForInstruction(instruction)
    val metrics = profile.metrics.joinToString("\n") { (value, label) ->
        """
        <div class="metric">
          <strong>${escapeHtml(value)}</strong>
          <span>${escapeHtml(label)}</span>
        </div>
        """.trimIndent()
    }
    val cards = profile.cards.joinToString("\n") { (title, copy) ->
        """
        <article class="feature-card">
          <span></span>
          <h3>${escapeHtml(title)}</h3>
          <p>${escapeHtml(copy)}</p>
        </article>
        """.trimIndent()
    }

    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(profile.brand)}</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html { scroll-behavior: smooth; -webkit-text-size-adjust: 100%; }
    :root {
      --primary: ${profile.primary};
      --secondary: ${profile.secondary};
      --accent: ${profile.accent};
      --ink: #1d1d1f;
      --muted: #6e6e73;
      --paper: #f5f5f7;
      --surface: rgba(255, 255, 255, 0.82);
      --line: rgba(29, 29, 31, 0.13);
      --shadow: 0 24px 70px rgba(20, 26, 34, 0.13);
    }
    body {
      min-height: 100vh;
      font-family: ui-sans-serif, -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", sans-serif;
      color: var(--ink);
      background:
        radial-gradient(circle at top left, var(--secondary), transparent 32rem),
        linear-gradient(180deg, #ffffff 0%, var(--paper) 46%, #ffffff 100%);
      line-height: 1.45;
    }
    a { color: inherit; text-decoration: none; }
    button, input { font: inherit; }
    .shell { width: min(1120px, calc(100% - 32px)); margin: 0 auto; }
    .nav {
      position: sticky; top: 0; z-index: 10;
      border-bottom: 1px solid var(--line);
      background: rgba(255, 255, 255, 0.74);
      backdrop-filter: blur(22px);
    }
    .nav-inner { min-height: 64px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
    .brand { display: flex; align-items: center; gap: 10px; font-weight: 800; letter-spacing: 0; }
    .mark { width: 30px; height: 30px; border-radius: 8px; background: linear-gradient(135deg, var(--primary), var(--secondary)); box-shadow: inset 0 0 0 1px rgba(255,255,255,0.5); }
    .links { display: flex; align-items: center; gap: 18px; color: var(--muted); font-size: 0.92rem; font-weight: 650; }
    .nav-cta, .primary-cta, .secondary-cta, .form button {
      border: 0; border-radius: 999px; cursor: pointer; transition: transform 0.2s ease, box-shadow 0.2s ease;
    }
    .nav-cta { padding: 9px 14px; color: #fff; background: var(--ink); }
    .hero { min-height: calc(100vh - 64px); display: grid; grid-template-columns: minmax(0, 1fr) minmax(300px, 0.92fr); align-items: center; gap: 38px; padding: 48px 0 42px; }
    .eyebrow { display: inline-flex; align-items: center; gap: 8px; padding: 7px 11px; border-radius: 999px; border: 1px solid var(--line); background: var(--surface); color: var(--primary); font-size: 0.82rem; font-weight: 800; }
    h1 { margin-top: 18px; max-width: 740px; font-size: clamp(2.65rem, 7vw, 5.7rem); line-height: 0.96; letter-spacing: 0; }
    .hero-copy { margin-top: 18px; max-width: 620px; color: var(--muted); font-size: clamp(1rem, 2vw, 1.18rem); }
    .actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 28px; }
    .primary-cta, .secondary-cta { display: inline-flex; align-items: center; justify-content: center; min-height: 46px; padding: 0 18px; font-weight: 800; }
    .primary-cta { color: #fff; background: var(--primary); box-shadow: 0 16px 36px rgba(10, 132, 255, 0.22); }
    .secondary-cta { background: rgba(255,255,255,0.8); border: 1px solid var(--line); color: var(--ink); }
    .primary-cta:hover, .secondary-cta:hover, .nav-cta:hover, .form button:hover { transform: translateY(-2px); }
    .device {
      position: relative; min-height: 520px; border-radius: 8px; overflow: hidden;
      background: linear-gradient(145deg, rgba(255,255,255,0.9), rgba(255,255,255,0.54));
      border: 1px solid var(--line); box-shadow: var(--shadow);
    }
    .device::before { content: ""; position: absolute; inset: 26px; border-radius: 8px; border: 1px solid rgba(29,29,31,0.1); }
    .orbital { position: absolute; width: 58%; aspect-ratio: 1; left: 50%; top: 42%; transform: translate(-50%, -50%); border-radius: 50%; background: radial-gradient(circle at 34% 28%, #fff, var(--secondary)); box-shadow: inset 0 0 0 1px rgba(255,255,255,0.5), 0 28px 90px rgba(0,0,0,0.16); }
    .panel { position: absolute; left: 26px; right: 26px; bottom: 26px; padding: 18px; border-radius: 8px; background: rgba(255,255,255,0.86); border: 1px solid var(--line); backdrop-filter: blur(20px); }
    .panel strong { display: block; font-size: 1.1rem; }
    .panel p { margin-top: 6px; color: var(--muted); }
    .metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; padding: 6px 0 44px; }
    .metric, .feature-card {
      border: 1px solid var(--line); border-radius: 8px; background: rgba(255,255,255,0.78);
      box-shadow: 0 12px 34px rgba(0,0,0,0.05);
    }
    .metric { padding: 18px; }
    .metric strong { display: block; font-size: clamp(1.55rem, 4vw, 2.6rem); letter-spacing: 0; }
    .metric span { color: var(--muted); font-weight: 650; }
    .section-head { padding: 42px 0 18px; max-width: 760px; }
    .section-head h2 { font-size: clamp(2rem, 5vw, 4rem); line-height: 1; letter-spacing: 0; }
    .section-head p { margin-top: 12px; color: var(--muted); }
    .features { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; padding-bottom: 42px; }
    .feature-card { padding: 22px; min-height: 230px; display: flex; flex-direction: column; justify-content: space-between; }
    .feature-card span { width: 42px; height: 42px; border-radius: 8px; background: linear-gradient(135deg, var(--accent), var(--secondary)); box-shadow: inset 0 0 0 1px rgba(255,255,255,0.35); }
    .feature-card h3 { margin-top: auto; font-size: 1.15rem; }
    .feature-card p { margin-top: 8px; color: var(--muted); }
    .cta-band { margin: 28px auto 52px; padding: 26px; border-radius: 8px; color: white; background: linear-gradient(135deg, var(--ink), var(--primary)); display: grid; grid-template-columns: 1fr minmax(240px, 380px); gap: 18px; align-items: center; }
    .cta-band p { margin-top: 8px; color: rgba(255,255,255,0.72); }
    .form { display: flex; gap: 8px; padding: 7px; border-radius: 999px; background: white; }
    .form input { min-width: 0; flex: 1; border: 0; outline: 0; padding: 0 12px; color: var(--ink); }
    .form button { padding: 11px 15px; background: var(--primary); color: white; font-weight: 800; }
    footer { border-top: 1px solid var(--line); padding: 24px 0 36px; color: var(--muted); }
    @media (max-width: 860px) {
      .links a { display: none; }
      .hero, .cta-band { grid-template-columns: 1fr; }
      .hero { min-height: auto; padding-top: 38px; }
      .device { min-height: 380px; }
      .metrics, .features { grid-template-columns: 1fr; }
    }
    @media (max-width: 520px) {
      .shell { width: min(100% - 24px, 1120px); }
      h1 { font-size: clamp(2.35rem, 14vw, 4.2rem); }
      .actions, .form { flex-direction: column; align-items: stretch; border-radius: 8px; }
      .form input { padding: 12px; }
    }
  </style>
</head>
<body>
  <header class="nav">
    <div class="shell nav-inner">
      <a class="brand" href="#"><span class="mark"></span>${escapeHtml(profile.brand)}</a>
      <nav class="links">
        <a href="#features">Features</a>
        <a href="#contact">Contact</a>
        <button class="nav-cta" type="button" data-scroll="#contact">Start</button>
      </nav>
    </div>
  </header>
  <main>
    <section class="shell hero">
      <div>
        <span class="eyebrow">${escapeHtml(profile.overline)}</span>
        <h1>${escapeHtml(profile.headline)}</h1>
        <p class="hero-copy">${escapeHtml(profile.body)}</p>
        <div class="actions">
          <a class="primary-cta" href="#contact">${escapeHtml(profile.cta)}</a>
          <a class="secondary-cta" href="#features">${escapeHtml(profile.secondaryCta)}</a>
        </div>
      </div>
      <div class="device" aria-label="Visual preview">
        <div class="orbital"></div>
        <div class="panel">
          <strong>${escapeHtml(profile.brand)}</strong>
          <p>Responsive, interactive, and ready to preview inside Localyze.</p>
        </div>
      </div>
    </section>
    <section class="shell metrics">
      $metrics
    </section>
    <section class="shell" id="features">
      <div class="section-head">
        <h2>Built with clarity from the first tap.</h2>
        <p>Every section is sized for small screens first, then expanded into a composed desktop layout.</p>
      </div>
      <div class="features">
        $cards
      </div>
    </section>
    <section class="shell cta-band" id="contact">
      <div>
        <h2>Launch the polished version.</h2>
        <p>Leave an email and the page responds instantly, so the preview feels alive instead of static.</p>
      </div>
      <form class="form" id="lead-form">
        <input id="email" type="email" placeholder="you@example.com" aria-label="Email address" required>
        <button type="submit">Notify me</button>
      </form>
    </section>
  </main>
  <footer class="shell">2026 ${escapeHtml(profile.brand)}. Responsive single-file preview.</footer>
  <script>
    document.querySelectorAll('[data-scroll]').forEach((button) => {
      button.addEventListener('click', () => {
        document.querySelector(button.dataset.scroll).scrollIntoView({ behavior: 'smooth' });
      });
    });
    document.getElementById('lead-form').addEventListener('submit', (event) => {
      event.preventDefault();
      const button = event.currentTarget.querySelector('button');
      const input = document.getElementById('email');
      button.textContent = input.value ? 'Saved' : 'Notify me';
      input.value = '';
      setTimeout(() => { button.textContent = 'Notify me'; }, 1800);
    });
  </script>
</body>
</html>
    """.trimIndent()
}

internal fun buildEcommerceLandingPageTemplate(instruction: String): String {
    val baseProfile = storeProfileForInstruction(instruction)
    val brandOverride = extractExplicitBrand(instruction)
    val profile = if (brandOverride != null) {
        baseProfile.copy(
            brand = brandOverride,
            category = if (instruction.contains("sneaker", ignoreCase = true)) {
                "performance sneakers"
            } else {
                baseProfile.category
            }
        )
    } else {
        baseProfile
    }
    val requestedProductCount = Regex("""\b(\d+)\s+product""", RegexOption.IGNORE_CASE)
        .find(instruction)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.coerceIn(4, 8)
        ?: profile.products.size
    val displayProducts = expandProducts(profile.products, requestedProductCount)
    val products = displayProducts.joinToString("\n") { product ->
        """
        <article class="product-card" data-category="${product.category}">
          <button class="quick-view" type="button" data-name="${escapeHtml(product.name)}" data-price="${product.price}" data-description="${escapeHtml(product.description)}">Quick view</button>
          <div class="product-art" style="--product-accent: ${product.accent};">
            <span>${escapeHtml(product.category)}</span>
          </div>
          <div class="product-copy">
            <p class="rating">5/5 <span>4.8 average</span></p>
            <h3>${escapeHtml(product.name)}</h3>
            <p>${escapeHtml(product.description)}</p>
          </div>
          <div class="product-actions">
            <strong>${'$'}${product.price}</strong>
            <button class="add-cart" type="button" data-price="${product.price}">Add to cart</button>
          </div>
        </article>
        """.trimIndent()
    }

    val categories = displayProducts
        .map { it.category }
        .distinct()
        .joinToString("\n") { category ->
            """<button class="filter-chip" type="button" data-filter="$category">${escapeHtml(category)}</button>"""
        }

    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(profile.brand)} - ${escapeHtml(profile.category)}</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html { font-size: 12px; -webkit-text-size-adjust: 100%; }
    :root {
      --primary: ${profile.primary};
      --secondary: ${profile.secondary};
      --ink: ${profile.ink};
      --paper: ${profile.paper};
      --muted: #687067;
      --line: rgba(21, 27, 24, 0.14);
      --white: #ffffff;
      --shadow: 0 18px 50px rgba(26, 34, 30, 0.13);
    }
    body {
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: var(--paper);
      color: var(--ink);
      line-height: 1.5;
    }
    button, input { font: inherit; }
    button { cursor: pointer; }
    .shell { width: min(1120px, calc(100% - 32px)); margin: 0 auto; }
    .nav {
      position: sticky; top: 0; z-index: 20;
      backdrop-filter: blur(18px);
      background: rgba(250, 249, 245, 0.88);
      border-bottom: 1px solid var(--line);
    }
    .nav-inner { min-height: 60px; display: flex; align-items: center; justify-content: space-between; gap: 14px; }
    .brand { display: flex; align-items: center; gap: 9px; font-weight: 900; letter-spacing: 0; font-size: 1rem; }
    .mark { width: 28px; height: 28px; border-radius: 8px; background: linear-gradient(135deg, var(--primary), var(--secondary)); box-shadow: inset 0 0 0 1px rgba(255,255,255,0.35); }
    .links { display: flex; align-items: center; gap: 12px; color: var(--muted); font-size: 0.9rem; }
    .links a { color: inherit; text-decoration: none; font-weight: 700; }
    .cart-pill { border: 1px solid var(--line); background: var(--white); border-radius: 999px; padding: 7px 10px; font-weight: 800; font-size: 0.88rem; box-shadow: 0 8px 22px rgba(0,0,0,0.05); }
    .hero { padding: 36px 0 26px; display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(310px, 0.95fr); gap: 28px; align-items: center; }
    .eyebrow { display: inline-flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 999px; background: var(--white); color: var(--primary); font-weight: 850; font-size: 0.78rem; }
    h1 { margin-top: 16px; font-size: clamp(1.7rem, 3.2vw, 2.55rem); line-height: 1.08; letter-spacing: 0; max-width: 720px; }
    .hero p { margin-top: 14px; max-width: 610px; color: var(--muted); font-size: 0.98rem; }
    .hero-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }
    .btn { border: 0; border-radius: 999px; padding: 11px 16px; font-weight: 900; font-size: 0.93rem; text-decoration: none; display: inline-flex; align-items: center; justify-content: center; transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease; }
    .btn:hover, .add-cart:hover, .filter-chip:hover { transform: translateY(-2px); }
    .btn-primary { background: var(--primary); color: white; box-shadow: 0 14px 28px rgba(0,0,0,0.16); }
    .btn-secondary { background: var(--white); color: var(--ink); border: 1px solid var(--line); }
    .hero-card { position: relative; min-height: 360px; border-radius: 8px; overflow: hidden; background: radial-gradient(circle at 30% 20%, var(--secondary), transparent 38%), linear-gradient(140deg, #ffffff, #ece9df); border: 1px solid var(--line); box-shadow: var(--shadow); }
    .product-orbit { position: absolute; inset: 34px; border: 1px solid rgba(0,0,0,0.08); border-radius: 8px; }
    .mock-product { position: absolute; left: 50%; top: 52%; transform: translate(-50%, -50%) rotate(-7deg); width: min(72%, 320px); aspect-ratio: 0.75; border-radius: 28px; background: linear-gradient(160deg, var(--primary), #111), linear-gradient(45deg, transparent 0 46%, rgba(255,255,255,0.25) 46% 56%, transparent 56%); box-shadow: 0 30px 70px rgba(0,0,0,0.25); display: grid; place-items: center; color: white; text-align: center; padding: 24px; font-weight: 900; }
    .mock-product small { display: block; margin-top: 10px; color: rgba(255,255,255,0.74); font-size: 0.86rem; }
    .floating-note { position: absolute; right: 22px; bottom: 22px; width: 190px; background: rgba(255,255,255,0.9); border: 1px solid var(--line); border-radius: 8px; padding: 14px; box-shadow: 0 16px 38px rgba(0,0,0,0.12); font-weight: 800; }
    .trust { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; padding: 14px 0 34px; }
    .trust div { background: var(--white); border: 1px solid var(--line); border-radius: 8px; padding: 16px; font-weight: 850; color: var(--muted); }
    .section-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; padding: 22px 0; }
    .section-head h2 { font-size: clamp(1.8rem, 4vw, 3rem); line-height: 1; }
    .filters { display: flex; flex-wrap: wrap; gap: 8px; }
    .filter-chip { border: 1px solid var(--line); background: var(--white); color: var(--ink); border-radius: 999px; padding: 10px 14px; font-weight: 850; }
    .filter-chip.active { background: var(--ink); color: white; }
    .grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
    .product-card { position: relative; background: var(--white); border: 1px solid var(--line); border-radius: 8px; padding: 12px; display: flex; flex-direction: column; min-height: 390px; box-shadow: 0 12px 34px rgba(0,0,0,0.06); }
    .quick-view { position: absolute; right: 22px; top: 22px; border: 0; background: rgba(255,255,255,0.88); color: var(--ink); border-radius: 999px; padding: 7px 10px; font-size: 0.78rem; font-weight: 900; }
    .product-art { height: 180px; border-radius: 8px; background: radial-gradient(circle at 30% 25%, #fff, transparent 28%), linear-gradient(140deg, var(--product-accent), #f7f4ec); display: grid; place-items: end start; padding: 16px; color: rgba(0,0,0,0.58); font-weight: 900; text-transform: uppercase; font-size: 0.74rem; }
    .product-copy { padding: 14px 2px; flex: 1; }
    .rating { color: #c28d20; font-size: 0.84rem; font-weight: 900; }
    .rating span { color: var(--muted); }
    .product-copy h3 { margin-top: 8px; font-size: 1.08rem; }
    .product-copy p:last-child { color: var(--muted); margin-top: 6px; font-size: 0.92rem; }
    .product-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
    .product-actions strong { font-size: 1.18rem; }
    .add-cart { border: 0; border-radius: 999px; padding: 10px 13px; background: var(--primary); color: white; font-weight: 900; transition: transform 0.18s ease, background 0.18s ease; white-space: nowrap; }
    .add-cart.added { background: #1f7a45; }
    .benefits, .reviews { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; padding: 42px 0 14px; }
    .benefit, .review { background: var(--white); border: 1px solid var(--line); border-radius: 8px; padding: 20px; }
    .benefit h3, .review strong { display: block; margin-bottom: 8px; }
    .review p, .benefit p { color: var(--muted); }
    .promo { margin: 34px 0; padding: 26px; border-radius: 8px; background: var(--ink); color: white; display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 18px; }
    .promo p { color: rgba(255,255,255,0.72); margin-top: 6px; }
    .signup { display: flex; gap: 8px; background: white; padding: 7px; border-radius: 999px; min-width: min(420px, 100%); }
    .signup input { border: 0; outline: 0; min-width: 0; flex: 1; padding: 0 10px; }
    .faq { padding: 16px 0 50px; }
    .faq-item { border-top: 1px solid var(--line); }
    .faq-question { width: 100%; border: 0; background: transparent; padding: 18px 0; display: flex; justify-content: space-between; text-align: left; font-weight: 900; color: var(--ink); }
    .faq-answer { display: none; color: var(--muted); padding: 0 0 18px; max-width: 760px; }
    .faq-item.open .faq-answer { display: block; }
    footer { border-top: 1px solid var(--line); padding: 24px 0 42px; color: var(--muted); }
    .modal { position: fixed; inset: 0; display: none; place-items: center; background: rgba(10, 12, 11, 0.45); z-index: 40; padding: 20px; }
    .modal.open { display: grid; }
    .modal-card { width: min(480px, 100%); background: white; border-radius: 8px; padding: 22px; box-shadow: var(--shadow); }
    .modal-card button { margin-top: 16px; }
    .cart-overlay { position: fixed; inset: 0; display: none; background: rgba(10, 12, 11, 0.34); z-index: 35; }
    .cart-overlay.open { display: block; }
    .cart-sidebar { position: fixed; top: 0; right: 0; z-index: 45; width: min(390px, 100%); height: 100%; background: white; border-left: 1px solid var(--line); box-shadow: -24px 0 70px rgba(0,0,0,0.18); transform: translateX(100%); transition: transform 0.22s ease; padding: 22px; display: flex; flex-direction: column; }
    .cart-sidebar.open { transform: translateX(0); }
    .cart-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 16px; border-bottom: 1px solid var(--line); }
    .cart-close { border: 1px solid var(--line); background: white; border-radius: 999px; padding: 8px 11px; font-weight: 900; }
    .cart-items { display: grid; gap: 10px; padding: 16px 0; overflow: auto; }
    .cart-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; border: 1px solid var(--line); border-radius: 8px; }
    .cart-empty { color: var(--muted); padding: 18px 0; }
    .cart-foot { margin-top: auto; border-top: 1px solid var(--line); padding-top: 16px; }
    .checkout { width: 100%; border: 0; border-radius: 999px; padding: 13px 16px; background: var(--primary); color: white; font-weight: 900; margin-top: 12px; }
    @media (max-width: 900px) {
      .hero, .promo { grid-template-columns: 1fr; }
      .grid, .trust { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .benefits, .reviews { grid-template-columns: 1fr; }
      .links a { display: none; }
    }
    @media (max-width: 560px) {
      .shell { width: min(100% - 22px, 1120px); }
      .nav-inner { min-height: 56px; }
      .hero { padding-top: 24px; }
      h1 { font-size: clamp(1.7rem, 10vw, 2.35rem); }
      .hero-card { min-height: 300px; }
      .grid, .trust { grid-template-columns: 1fr; }
      .section-head { align-items: start; flex-direction: column; }
      .product-card { min-height: auto; }
      .signup { border-radius: 8px; flex-direction: column; }
      .signup input { padding: 11px 10px; }
    }
  </style>
</head>
<body>
  <header class="nav">
    <div class="shell nav-inner">
      <div class="brand"><span class="mark"></span>${escapeHtml(profile.brand)}</div>
      <nav class="links">
        <a href="#shop">Shop</a>
        <a href="#reviews">Reviews</a>
        <a href="#faq">FAQ</a>
        <button class="cart-pill" id="cart-toggle" type="button">Cart <span id="cart-count">0</span> - <span id="cart-total">${'$'}0</span></button>
      </nav>
    </div>
  </header>
  <main>
    <section class="shell hero">
      <div>
        <span class="eyebrow">${escapeHtml(profile.offer)}</span>
        <h1>${escapeHtml(profile.category)} that feel ready before you are.</h1>
        <p>Built for ${escapeHtml(profile.audience)}, ${escapeHtml(profile.brand)} pairs confident product design with clear pricing, fast checkout cues, and details shoppers can trust.</p>
        <div class="hero-actions">
          <a class="btn btn-primary" href="#shop">Shop featured picks</a>
          <a class="btn btn-secondary" href="#reviews">Read customer stories</a>
        </div>
      </div>
      <div class="hero-card" aria-label="Featured product visual">
        <div class="product-orbit"></div>
        <div class="mock-product">${escapeHtml(displayProducts.first().name)}<small>${escapeHtml(profile.category)}</small></div>
        <div class="floating-note">Free shipping over ${'$'}75 - 30-day fit guarantee</div>
      </div>
    </section>
    <section class="shell trust">
      <div>4.8 average rating</div>
      <div>Carbon-aware shipping</div>
      <div>Secure checkout</div>
      <div>Easy returns</div>
    </section>
    <section class="shell" id="shop">
      <div class="section-head">
        <h2>Featured collection</h2>
        <div class="filters">
          <button class="filter-chip active" type="button" data-filter="all">All</button>
          $categories
        </div>
      </div>
      <div class="grid">
        $products
      </div>
    </section>
    <section class="shell benefits">
      <article class="benefit"><h3>Designed to compare fast</h3><p>Product names, ratings, prices, and benefits are visible without forcing shoppers into detail pages.</p></article>
      <article class="benefit"><h3>Mobile first purchase flow</h3><p>Thumb-friendly actions, readable cards, and stacked sections keep the page comfortable on phones.</p></article>
      <article class="benefit"><h3>Trust before checkout</h3><p>Shipping, returns, guarantees, and customer proof appear before the shopper has to commit.</p></article>
    </section>
    <section class="shell reviews" id="reviews">
      <article class="review"><strong>"Looks premium and buys fast."</strong><p>The product cards gave me everything I needed without hunting.</p></article>
      <article class="review"><strong>"The fit guide sold me."</strong><p>Clear details and quick-view made the store feel trustworthy.</p></article>
      <article class="review"><strong>"Checkout felt obvious."</strong><p>The cart counter and total made each add-to-cart action feel real.</p></article>
    </section>
    <section class="shell promo">
      <div>
        <h2>Get the launch edit</h2>
        <p>Join for early access, restock notes, and a first-order code.</p>
      </div>
      <form class="signup" id="signup-form">
        <input id="email" type="email" placeholder="you@example.com" aria-label="Email address" required>
        <button class="btn btn-primary" type="submit">Join list</button>
      </form>
    </section>
    <section class="shell faq" id="faq">
      <div class="faq-item open">
        <button class="faq-question" type="button">How fast is shipping?<span>+</span></button>
        <p class="faq-answer">Most orders leave the warehouse within one business day and include tracking.</p>
      </div>
      <div class="faq-item">
        <button class="faq-question" type="button">Can I return opened products?<span>+</span></button>
        <p class="faq-answer">Yes. The 30-day guarantee lets shoppers try the product and return it if it is not right.</p>
      </div>
      <div class="faq-item">
        <button class="faq-question" type="button">Is checkout connected here?<span>+</span></button>
        <p class="faq-answer">This preview uses static mock cart behavior, so it works fully offline in the workspace.</p>
      </div>
    </section>
  </main>
  <footer class="shell">&copy; 2026 ${escapeHtml(profile.brand)} - Crafted for ${escapeHtml(profile.audience)} - Privacy-friendly preview</footer>
  <div class="modal" id="modal" role="dialog" aria-modal="true" aria-label="Product quick view">
    <div class="modal-card">
      <h2 id="modal-title">Product</h2>
      <p id="modal-description"></p>
      <strong id="modal-price"></strong>
      <br>
      <button class="btn btn-primary" type="button" id="close-modal">Close</button>
    </div>
  </div>
  <div class="cart-overlay" id="cart-overlay"></div>
  <aside class="cart-sidebar" id="cart-sidebar" aria-label="Shopping cart">
    <div class="cart-head">
      <h2>Your cart</h2>
      <button class="cart-close" id="cart-close" type="button">Close</button>
    </div>
    <div class="cart-items" id="cart-items">
      <p class="cart-empty">Your cart is empty.</p>
    </div>
    <div class="cart-foot">
      <strong>Total: <span id="cart-sidebar-total">${'$'}0</span></strong>
      <button class="checkout" type="button">Checkout preview</button>
    </div>
  </aside>
  <script>
    const cart = { count: 0, total: 0, items: [] };
    const cartCount = document.getElementById('cart-count');
    const cartTotal = document.getElementById('cart-total');
    const cartSidebarTotal = document.getElementById('cart-sidebar-total');
    const cartItems = document.getElementById('cart-items');
    const cartSidebar = document.getElementById('cart-sidebar');
    const cartOverlay = document.getElementById('cart-overlay');
    const openCart = () => { cartSidebar.classList.add('open'); cartOverlay.classList.add('open'); };
    const closeCart = () => { cartSidebar.classList.remove('open'); cartOverlay.classList.remove('open'); };
    const renderCart = () => {
      cartCount.textContent = cart.count;
      cartTotal.textContent = '$' + cart.total;
      cartSidebarTotal.textContent = '$' + cart.total;
      cartItems.innerHTML = cart.items.length
        ? cart.items.map((item) => '<div class="cart-line"><span>' + item.name + '</span><strong>$' + item.price + '</strong></div>').join('')
        : '<p class="cart-empty">Your cart is empty.</p>';
    };
    document.getElementById('cart-toggle').addEventListener('click', openCart);
    document.getElementById('cart-close').addEventListener('click', closeCart);
    cartOverlay.addEventListener('click', closeCart);
    document.querySelectorAll('.add-cart').forEach((button) => {
      button.addEventListener('click', () => {
        const card = button.closest('.product-card');
        const name = card.querySelector('h3').textContent;
        const price = Number(button.dataset.price);
        cart.count += 1;
        cart.total += price;
        cart.items.push({ name, price });
        renderCart();
        button.classList.add('added');
        button.textContent = 'Added';
        openCart();
        setTimeout(() => { button.textContent = 'Add to cart'; button.classList.remove('added'); }, 1200);
      });
    });
    document.querySelectorAll('.filter-chip').forEach((chip) => {
      chip.addEventListener('click', () => {
        document.querySelectorAll('.filter-chip').forEach((item) => item.classList.remove('active'));
        chip.classList.add('active');
        const filter = chip.dataset.filter;
        document.querySelectorAll('.product-card').forEach((card) => {
          card.style.display = filter === 'all' || card.dataset.category === filter ? 'flex' : 'none';
        });
      });
    });
    const modal = document.getElementById('modal');
    document.querySelectorAll('.quick-view').forEach((button) => {
      button.addEventListener('click', () => {
        document.getElementById('modal-title').textContent = button.dataset.name;
        document.getElementById('modal-description').textContent = button.dataset.description;
        document.getElementById('modal-price').textContent = '$' + button.dataset.price;
        modal.classList.add('open');
      });
    });
    document.getElementById('close-modal').addEventListener('click', () => modal.classList.remove('open'));
    modal.addEventListener('click', (event) => { if (event.target === modal) modal.classList.remove('open'); });
    document.querySelectorAll('.faq-question').forEach((button) => {
      button.addEventListener('click', () => button.parentElement.classList.toggle('open'));
    });
    document.getElementById('signup-form').addEventListener('submit', (event) => {
      event.preventDefault();
      const email = document.getElementById('email');
      event.currentTarget.querySelector('button').textContent = email.value ? 'You are in' : 'Join list';
      email.value = '';
    });
  </script>
</body>
</html>
    """.trimIndent()
}

private fun websiteProfileForInstruction(instruction: String): WebsiteProfile {
    val lower = instruction.lowercase()
    return when {
        listOf("portfolio", "developer", "designer", "resume", "personal").any { lower.contains(it) } -> WebsiteProfile(
            brand = "Nova Hart",
            overline = "Independent product designer",
            headline = "Quietly precise digital work for ambitious teams.",
            body = "A focused portfolio for case studies, selected launches, and a concise path to hire the maker behind them.",
            primary = "#0a84ff",
            secondary = "#a7c7ff",
            accent = "#34c759",
            cta = "View work",
            secondaryCta = "Read approach",
            metrics = listOf("24" to "selected projects", "8 yrs" to "product craft", "96%" to "client retention"),
            cards = listOf(
                "Case studies with pace" to "Compact project summaries make the strongest outcomes easy to scan.",
                "Human contact flow" to "The call to action stays present without crowding the reading experience.",
                "Polished mobile rhythm" to "The layout holds generous whitespace while staying useful on a phone."
            )
        )
        listOf("restaurant", "cafe", "menu", "reservation", "food").any { lower.contains(it) } -> WebsiteProfile(
            brand = "Maison Rue",
            overline = "Seasonal neighborhood dining",
            headline = "A warm room, a short menu, and a reason to stay.",
            body = "A restaurant website with reservations, menu highlights, opening hours, and enough atmosphere to feel considered without relying on stock photos.",
            primary = "#2f5d50",
            secondary = "#f0bf6a",
            accent = "#ff9f0a",
            cta = "Reserve a table",
            secondaryCta = "See menu",
            metrics = listOf("5-10" to "dinner service", "18" to "seasonal plates", "4.9" to "guest rating"),
            cards = listOf(
                "Menu previews" to "Signature dishes are grouped by service moment, not generic categories.",
                "Reservation ready" to "The contact section behaves like a real booking prompt in the preview.",
                "Local warmth" to "Color, type, and copy create a calm dining mood without visual clutter."
            )
        )
        listOf("dashboard", "analytics", "admin", "saas", "startup", "app").any { lower.contains(it) } -> WebsiteProfile(
            brand = "Atlas Metrics",
            overline = "Realtime operating system",
            headline = "One calm dashboard for decisions that cannot wait.",
            body = "A product website for an analytics tool, with crisp value props, proof metrics, and a preview-like hero that feels software-native.",
            primary = "#1d1d1f",
            secondary = "#64d2ff",
            accent = "#0a84ff",
            cta = "Book demo",
            secondaryCta = "Explore product",
            metrics = listOf("42%" to "faster reviews", "12k" to "events per minute", "99.9%" to "uptime target"),
            cards = listOf(
                "Executive signal" to "Summaries, alerts, and trends are presented as one clear operating layer.",
                "Team workflows" to "Every feature card describes an action a real team would take.",
                "Trust by default" to "The visual system feels secure, restrained, and enterprise-ready."
            )
        )
        else -> WebsiteProfile(
            brand = "Luma Studio",
            overline = "Responsive launch page",
            headline = "A polished website that feels finished on the first preview.",
            body = "A complete single-file site with real structure, adaptive layout, interactive form behavior, and a refined visual system.",
            primary = "#0a84ff",
            secondary = "#c7e5ff",
            accent = "#34c759",
            cta = "Start project",
            secondaryCta = "See details",
            metrics = listOf("100%" to "responsive", "1 file" to "HTML CSS JS", "0" to "external assets"),
            cards = listOf(
                "Immediate preview" to "The generated page opens directly in the live preview pane.",
                "Real content hierarchy" to "Hero, proof, features, and contact sections are written with usable copy.",
                "Subtle interaction" to "Buttons, smooth scroll, and form feedback make the page feel alive."
            )
        )
    }
}

private fun extractExplicitBrand(instruction: String): String? {
    val match = Regex("""\bfor\s+([A-Z][A-Za-z0-9'.&-]{1,30})(?=\s|$)""").find(instruction)
    val brand = match?.groupValues?.getOrNull(1)?.trim()
    return brand?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("a", "an", "the") }
}

private fun expandProducts(products: List<ProductSeed>, count: Int): List<ProductSeed> {
    if (products.size >= count) return products.take(count)
    val additions = listOf(
        ProductSeed("Launch Crew Sock", "Cushioned knit support for everyday training and travel.", 22, "Accessories", "#b7c7d9"),
        ProductSeed("City Pace Sneaker", "Street-ready cushioning with a breathable knit upper.", 132, "Shoes", "#9cc5a1"),
        ProductSeed("All-Weather Tote", "Water-resistant carry with a laptop sleeve and easy-access pocket.", 72, "Bags", "#d6a76c"),
        ProductSeed("Recovery Hoodie", "Soft post-session layer with a structured hood and relaxed fit.", 88, "Apparel", "#b9a7d6")
    )
    return (products + additions).take(count)
}

private fun storeProfileForInstruction(instruction: String): StoreProfile {
    val lower = instruction.lowercase()
    return when {
        listOf("shoe", "sneaker", "runner", "running", "trail").any { lower.contains(it) } -> StoreProfile(
            brand = "RidgeRun Supply",
            category = "trail running gear",
            audience = "runners who split miles between city pavement and rough singletrack",
            offer = "Launch week: save 20% on performance bundles",
            primary = "#193f32",
            secondary = "#f3c75f",
            ink = "#14201b",
            paper = "#f6f3eb",
            products = listOf(
                ProductSeed("Apex Trail Runner", "Rock-plate support, grippy lugs, and a breathable upper for long climbs.", 148, "Shoes", "#8fc27f"),
                ProductSeed("Summit Recovery Slide", "Soft post-run cushioning with water-friendly straps.", 64, "Recovery", "#f3c75f"),
                ProductSeed("Ridgeline Hydration Vest", "Bounce-free storage for keys, gels, phone, and two soft flasks.", 118, "Packs", "#9eb8d7"),
                ProductSeed("Storm Shell Cap", "Featherweight rain protection with a crushable brim.", 38, "Accessories", "#d98b66")
            )
        )
        listOf("skin", "beauty", "serum", "cosmetic").any { lower.contains(it) } -> StoreProfile(
            brand = "Luma Ritual",
            category = "clean skincare",
            audience = "busy shoppers who want gentle formulas and clear routines",
            offer = "Starter kits ship free today",
            primary = "#6f3d4d",
            secondary = "#f2b8a2",
            ink = "#251d1f",
            paper = "#fbf4ef",
            products = listOf(
                ProductSeed("Cloud Milk Cleanser", "Cream cleanser that removes sunscreen without stripping.", 34, "Cleanse", "#f2b8a2"),
                ProductSeed("Barrier Bloom Serum", "Ceramide and oat blend for calmer-looking skin.", 48, "Treat", "#d8b6e4"),
                ProductSeed("Dewlock Gel Cream", "Weightless hydration with a soft satin finish.", 42, "Moisturize", "#b7d9c8"),
                ProductSeed("Daily Veil SPF", "Mineral SPF with no white cast and a smooth primer feel.", 39, "Protect", "#f0d27c")
            )
        )
        listOf("furniture", "desk", "lamp", "decor").any { lower.contains(it) } ||
            lower.contains("home goods") ||
            lower.contains("home decor") -> StoreProfile(
            brand = "AuraFlow Home",
            category = "warm modern home goods",
            audience = "people styling calm workspaces and small apartments",
            offer = "Bundle lighting and storage for 15% off",
            primary = "#3b342b",
            secondary = "#d8a85f",
            ink = "#241f1a",
            paper = "#f7f1e7",
            products = listOf(
                ProductSeed("Halo Desk Lamp", "Dimmable warm light with a compact weighted base.", 86, "Lighting", "#d8a85f"),
                ProductSeed("Modular Catchall Tray", "Stackable felt-lined tray for desk essentials.", 44, "Storage", "#a5b19d"),
                ProductSeed("Arc Floor Light", "Soft indirect glow for reading corners.", 164, "Lighting", "#c98f68"),
                ProductSeed("Oak Cable Dock", "Weighted dock that hides charging clutter.", 52, "Desk", "#b88455")
            )
        )
        listOf("headphone", "audio", "speaker", "electronics", "gadget", "tech").any { lower.contains(it) } -> StoreProfile(
            brand = "VoltHaus Audio",
            category = "wireless audio gear",
            audience = "commuters, creators, and focused desk workers",
            offer = "Launch bundle: earbuds plus case for 18% off",
            primary = "#1f3147",
            secondary = "#68c7d4",
            ink = "#111a24",
            paper = "#eff6f7",
            products = listOf(
                ProductSeed("Pulse ANC Earbuds", "Adaptive noise control with a pocketable charging case.", 129, "Earbuds", "#68c7d4"),
                ProductSeed("Studio Arc Headphones", "Over-ear comfort with crisp calls and 38-hour battery life.", 188, "Headphones", "#9ab4ff"),
                ProductSeed("Dock Mini Speaker", "Compact speaker with room-filling sound for small spaces.", 74, "Speakers", "#f2bd6b"),
                ProductSeed("Cable-Free Desk Kit", "Charging pad, braided cable, and desk stand in one set.", 58, "Accessories", "#a7d8a8")
            )
        )
        listOf("fashion", "clothing", "apparel", "shirt", "dress", "jacket").any { lower.contains(it) } -> StoreProfile(
            brand = "North Loom",
            category = "versatile everyday apparel",
            audience = "people building a smaller, sharper wardrobe",
            offer = "Capsule edit: buy any two layers, save 15%",
            primary = "#37433d",
            secondary = "#d7b98b",
            ink = "#1c2420",
            paper = "#f7f2e8",
            products = listOf(
                ProductSeed("Everyday Overshirt", "Midweight cotton layer with utility pockets and a relaxed fit.", 88, "Layers", "#a9b29a"),
                ProductSeed("Ribbed Travel Tee", "Soft rib knit that holds shape through long days.", 42, "Tops", "#d7b98b"),
                ProductSeed("Tapered Weekender Pant", "Stretch waist, tailored leg, and hidden phone pocket.", 96, "Bottoms", "#9eaec6"),
                ProductSeed("Cloud Knit Cardigan", "Breathable texture for cool mornings and late dinners.", 118, "Layers", "#d9a7a7")
            )
        )
        listOf("pet", "dog", "cat", "puppy").any { lower.contains(it) } -> StoreProfile(
            brand = "PawMarket",
            category = "thoughtful pet essentials",
            audience = "pet parents who want durable gear that still looks good at home",
            offer = "New pet kit ships free this week",
            primary = "#275044",
            secondary = "#f1b66a",
            ink = "#18241f",
            paper = "#f5f0e5",
            products = listOf(
                ProductSeed("Trail Walk Harness", "Padded, adjustable harness with reflective trim.", 54, "Walk", "#8fc0a2"),
                ProductSeed("Slow Feast Bowl", "Weighted ceramic bowl that helps pace eager eaters.", 36, "Feed", "#f1b66a"),
                ProductSeed("Calm Nap Mat", "Washable quilted mat sized for crates, cars, and couches.", 68, "Sleep", "#b3c5de"),
                ProductSeed("Treat Pocket Set", "Clip-on pouch with two training treat blends.", 28, "Training", "#d99576")
            )
        )
        listOf("coffee", "bean", "roast", "espresso").any { lower.contains(it) } -> StoreProfile(
            brand = "Roast House",
            category = "small-batch coffee beans",
            audience = "home brewers who want cafe-level flavor without guesswork",
            offer = "Subscribe today and get the first bag 30% off",
            primary = "#3b2418",
            secondary = "#d69a4b",
            ink = "#201611",
            paper = "#f7efe2",
            products = listOf(
                ProductSeed("Morning Bloom Blend", "Balanced notes of cocoa, citrus, and toasted sugar.", 18, "Blend", "#d69a4b"),
                ProductSeed("Ethiopia Halo", "Single-origin roast with jasmine aroma and berry sweetness.", 24, "Single origin", "#c48ab2"),
                ProductSeed("Cold Brew Reserve", "Low-acid roast built for smooth overnight brewing.", 22, "Cold brew", "#8f7763"),
                ProductSeed("Barista Starter Kit", "Beans, filters, scoop, and brew card for a better first cup.", 42, "Bundles", "#b8a06f")
            )
        )
        listOf("baby", "stroller", "kid", "toddler", "nursery").any { lower.contains(it) } -> StoreProfile(
            brand = "Nestling Co",
            category = "baby travel gear",
            audience = "new parents who need compact gear that handles real errands",
            offer = "Registry week: free organizer with every stroller",
            primary = "#455a64",
            secondary = "#f0c7a6",
            ink = "#1e2a2f",
            paper = "#f7f3ec",
            products = listOf(
                ProductSeed("Metro Fold Stroller", "One-hand fold, smooth wheels, and a breathable canopy.", 289, "Strollers", "#9bb7c2"),
                ProductSeed("Snack Sprint Tray", "Clip-on tray with dishwasher-safe cup and snack sections.", 34, "Accessories", "#f0c7a6"),
                ProductSeed("Cloud Nap Insert", "Soft washable insert for longer walks and naps.", 46, "Comfort", "#c9d7bb"),
                ProductSeed("Parent Console", "Zip pocket, cup holder, and phone dock in one organizer.", 39, "Accessories", "#d6a7a7")
            )
        )
        listOf("jewelry", "jewellery", "ring", "necklace", "bracelet").any { lower.contains(it) } -> StoreProfile(
            brand = "Atelier Gleam",
            category = "handmade jewelry",
            audience = "gift shoppers looking for pieces that feel personal and wearable",
            offer = "Gift-ready packaging included on every order",
            primary = "#5c3d4f",
            secondary = "#d9b56f",
            ink = "#241b22",
            paper = "#faf4eb",
            products = listOf(
                ProductSeed("Sol Pendant", "Brushed pendant on an adjustable recycled-gold chain.", 74, "Necklaces", "#d9b56f"),
                ProductSeed("Mira Stacking Ring", "Slim hammered ring designed for mixing metals.", 46, "Rings", "#c9a9d6"),
                ProductSeed("Luna Hoop Pair", "Lightweight oval hoops with a satin finish.", 58, "Earrings", "#e5b9a7"),
                ProductSeed("Woven Memory Bracelet", "Adjustable bracelet with a subtle hand-braided texture.", 52, "Bracelets", "#9ab0c8")
            )
        )
        listOf("fitness", "gym", "workout", "equipment", "dumbbell").any { lower.contains(it) } -> StoreProfile(
            brand = "FormLab Fitness",
            category = "compact home fitness gear",
            audience = "small-space athletes who want durable gear that stores cleanly",
            offer = "Home gym kits ship free over $99",
            primary = "#243b53",
            secondary = "#ef8354",
            ink = "#142033",
            paper = "#f1f4f6",
            products = listOf(
                ProductSeed("FlexBand Pro Set", "Three resistance bands with grippy woven sleeves.", 42, "Mobility", "#ef8354"),
                ProductSeed("StackFit Dumbbells", "Adjustable pair with quiet plates and compact stand.", 168, "Strength", "#8fb3d9"),
                ProductSeed("CoreGlide Mat", "Dense no-slip mat for strength, stretch, and recovery.", 64, "Recovery", "#9ac7a8"),
                ProductSeed("Door Anchor Kit", "Secure anchor, handles, and workout cards for bands.", 28, "Accessories", "#d2b06f")
            )
        )
        else -> StoreProfile(
            brand = "MarketLane",
            category = "curated everyday essentials",
            audience = "shoppers who want useful goods with premium details",
            offer = "New arrivals with free returns",
            primary = "#253d5b",
            secondary = "#e7b35a",
            ink = "#182333",
            paper = "#f5f1e8",
            products = listOf(
                ProductSeed("Carry Daily Tote", "Structured carryall with padded pockets and weatherproof lining.", 92, "Bags", "#9bb8d3"),
                ProductSeed("Focus Bottle", "Ceramic-coated bottle that keeps drinks cold all day.", 36, "Drinkware", "#c4d6a6"),
                ProductSeed("Soft Tech Organizer", "Slim pouch for chargers, pens, and travel cables.", 48, "Travel", "#ddb17c"),
                ProductSeed("Weekender Kit", "A compact set of useful travel staples.", 78, "Bundles", "#d39da3")
            )
        )
    }
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
