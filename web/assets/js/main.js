/* Localyze.ai — tiny progressive-enhancement script.
   Everything works without JS; this just adds nice-to-haves. */

(function () {
  // Footer year
  var yr = document.getElementById('year');
  if (yr) yr.textContent = new Date().getFullYear();

  // Theme toggle (persists in localStorage; respects OS otherwise)
  var root = document.documentElement;
  var toggle = document.getElementById('themeToggle');
  var stored = null;
  try { stored = localStorage.getItem('localyze-theme'); } catch (e) {}
  if (stored === 'dark' || stored === 'light') {
    root.setAttribute('data-theme', stored);
  }
  function updateToggleLabel() {
    if (!toggle) return;
    var dark = root.getAttribute('data-theme') === 'dark'
      || (!root.hasAttribute('data-theme') && window.matchMedia('(prefers-color-scheme: dark)').matches);
    toggle.textContent = dark ? '☀' : '☾';
    toggle.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
  }
  updateToggleLabel();
  if (toggle) {
    toggle.addEventListener('click', function () {
      var current = root.getAttribute('data-theme');
      var next = current === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('localyze-theme', next); } catch (e) {}
      updateToggleLabel();
    });
  }

  // Mobile nav toggle
  var navToggle = document.getElementById('navToggle');
  var navLinks = document.getElementById('navLinks');
  if (navToggle && navLinks) {
    navToggle.addEventListener('click', function () {
      var open = navLinks.classList.toggle('open');
      navToggle.setAttribute('aria-expanded', String(open));
    });
    navLinks.addEventListener('click', function (e) {
      if (e.target && e.target.tagName === 'A') {
        navLinks.classList.remove('open');
        navToggle.setAttribute('aria-expanded', 'false');
      }
    });
  }

  // OS auto-detect on /download.html
  var hint = document.getElementById('osHint');
  var grid = document.getElementById('dlGrid');
  if (hint && grid) {
    var ua = navigator.userAgent || '';
    var platform = (navigator.platform || '') + ' ' + ua;
    var os = 'unknown';
    var label = 'your platform';
    if (/Android/i.test(ua))            { os = 'android'; label = 'Android'; }
    else if (/iPhone|iPad|iPod/i.test(ua)) { os = 'android'; label = 'iOS (try Android — iOS coming soon)'; }
    else if (/Win/i.test(platform))     { os = 'windows'; label = 'Windows'; }
    else if (/Mac/i.test(platform))     { os = 'macos';   label = 'macOS'; }
    else if (/Linux|X11/i.test(platform)) { os = 'linux'; label = 'Linux'; }

    if (os !== 'unknown') {
      hint.innerHTML = 'Detected <strong>' + label + '</strong>. We\'ve highlighted the recommended build.';
      var card = grid.querySelector('.dcard[data-os="' + os + '"]');
      if (card) {
        card.classList.add('suggested');
        var pill = document.createElement('span');
        pill.className = 'suggested-pill';
        pill.textContent = 'Recommended for you';
        card.insertBefore(pill, card.firstChild);
        // Move suggested card to first position visually
        grid.insertBefore(card, grid.firstChild);
      }
    } else {
      hint.textContent = 'Pick your platform below — all builds are free.';
    }
  }
})();
