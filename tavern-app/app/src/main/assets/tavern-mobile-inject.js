/**
 * ST-Ctrl mobile injection script.
 * Injected into every tavern page load by TavernWebView.injectCSS().
 *
 * Contains:
 *   - Mobile-friendly CSS overrides
 *   - World book select sizing
 *   - Compat mode polyfills (dvw/dvh, ES2022 shims, layout fixes)
 *   - Keyboard viewport fix
 *   - rAF tracking for pauseRendering()
 */
(function() {
  if (document.getElementById('tavern-mobile-css')) return;

  // ═══ Base CSS (always applied) ═══
  var s = document.createElement('style');
  s.id = 'tavern-mobile-css';
  s.textContent = `
    *{-webkit-tap-highlight-color:transparent;touch-action:manipulation}
    body{overscroll-behavior-y:contain;background:#0a0a12!important}

    /* Rendering optimization: contain layout/style scope */
    #chat .mes, #chat [class*="mes"], #chat [class*="message"],
    .chat-content > *, #chat-content > * {
      contain:layout style;
    }

    /* Reduce paint area for frequently-updated elements */
    #chat, .chat-content, #chat-content {
      contain:layout style;
    }

    /* World book: improved multi-select */
    #world_info select[multiple], #WorldInfo select[multiple],
    [id*="world_info"] select[multiple], [id*="WorldInfo"] select[multiple] {
      max-height: 420px !important;
      overflow-y: auto !important;
      border-radius: 8px !important;
      border: 1px solid rgba(255,255,255,0.1) !important;
    }
    #world_info select[multiple] option, #WorldInfo select[multiple] option,
    [id*="world_info"] select[multiple] option, [id*="WorldInfo"] select[multiple] option {
      padding: 11px 14px !important;
      margin: 2px 6px !important;
      border-radius: 6px !important;
      font-size: 15px !important;
      cursor: pointer !important;
    }
    #world_info select[multiple] option:checked, #WorldInfo select[multiple] option:checked,
    [id*="world_info"] select[multiple] option:checked, [id*="WorldInfo"] select[multiple] option:checked {
      background: rgba(212,168,83,0.2) !important;
      color: #d4a853 !important;
      font-weight: 500 !important;
    }

    /* ── Drawer panel fade-in transition ── */
    .drawer-content {
      animation: drawer-fade-in 0.18s ease-out both;
    }
    @keyframes drawer-fade-in {
      from { opacity: 0; transform: translateY(6px); }
      to   { opacity: 1; transform: translateY(0); }
    }

    /* ── Faster animations (125ms) ── */
    :root { --animation-duration: 125ms; }

    /* ── Native textarea auto-height (Chrome 123+) ── */
    #send_textarea { field-sizing: content; min-height: 44px; max-height: 50vh; }

    /* ── Doc-height mechanism: JS-measured viewport height ──
       Android WebView's 100dvh lags behind when the keyboard opens.
       We drive #sheld/#chat heights from a JS-updated --doc-height var. */
    :root { --doc-height: 100dvh; }

    /* ── Mobile fixed body: prevents black flash on keyboard
       dismiss — the page doesn't reflow with adjustResize, only #sheld
       height tracks --doc-height. All scrolling happens inside #chat. ── */
    body {
      position: fixed !important;
      top: 0 !important;
      left: 0 !important;
      width: 100% !important;
      overflow: hidden !important;
    }
    #chat {
      overflow-y: auto !important;
      -webkit-overflow-scrolling: touch;
      max-height: calc(var(--doc-height) - var(--topBarBlockSize, 40px) - var(--bottomFormBlockSize, 60px)) !important;
    }
    #sheld {
      height: calc(var(--doc-height) - var(--topBarBlockSize, 40px) - 1px) !important;
      max-height: calc(var(--doc-height) - var(--topBarBlockSize, 40px) - 1px) !important;
    }
  `;
  document.head.appendChild(s);

  // Dynamic world-book select sizing
  (function() {
    var WB_SEL = '#world_info select[multiple], #WorldInfo select[multiple],' +
      '[id*="world_info"] select[multiple], [id*="WorldInfo"] select[multiple]';
    function sizeSelects() {
      document.querySelectorAll(WB_SEL).forEach(function(sel) {
        var n = sel.options.length;
        if (n > 1) sel.size = Math.min(n, 7);
      });
    }
    sizeSelects();
    new MutationObserver(sizeSelects).observe(document.body, { childList: true, subtree: true });
  })();

  // ═══ Mobile input & keyboard optimizations ═══
  (function() {
    var isMobile = /Mobi|Android/i.test(navigator.userAgent);
    if (!isMobile) return;

    var lastUserTouchTs = 0;
    var GRACE_MS = 900;
    var CHAT_ID = '#chat';
    var TEXTAREA_ID = '#send_textarea';

    // ── 1. Programmatic focus guard (no-op approach) ──
    // Replace sendTextArea.focus with a version that only allows real focus
    // when the user actively touched the textarea. Prevents keyboard flash.
    (function() {
      function installNoopFocus() {
        var ta = document.querySelector(TEXTAREA_ID);
        if (!(ta instanceof HTMLTextAreaElement)) {
          setTimeout(installNoopFocus, 300);
          return;
        }
        var origFocus = ta.focus;
        ta.focus = function() {
          if (Date.now() - lastUserTouchTs <= GRACE_MS) {
            origFocus.call(ta);
          }
          // Otherwise: silently ignored — keyboard stays closed
        };
      }
      document.addEventListener('pointerdown', function(e) {
        if (e.target && (e.target.closest && e.target.closest(TEXTAREA_ID))) lastUserTouchTs = Date.now();
      }, true);
      document.addEventListener('touchstart', function(e) {
        if (e.target && (e.target.closest && e.target.closest(TEXTAREA_ID))) lastUserTouchTs = Date.now();
      }, true);
      installNoopFocus();
    })();

    // ── 2. Enter = newline on mobile ──
    document.addEventListener('keydown', function(e) {
      var t = e.target;
      if (!(t instanceof HTMLTextAreaElement) || t.id !== 'send_textarea') return;
      if (e.isComposing || e.shiftKey || e.ctrlKey || e.altKey) return;
      if (e.key !== 'Enter') return;
      e.stopImmediatePropagation();
    }, true);

    // ── 3. Keyboard viewport fix + --doc-height ──
    (function() {
      var prevW = window.innerWidth;
      var prevVH = window.visualViewport ? window.visualViewport.height : window.innerHeight;

      // Update --doc-height so #sheld/#chat shrink to the keyboard top
      function updateDocHeight() {
        var vh = window.visualViewport ? window.visualViewport.height : window.innerHeight;
        document.documentElement.style.setProperty('--doc-height', vh + 'px');
      }
      if (window.visualViewport) window.visualViewport.addEventListener('resize', updateDocHeight);
      window.addEventListener('resize', updateDocHeight);
      updateDocHeight();

      // Primary: visualViewport resize detects keyboard open/close reliably
      if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', function() {
          var newVH = window.visualViewport.height;
          if (Math.abs(newVH - prevVH) > 10) {
            prevVH = newVH;
            // WorldInfo editor has its own IME handling — skip there
            var ae = document.activeElement;
            if (ae && ae.closest && ae.closest('#WorldInfo.openDrawer')) return;
            // Force compositor repaint with double-rAF (open AND close)
            var d = document.documentElement;
            d.style.position = 'fixed';
            requestAnimationFrame(function() {
              d.style.position = '';
              requestAnimationFrame(function() {
                document.body.style.minHeight = newVH + 'px';
                requestAnimationFrame(function() {
                  document.body.style.minHeight = '';
                });
              });
            });
          }
        });
      }

      // Fallback: window resize (orientation change + keyboard on some devices)
      window.addEventListener('resize', function() {
        var w = window.innerWidth;
        if (Math.abs(w - prevW) <= 4) {
          var ae = document.activeElement;
          if (!ae || !ae.closest || !ae.closest('#WorldInfo.openDrawer')) {
            document.documentElement.style.position = 'fixed';
            requestAnimationFrame(function() { document.documentElement.style.position = ''; });
          }
        }
        prevW = w;
      });
    })();

    // ── 4. ResizeObserver: keep chat visible when textarea grows ──
    (function() {
      var chat = document.getElementById('chat');
      if (!chat) return;
      var lastH = chat.offsetHeight;
      var ro = new ResizeObserver(function() {
        var newH = chat.offsetHeight;
        var delta = newH - lastH;
        var atBottom = Math.abs(chat.scrollHeight - chat.scrollTop - newH) <= 4;
        if (!atBottom && Math.abs(delta) > 4) chat.scrollTop -= delta;
        lastH = newH;
      });
      ro.observe(chat);
    })();

    // ── 6. Swipe guard + long-press ──
    (function() {
      // 6a. Swipe guard
      var swMinDx = 60, swDxDyRatio = 1.8, swBlockMs = 180, swMeaningful = 8;
      var swLastTs = 0, swLastTop = null, swLastDelta = 0;
      var chatEl2 = document.getElementById('chat');
      if (chatEl2) {
        chatEl2.addEventListener('scroll', function() {
          var now = performance.now(), top = chatEl2.scrollTop;
          swLastDelta = swLastTop !== null ? Math.abs(top - swLastTop) : 0;
          swLastTop = top; swLastTs = now;
        }, { passive: true });
      }
      function block(e) {
        if (!e || !e.detail) return false;
        var d = e.detail, dx = Math.abs((d.xEnd||0)-(d.xStart||0)), dy = Math.abs((d.yEnd||0)-(d.yStart||0));
        if (dx < swMinDx) return true;
        if (dy > 0 && dx/dy < swDxDyRatio) return true;
        if ((performance.now()-swLastTs) < swBlockMs && swLastDelta >= swMeaningful) return true;
        return false;
      }
      document.addEventListener('swiped-left', function(e) { if (block(e)) { e.stopPropagation(); e.preventDefault(); } }, true);
      document.addEventListener('swiped-right', function(e) { if (block(e)) { e.stopPropagation(); e.preventDefault(); } }, true);

      // 6b. Long-press = right-click
      var lpTimer = null, lpFired = false, lpTarget = null;
      document.addEventListener('touchstart', function(e) {
        var el = e.target && e.target.closest && e.target.closest('.mes');
        if (!el) return;
        lpTarget = el; lpFired = false;
        lpTimer = setTimeout(function() {
          lpFired = true; e.preventDefault();
          el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, view: window }));
        }, 500);
      }, { passive: false });
      document.addEventListener('touchend', function() { clearTimeout(lpTimer); });
      document.addEventListener('touchmove', function() { clearTimeout(lpTimer); });
    })();

    // ── 7. Android back-button handler ──
    window.__ctrlHandleBack = function() {
      var popup = document.querySelector(
        '#character_popup:not([style*="display:none"]),' +
        '#dialogue_popup:not([style*="display:none"]),' +
        '#world_popup:not([style*="display:none"]),' +
        '#select_chat_popup:not([style*="display:none"]),' +
        '.popup:not(.hidden)'
      );
      if (popup && popup.offsetParent !== null) {
        document.dispatchEvent(new KeyboardEvent('keydown',
          { key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true, cancelable:true }));
        return 'consumed';
      }
      var drawer = document.querySelector('.drawer-content.openDrawer');
      if (drawer && drawer.offsetParent !== null) {
        document.dispatchEvent(new KeyboardEvent('keydown',
          { key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true, cancelable:true }));
        return 'consumed';
      }
      var edit = document.getElementById('curEditTextarea');
      if (edit && edit.offsetParent !== null) {
        var done = document.querySelector('.mes_edit_done:visible');
        if (done) { done.click(); return 'consumed'; }
      }
      return 'noop';
    };
  })();

  // ═══ Compat mode polyfills ═══
  (function() {
    try {
      var compatOn = window.AndroidBridge ? AndroidBridge.compatModeEnabled() : '0';
      if (compatOn !== '1') return;
    } catch (e) { return; }

    console.log('[st-ctrl] Compat mode active');

    // Chrome 76+ and modern Android WebView can render backdrop-filter.
    // Keep it in compat mode so the Blur Strength slider keeps working; only
    // strip it on genuinely old WebView versions that cannot parse it.
    var supportsBackdropFilter = false;
    try {
      supportsBackdropFilter = !!(window.CSS && CSS.supports && (
        CSS.supports('backdrop-filter', 'blur(1px)') ||
        CSS.supports('-webkit-backdrop-filter', 'blur(1px)')
      ));
    } catch (_) {}

    // ── Redirect CDN fetches to local (old WebView TLS can't reach CDNs) ──
    if (window.fetch) {
      var _origFetch = window.fetch;
      window.fetch = function(url, opts) {
        var s = String(url);
        if (s.includes('jsdelivr.net/npm/vue')) {
          console.log('[st-ctrl] Redirecting CDN fetch to local vue');
          return _origFetch.call(window, '/vue-runtime.js', opts);
        }
        return _origFetch.call(window, url, opts);
      };
    }
    // Also monkey-patch dynamic import() via a script element interception
    var _origCE = document.createElement.bind(document);
    document.createElement = function(tag, opts) {
      var el = _origCE(tag, opts);
      if (tag.toLowerCase() === 'script') {
        var _origSA = el.setAttribute.bind(el);
        el.setAttribute = function(name, value) {
          if (name === 'src' && typeof value === 'string' && value.includes('jsdelivr.net/npm/vue')) {
            console.log('[st-ctrl] Redirecting script src to local vue');
            value = '/vue-runtime.js';
          }
          return _origSA(name, value);
        };
      }
      return el;
    };

    // ── Polyfill missing CSS rule constructors (old WebView lacks them) ──
    if (typeof CSSMediaRule === 'undefined') window.CSSMediaRule = function(){};
    if (typeof CSSStyleRule === 'undefined') window.CSSStyleRule = function(){};
    if (typeof CSSKeyframesRule === 'undefined') window.CSSKeyframesRule = function(){};
    if (typeof CSSSupportsRule === 'undefined') window.CSSSupportsRule = function(){};
    if (typeof CSSImportRule === 'undefined') window.CSSImportRule = function(){};
    if (typeof CSSContainerRule === 'undefined') window.CSSContainerRule = function(){};

    // ── CSS polyfill: intercept <style> tags to fix color-mix/backdrop-filter ──
    (function() {
      // Resolve a CSS colour value (var(--x), rgb(), rgba(), #hex, named) → {r,g,b,a}
      function resolveColour(val, root, local) {
        val = val.trim();
        // var(--name) or var(--name, fallback)
        if (val.startsWith('var(--')) {
          var end = val.indexOf(')');
          if (end < 0) return null;
          var inner = val.slice(4, end);
          // Split name and optional fallback
          var fbIdx = -1, depth = 0;
          for (var ii = 0; ii < inner.length; ii++) {
            if (inner[ii] === '(') depth++;
            else if (inner[ii] === ')') depth--;
            else if (inner[ii] === ',' && depth === 0) { fbIdx = ii; break; }
          }
          var name = fbIdx >= 0 ? inner.slice(0, fbIdx).trim() : inner.trim();
          var fallback = fbIdx >= 0 ? inner.slice(fbIdx + 1).trim() : null;
          // 1. Try getComputedStyle (for previously-parsed styles)
          var r = root.getPropertyValue(name).trim();
          if (r) return parseRGBA(r);
          // 2. Try local map (from this CSS text)
          if (local[name]) return resolveColour(local[name], root, local);
          // 3. Try fallback
          if (fallback) {
            r = root.getPropertyValue(fallback).trim();
            if (r) return parseRGBA(r);
            if (local[fallback]) return resolveColour(local[fallback], root, local);
            return parseRGBA(fallback);
          }
          return null;
        }
        return parseRGBA(val);
      }

      function parseRGBA(s) {
        s = s.trim();
        if (!s) return null;
        // transparent
        if (s === 'transparent') return { r: 0, g: 0, b: 0, a: 0 };
        // black / white
        if (s === 'black') return { r: 0, g: 0, b: 0, a: 1 };
        if (s === 'white') return { r: 255, g: 255, b: 255, a: 1 };
        // rgb/rgba
        var m = s.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+))?\s*\)/);
        if (m) return { r: +m[1], g: +m[2], b: +m[3], a: m[4] !== undefined ? +m[4] : 1 };
        // #hex
        m = s.match(/^#([0-9a-fA-F]{3,8})$/);
        if (m) {
          var h = m[1];
          if (h.length === 3) h = h[0]+h[0]+h[1]+h[1]+h[2]+h[2]+'ff';
          else if (h.length === 6) h += 'ff';
          return { r: parseInt(h.slice(0,2), 16), g: parseInt(h.slice(2,4), 16), b: parseInt(h.slice(4,6), 16), a: parseInt(h.slice(6,8)||'ff', 16) / 255 };
        }
        return null;
      }

      function rgbaToStr(c) {
        if (c.a >= 1) return 'rgb(' + c.r + ',' + c.g + ',' + c.b + ')';
        return 'rgba(' + c.r + ',' + c.g + ',' + c.b + ',' + c.a.toFixed(3).replace(/0+$/, '').replace(/\.$/, '') + ')';
      }

      // Mix two sRGB colours (weighted average).  Returns null if either missing.
      function mix(c1, c2, p1, p2) {
        if (!c1 || !c2) return null;  // need both colours
        var t = p1 + p2;
        if (t === 0) return c1;
        return {
          r: Math.round((c1.r * p1 + c2.r * p2) / t),
          g: Math.round((c1.g * p1 + c2.g * p2) / t),
          b: Math.round((c1.b * p1 + c2.b * p2) / t),
          a: +((c1.a * p1 + c2.a * p2) / t).toFixed(3)
        };
      }

      // Convert oklch(L C H [/A]) → rgb/rgba string
      function oklchToRGB(L, C, H, alpha) {
        if (isNaN(L) || isNaN(C)) return null;
        // oklch → oklab
        var hRad = (H || 0) * Math.PI / 180;
        var a = C * Math.cos(hRad);
        var b = C * Math.sin(hRad);
        // oklab → linear sRGB
        var l_ = L + 0.3963377774 * a + 0.2158037573 * b;
        var m_ = L - 0.1055613458 * a - 0.0638541728 * b;
        var s_ = L - 0.0894841775 * a - 1.2914855480 * b;
        var l3 = l_ * l_ * l_, m3 = m_ * m_ * m_, s3 = s_ * s_ * s_;
        var rLin = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3;
        var gLin = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3;
        var bLin = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3;
        // sRGB gamma
        function gamma(c) { return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1/2.4) - 0.055; }
        var r = Math.round(Math.max(0, Math.min(1, gamma(rLin))) * 255);
        var g = Math.round(Math.max(0, Math.min(1, gamma(gLin))) * 255);
        var bVal = Math.round(Math.max(0, Math.min(1, gamma(bLin))) * 255);
        if (isNaN(alpha) || alpha >= 1) return 'rgb(' + r + ',' + g + ',' + bVal + ')';
        return 'rgba(' + r + ',' + g + ',' + bVal + ',' + alpha.toFixed(3).replace(/0+$/, '').replace(/\.$/, '') + ')';
      }

      function fixCSS(css) {
        // Strip @layer (brace-counted in Kotlin, regex fallback here). @supports stays — it works on Chrome 76+
        css = css.replace(/@layer\s+\w+\s*\{/g, '');
        css = css.replace(/:root,:host/g, ':root');
        css = css.replace(/::backdrop\s*,\s*/g, '');
        css = css.replace(/,\s*::backdrop/g, '');
        // Strip :where() — unsupported on Chrome < 88
        css = css.replace(/:where\(([^)]*)\)/g, '$1');
        if (!supportsBackdropFilter) {
          css = css.replace(/backdrop-filter\s*:\s*[^;!}\n]+/g, 'backdrop-filter: none');
          css = css.replace(/-webkit-backdrop-filter\s*:\s*[^;!}\n]+/g, '-webkit-backdrop-filter: none');
        }

        // Convert oklch() → rgb() (Tailwind v4 uses this, unsupported on old WebView)
        css = css.replace(/oklch\(([^)]+)\)/g, function(_, args) {
          var parts = args.split(/\s+/).filter(Boolean);
          var L = parseFloat(parts[0]) / (parts[0].indexOf('%') >= 0 ? 100 : 1);
          var C = parseFloat(parts[1]) || 0;
          var H = parseFloat(parts[2]) || 0;
          var alpha = parts[3] ? parseFloat(parts[3].replace('/','')) : 1;
          var rgb = oklchToRGB(L, C, H, alpha);
          return rgb || 'transparent';
        });

        // Replace rgb(from var(--X) r g b / A) → var(--X) (relative colour syntax)
        css = css.replace(/rgb\(from\s+(var\(--[\w-]+\))\s+r\s+g\s+b\s*\/?\s*[\d.]*\)/gi, '$1');

        // Build local var map from this CSS text (for vars defined here but not yet parsed)
        var localMap = {};
        var vr = /(--[\w-]+)\s*:\s*([^;]+)/g, vm;
        while ((vm = vr.exec(css)) !== null) {
          var lv = vm[2].trim();
          if (lv.endsWith('!important')) lv = lv.slice(0, -10).trim();
          localMap[vm[1].trim()] = lv;
        }

        // Get computed :root for globally-set variables
        var root = getComputedStyle(document.documentElement);

        // Replace color-mix(…) with actual computed colour
        var result = '', i = 0;
        while (i < css.length) {
          var idx = css.indexOf('color-mix(', i);
          if (idx < 0) { result += css.slice(i); break; }
          result += css.slice(i, idx);
          var depth = 1, j = idx + 10;
          while (j < css.length && depth > 0) { if (css[j] === '(') depth++; else if (css[j] === ')') depth--; j++; }
          var body = css.slice(idx + 10, j - 1);
          var parts = [], d = 0, s = 0;
          for (var k = 0; k < body.length; k++) {
            if (body[k] === '(') d++; else if (body[k] === ')') d--;
            else if (body[k] === ',' && d === 0) { parts.push(body.slice(s, k).trim()); s = k + 1; }
          }
          parts.push(body.slice(s).trim());
          if (parts.length < 3) { result += 'transparent'; i = j; continue; }

          function pp(s) { var m = s.match(/^(.+?)\s+(\d+(?:\.\d+)?)\s*%$/); return m ? { c: m[1].trim(), p: parseFloat(m[2]) } : { c: s.trim(), p: -1 }; }
          var a = pp(parts[1]), b = pp(parts[2]);
          if (b.p < 0) b.p = Math.max(0, 100 - a.p);

          // Resolve and compute actual colour!
          var ca = resolveColour(a.c, root, localMap), cb = resolveColour(b.c, root, localMap);
          var mixed = mix(ca, cb, a.p, b.p);
          if (mixed) {
            result += rgbaToStr(mixed);
          } else {
            // Fallback: pick best
            result += (b.p > a.p && !/^transparent$/i.test(b.c)) ? b.c : (a.c || 'transparent');
          }
          i = j;
        }
        return result;
      }

      // Hook textContent
      var nd = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent');
      if (nd && nd.set) {
        var origTC = nd.set;
        Object.defineProperty(HTMLStyleElement.prototype, 'textContent', {
          get: nd.get, configurable: true, enumerable: true,
          set: function(v) { if (typeof v === 'string' && /color-mix|backdrop-filter|oklch/i.test(v)) v = fixCSS(v); return origTC.call(this, v); }
        });
      }
      // Hook innerHTML
      try {
        var hd = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML');
        if (hd && hd.set) {
          var origHTML = hd.set;
          Object.defineProperty(HTMLStyleElement.prototype, 'innerHTML', {
            get: hd.get, configurable: true, enumerable: true,
            set: function(v) { if (typeof v === 'string' && /color-mix|backdrop-filter|oklch/i.test(v)) v = fixCSS(v); return origHTML.call(this, v); }
          });
        }
      } catch(_) {}
      // Hook appendChild / insertBefore
      var origAC = Node.prototype.appendChild;
      Node.prototype.appendChild = function(child) {
        if (child.nodeName === 'STYLE' && child.textContent && !child._st_fix) { child._st_fix = true; child.textContent = fixCSS(child.textContent); }
        if (this.nodeName === 'STYLE' && child.nodeType === 3 && /color-mix|backdrop-filter|oklch/i.test(child.nodeValue||'')) child.nodeValue = fixCSS(child.nodeValue);
        return origAC.call(this, child);
      };
      var origIB = Node.prototype.insertBefore;
      Node.prototype.insertBefore = function(child, ref) {
        if (child.nodeName === 'STYLE' && child.textContent && !child._st_fix) { child._st_fix = true; child.textContent = fixCSS(child.textContent); }
        if (this.nodeName === 'STYLE' && child.nodeType === 3 && /color-mix|backdrop-filter|oklch/i.test(child.nodeValue||'')) child.nodeValue = fixCSS(child.nodeValue);
        return origIB.call(this, child, ref);
      };

      // Hook CSSStyleSheet APIs
      try {
        var origIR = CSSStyleSheet.prototype.insertRule;
        CSSStyleSheet.prototype.insertRule = function(rule, idx) { return origIR.call(this, fixCSS(rule), idx); };
      } catch(_) {}
      try {
        if (CSSStyleSheet.prototype.replace) {
          var origR = CSSStyleSheet.prototype.replace;
          CSSStyleSheet.prototype.replace = function(css) { return origR.call(this, fixCSS(css)); };
        }
        if (CSSStyleSheet.prototype.replaceSync) {
          var origRS = CSSStyleSheet.prototype.replaceSync;
          CSSStyleSheet.prototype.replaceSync = function(css) { return origRS.call(this, fixCSS(css)); };
        }
      } catch(_) {}

      // Fix existing <style> tags (remove+reinsert forces re-parse)
      var styles = document.querySelectorAll('style');
      for (var si = 0; si < styles.length; si++) {
        var st = styles[si];
        if (st.textContent && /color-mix|backdrop-filter|oklch/i.test(st.textContent)) {
          var p = st.parentNode; if (!p) continue;
          var n = st.nextSibling;
          st.textContent = fixCSS(st.textContent);
          p.removeChild(st);
          if (n) p.insertBefore(st, n); else p.appendChild(st);
        }
      }
      console.log('[st-ctrl] CSS polyfill ready, scanned ' + styles.length + ' style tags');
      setTimeout(function() {
        console.log('[st-ctrl] setTimeout fired');
        var found = false;
        for (var si2 = 0; si2 < document.styleSheets.length; si2++) {
          try {
            var ss = document.styleSheets[si2];
            if (!ss.cssRules) continue;
            for (var ri = 0; ri < ss.cssRules.length; ri++) {
              var r = ss.cssRules[ri];
              if (r.selectorText && r.selectorText.indexOf('TH-custom') >= 0) {
                console.log('[st-ctrl] Found TH-custom rule: ' + r.selectorText + ' | ' + r.cssText.slice(0, 150));
                found = true;
              }
            }
          } catch(e) {}
        }
        if (!found) console.log('[st-ctrl] No TH-custom-tailwind rule found in any stylesheet');
      }, 5000);
    })();

    // Check if dvw/dvh polyfill is needed (don't exit all compat fixes!)
    var needsDvwPolyfill = true;
    if (typeof CSS !== 'undefined' && CSS.supports) {
      try { if (CSS.supports('width', '1dvw')) needsDvwPolyfill = false; } catch (_) {}
    }

    // ES2022 shim
    if (!Array.prototype.at) {
      Array.prototype.at = function(n) { return n >= 0 ? this[n] : this[this.length + n]; };
    }

    // Phase 0: Tailwind compat variables
    var twStyle = document.createElement('style');
    twStyle.id = 'tavern-tw-compat';
    twStyle.textContent =
      '#rm_extensions_block .inline-drawer { width:100%!important; overflow-x:hidden!important; } ' +
      '#rm_extensions_block .inline-drawer-content { width:100%!important; overflow-x:hidden!important; } ' +
      /* Constrain popup/tooltip descriptions within viewport */
      '.TH-custom-tailwind .vfm__container,.TH-custom-tailwind [class*="popup"],.TH-custom-tailwind [class*="tooltip"],.TH-custom-tailwind [class*="dialog"]{max-width:90vw!important;max-height:80vh!important;overflow-y:auto!important;word-wrap:break-word!important;}' +
      'fieldset.TH-rounded-md { min-width:0!important; flex-shrink:1!important; }';
    document.head.appendChild(twStyle);

    // Phase 1: dvw/dvh stylesheet scan
    var out = '';
    if (needsDvwPolyfill) {
    try {
      for (var i = 0; i < document.styleSheets.length; i++) {
        var sheet = document.styleSheets[i];
        var rules;
        try { rules = sheet.cssRules; } catch (e) { continue; }
        if (!rules) continue;
        for (var j = 0; j < rules.length; j++) {
          (function process(rule, media) {
            if (rule.type === 4) { // MEDIA_RULE
              for (var k = 0; k < rule.cssRules.length; k++) process(rule.cssRules[k], rule.conditionText);
            } else if (rule.type === 1) { // STYLE_RULE
              var t = rule.cssText || '';
              if (!/dvw|dvh/i.test(t)) return;
              var rw = t.replace(/(\d+(?:\.\d+)?)dvw/gi, 'calc(var(--tavern-dvw-px) * $1 / 100)')
                .replace(/(\d+(?:\.\d+)?)dvh/gi, 'calc(var(--tavern-dvh-px) * $1 / 100)');
              if (media) rw = '@media ' + media + ' { ' + rw + ' } ';
              out += rw;
            }
          })(rules[j], null);
        }
      }
    } catch (e) {}
    }

    // Phase 2: manual layout fixes
    out += ' @media screen and (max-width:1000px) { ' +
      ':root { --sheldWidth:100vw!important; } ' +
      '#sheld { height:calc(var(--tavern-dvh-px) - var(--topBarBlockSize) - 1px)!important; } ' +
      '#left-nav-panel,#right-nav-panel,#floatingPrompt,#cfgConfig,#logprobsViewer,#movingDivs>div,' +
      '#character_popup,#dialogue_popup,#completion_prompt_manager_popup {' +
      'min-width:100vw!important;width:100vw!important;max-width:100vw!important;' +
      'left:0!important;box-sizing:border-box!important;' +
      'border-left:none!important;border-right:none!important;}' +
      '.drawer-content { min-width:100vw!important;width:100vw!important;max-width:100vw!important;}' +
      '.drawer-content.openDrawer,#left-nav-panel.openDrawer,#right-nav-panel.openDrawer {' +
      'min-height:calc(var(--tavern-dvh-px) - var(--topBarBlockSize))!important;}' +
      '#top-settings-holder,#form_sheld { border-top:none!important;border-bottom:none!important;}' +
      '.TH-render pre.hidden\\!, .TH-render pre[class*="hidden"],' +
      '.TH-render .hidden\\!, .TH-render [class*="hidden"] { display:none!important;}' +
      '}';

    // Compensate for missing backdrop-filter only where it is truly unsupported.
    if (!supportsBackdropFilter) {
      out += ' @media screen and (max-width:1000px) { ' +
        '.drawer-content,#floatingPrompt,#cfgConfig,#logprobsViewer,' +
        '#character_popup,#dialogue_popup,#completion_prompt_manager_popup,' +
        '#options,#extensionsMenu {' +
        'backdrop-filter:none!important;-webkit-backdrop-filter:none!important;}' +
        '#sheld,#top-bar,#form_sheld,#chat{' +
        'border:none!important;box-shadow:none!important;outline:none!important;}' +
        '}';
    }

    var fb = document.createElement('style');
    fb.id = 'tavern-dvw-fallback';
    fb.textContent = out;
    document.head.appendChild(fb);

    // Phase 3: dynamic viewport variables
    var d = document.documentElement;
    function refresh() {
      var dvh = window.visualViewport ? window.visualViewport.height : window.innerHeight;
      var dvw = window.visualViewport ? window.visualViewport.width : window.innerWidth;
      d.style.setProperty('--tavern-dvh-px', dvh + 'px');
      d.style.setProperty('--tavern-dvw-px', dvw + 'px');
    }
    refresh();
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', refresh);
      window.visualViewport.addEventListener('scroll', refresh);
    }
    window.addEventListener('resize', refresh);

    // Phase 4: panel + checkbox + sweep fixes
    function setInline(el, props) {
      for (var k in props) { el.style.setProperty(k, props[k], 'important'); }
    }
    function fixPanelSize(el) {
      if (el.offsetWidth > 200) {
        setInline(el, {
          minWidth: '100vw', width: '100vw', maxWidth: '100vw',
          left: '0px', right: '0px', boxSizing: 'border-box',
          minHeight: 'calc(var(--tavern-dvh-px) - var(--topBarBlockSize))'
        });
      }
    }
    document.querySelectorAll('.drawer-content,[id$="_popup"],[id$="_block"]').forEach(fixPanelSize);
    var sheld = document.getElementById('sheld');
    if (sheld) sheld.style.setProperty('height',
      'calc(var(--tavern-dvh-px) - var(--topBarBlockSize) - 1px)', 'important');
    ['top-settings-holder', 'form_sheld'].forEach(function(id) {
      var el = document.getElementById(id);
      if (el) setInline(el, { borderTop: 'none', borderBottom: 'none' });
    });

    // Checkbox fix
    var cbFix = document.createElement('style');
    cbFix.id = 'tavern-cb-fix';
    document.head.appendChild(cbFix);


    // Sweep for late panels
    function sweepPanels() {
      var vw = window.innerWidth;
      document.querySelectorAll('.drawer-content,[id$="_popup"],[id$="_block"]').forEach(function(el) {
        if (el.offsetWidth > vw || el.scrollWidth > vw) {
          setInline(el, {
            minWidth: '100vw', width: '100vw', maxWidth: '100vw',
            left: '0px', right: '0px', boxSizing: 'border-box',
            minHeight: 'calc(var(--tavern-dvh-px) - var(--topBarBlockSize))'
          });
        }
      });
      var rm = document.getElementById('rm_extensions_block');
      if (rm) {
        rm.style.setProperty('overflow-x', 'hidden', 'important');
        rm.style.setProperty('width', '100%', 'important');
        rm.style.setProperty('max-width', '100%', 'important');
        rm.querySelectorAll('*').forEach(function(child) {
          if (child.scrollWidth > child.offsetWidth || child.offsetWidth > 340) {
            child.style.setProperty('overflow-x', 'hidden', 'important');
            child.style.setProperty('max-width', '100%', 'important');
            child.style.setProperty('word-break', 'break-all', 'important');
          }
        });
      }
    }
    sweepPanels();
    var sweepCount = 0;
    var sweepTimer = setInterval(function() {
      sweepCount++;
      sweepPanels();
      if (sweepCount >= 10) clearInterval(sweepTimer);
    }, 1200);

    console.log('[st-ctrl] compat polyfill done, ' + out.length + ' chars');
  })();

  // ═══ Prevent auto-focus/auto-keyboard on page load ═══
  // Only allow focus when user explicitly taps/clicks an input
  (function() {
    var userInteracted = false;
    document.addEventListener('touchstart', function() { userInteracted = true; }, { once: false, passive: true });
    document.addEventListener('mousedown', function() { userInteracted = true; }, { once: false, passive: true });
    document.addEventListener('focusin', function(e) {
      if (!userInteracted && /input|textarea|select/i.test(e.target.tagName)) {
        e.target.blur();
      }
    });
  })();

  // ═══ Post-load utilities ═══

  // Prevent black area when keyboard dismisses — keep body height >= viewport
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', function() {
      document.body.style.minHeight = window.visualViewport.height + 'px';
    });
  }

  // Track all rAF ids so pauseRendering can cancel ALL pending animation frames
  var _origRAF = window.requestAnimationFrame;
  window.__tavernAnimIds = [];
  window.requestAnimationFrame = function(cb) {
    var id = _origRAF.call(window, cb);
    window.__tavernAnimIds.push(id);
    return id;
  };

  // ═══ Blob Export Hook ═══
  // SillyTavern exports via: blob → createObjectURL → <a>.click().
  // We stash blobs when they're created, then read directly from stash
  // in the click handler. FileReader → AndroidBridge.saveBytes → SAF picker.
  // No DownloadListener involved — SAF-based export.
  (function(){
    var _st_blobs = {};

    var _origCreate = URL.createObjectURL;
    URL.createObjectURL = function(blob) {
      var url = _origCreate.call(URL, blob);
      _st_blobs[url] = blob;
      return url;
    };

    var _origRevoke = URL.revokeObjectURL;
    URL.revokeObjectURL = function(url) {
      // Delay cleanup — keep blob reference alive until click handler runs
      setTimeout(function(){ delete _st_blobs[url]; }, 30000);
      _origRevoke.call(URL, url);
    };

    var _origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
      var href = this.getAttribute('href') || '';
      if (!href.startsWith('blob:')) return _origClick.call(this);

      var blob = _st_blobs[href];
      if (!blob) return _origClick.call(this); // fallback to native

      var name = this.getAttribute('download') || ('tavern-export-' + Date.now());
      var mime = blob.type || 'application/octet-stream';

      // Read blob directly from stash — no fetch, no re-acquire
      var r = new FileReader();
      r.onloadend = function() {
        try { window.AndroidBridge.saveBytes(r.result, mime, name); } catch(e) {}
      };
      r.readAsDataURL(blob);
    };
  })();
})();
