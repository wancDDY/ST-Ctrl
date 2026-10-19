/**
 * ST-Ctrl mobile injection script.
 * Injected into every tavern page load by TavernWebView.injectCSS().
 *
 * Contains:
 *   - Mobile-friendly CSS overrides
 *   - Compat mode polyfills (dvw/dvh, ES2022 shims, layout fixes)
 *   - Keyboard viewport fix
 *   - rAF tracking for pauseRendering()
 */
(function() {
  if (document.getElementById('tavern-mobile-css')) return;

  var stPerfFactor = 1;
  try {
    if (window.AndroidBridge && AndroidBridge.perfFactor) {
      var parsedFactor = parseFloat(AndroidBridge.perfFactor());
      if (isFinite(parsedFactor) && parsedFactor > 0) stPerfFactor = parsedFactor;
    }
  } catch (e) {}
  window.__stctrlPerfFactor = Math.min(1, stPerfFactor);
  function tavernPerfAnimEnabled() {
    try {
      if (window.AndroidBridge && AndroidBridge.tavernOpt) {
        return AndroidBridge.tavernOpt('perf_animation') !== '0';
      }
    } catch (e) {}
    return true;
  }

  // Base CSS (always applied)
  var s = document.createElement('style');
  s.id = 'tavern-mobile-css';
  s.textContent = `
    *{-webkit-tap-highlight-color:transparent;touch-action:manipulation}
    body{overscroll-behavior-y:contain;background:#16150f!important}
    :root{--tw-ring-inset: ;}

    /* Rendering optimization: contain layout/style scope */
    #chat .mes, #chat [class*="mes"], #chat [class*="message"],
    .chat-content > *, #chat-content > * {
      contain:layout style;
    }

    /* Reduce paint area for frequently-updated elements */
    #chat, .chat-content, #chat-content {
      contain:layout style;
    }

    /* The active-world picker: a native multi-select that the core's mobile stylesheet caps at
       50px (about two rows), and whose rows the platform paints with colours that ignore the
       theme. Wider, tall enough for the rows the script below asks for, and both row states are
       coloured from the theme's own values. The important flag is required: the browser's own
       stylesheet marks option colours important, so an author rule without it is dropped. */
    #world_info { width: 100% !important; max-height: 40vh !important; }
    #world_info option {
      background-color: transparent !important;
      color: var(--SmartThemeBodyColor, #e8e8e8) !important;
    }
    #world_info option:checked {
      background-color: color-mix(in srgb, var(--SmartThemeQuoteColor, #86b288) 30%, transparent) !important;
      color: var(--SmartThemeBodyColor, #e8e8e8) !important;
    }

    /* Drawer panel fade-in transition */
    .drawer-content {
      animation: drawer-fade-in 0.18s ease-out both;
    }
    @keyframes drawer-fade-in {
      from { opacity: 0; transform: translateY(6px); }
      to   { opacity: 1; transform: translateY(0); }
    }

    /* Faster animations for the mobile shell */
    :root { --animation-duration: 125ms; }
    html[data-st-perf="save"][data-st-anim="on"] .drawer-content,
    html[data-st-perf="save"][data-st-anim="on"] [class*="slide"] {
      animation: none !important;
    }
    html[data-st-perf="save"][data-st-anim="on"] .mes {
      transition: none !important;
    }

    /* Native textarea auto-height on Chrome 123+ */
    #send_textarea { field-sizing: content; min-height: 44px; max-height: 50vh; }

    /* Mobile fixed body, matching SillyTavern's own mobile styles.
       body is 100dvh; #sheld/#chat flow naturally inside it, and native
       adjustResize shrinks the WebView so the send bar follows the keyboard
       with zero JS delay. No --doc-height override; that stale JS-driven
       height caused the input bar to lag behind the keyboard and leave a
       black gap on dismiss. */
    :root { --topBarBlockSize: 40px; }

    #stctrl-fullscreen-button {
      display:inline-flex!important;
      align-items:center!important;
      justify-content:center!important;
      width:2em!important;
      height:2em!important;
      min-width:2em!important;
      flex:0 0 auto!important;
      opacity:1!important;
      filter:none!important;
      color:inherit!important;
      background:transparent!important;
      border:0!important;
      border-radius:0!important;
      box-shadow:none!important;
      padding:0!important;
      outline:none!important;
      appearance:none!important;
      -webkit-appearance:none!important;
      margin:0 2px!important;
    }
    #stctrl-fullscreen-button>i {
      opacity:1!important;
      line-height:1!important;
      font-size:1.15em!important;
    }
  `;
  document.head.appendChild(s);

    // Native fullscreen toggle beside the recent-chat settings button.
  (function() {
    var BUTTON_ID = 'stctrl-fullscreen-button';
    var installScheduled = false;

    function syncButton(button, enabled) {
      var icon = button.querySelector('i,.drawer-icon');
      if (!icon) return;
      icon.classList.toggle('fa-expand', !enabled);
      icon.classList.toggle('fa-compress', enabled);
      icon.title = enabled ? '退出全屏' : '进入全屏';
    }

    function captureChatScroll() {
      var chat = document.getElementById('chat');
      if (!chat) return null;
      var chatRect = chat.getBoundingClientRect();
      var anchor = {
        messageId: null,
        offset: 0,
        scrollTop: chat.scrollTop,
      };
      var hit = document.elementFromPoint(
        chatRect.left + chatRect.width / 2,
        chatRect.top + 4
      );
      var element = hit && hit.closest ? hit.closest('.mes') : null;
      if (element && chat.contains(element)) {
        anchor.messageId = element.getAttribute('mesid');
        anchor.offset = element.getBoundingClientRect().top - chatRect.top;
      }
      return anchor;
    }

    function restoreChatScroll(anchor) {
      if (!anchor) return;
      var chat = document.getElementById('chat');
      if (!chat || !chat.isConnected) return;
      var target;
      if (anchor.messageId !== null) {
        var element = chat.querySelector('.mes[mesid="' + anchor.messageId + '"]');
        if (element) {
          var chatRect = chat.getBoundingClientRect();
          var currentOffset = element.getBoundingClientRect().top - chatRect.top;
          target = chat.scrollTop + currentOffset - anchor.offset;
        } else {
          target = anchor.scrollTop;
        }
      } else {
        target = anchor.scrollTop;
      }
      animateChatScroll(chat, target);
    }

    function animateChatScroll(chat, target) {
      if (chat.__stctrlFullscreenScrollRaf) {
        cancelAnimationFrame(chat.__stctrlFullscreenScrollRaf);
      }
      var start = chat.scrollTop;
      var distance = target - start;
      if (Math.abs(distance) < 0.5) {
        chat.__stctrlFullscreenScrollRaf = null;
        return;
      }
      var startedAt = performance.now();
      var duration = 180;
      function step(now) {
        var progress = Math.min(1, (now - startedAt) / duration);
        var eased = 1 - Math.pow(1 - progress, 3);
        chat.scrollTop = start + distance * eased;
        if (progress < 1) {
          chat.__stctrlFullscreenScrollRaf = requestAnimationFrame(step);
        } else {
          chat.__stctrlFullscreenScrollRaf = null;
        }
      }
      chat.__stctrlFullscreenScrollRaf = requestAnimationFrame(step);
    }

    function scheduleChatScrollRestore(anchor) {
      if (!anchor) return;
      var cancelled = false;
      var cancel = function() {
        cancelled = true;
        var chat = document.getElementById('chat');
        if (chat && chat.__stctrlFullscreenScrollRaf) {
          cancelAnimationFrame(chat.__stctrlFullscreenScrollRaf);
          chat.__stctrlFullscreenScrollRaf = null;
        }
      };
      document.addEventListener('touchstart', cancel, { capture: true, once: true });
      [60, 180, 420, 760].forEach(function(delay) {
        setTimeout(function() {
          if (!cancelled) restoreChatScroll(anchor);
        }, delay);
      });
      setTimeout(function() {
        document.removeEventListener('touchstart', cancel, true);
      }, 900);
    }

    function installButton() {
      var settings = document.querySelector('.recentChatsSettings');
      if (!settings || !settings.parentElement) return false;

      var existing = document.getElementById(BUTTON_ID);
      if (existing && existing.parentElement === settings.parentElement) return true;
      if (existing) existing.remove();

      var button = document.createElement('button');
      button.type = 'button';
      button.id = BUTTON_ID;
      button.className = 'mes_button';
      button.title = '进入全屏';
      button.setAttribute('role', 'button');

      var icon = document.createElement('i');
      icon.className = 'fa-solid fa-expand fa-fw fa-lg';

      button.appendChild(icon);
      button.addEventListener('click', function(event) {
        event.preventDefault();
        event.stopPropagation();
        var scrollAnchor = captureChatScroll();
        var state = '0';
        try {
          if (window.AndroidBridge && AndroidBridge.toggleFullscreen) {
            state = AndroidBridge.toggleFullscreen();
          }
        } catch (e) {}
        syncButton(button, state === '1');
        scheduleChatScrollRestore(scrollAnchor);
      }, true);

      settings.parentElement.insertBefore(button, settings);
      var enabled = false;
      try {
        if (window.AndroidBridge && AndroidBridge.isFullscreen) {
          enabled = AndroidBridge.isFullscreen() === '1';
        }
      } catch (e) {}
      syncButton(button, enabled);
      return true;
    }

    function scheduleInstall() {
      if (installScheduled) return;
      installScheduled = true;
      var run = function() {
        installScheduled = false;
        installButton();
      };
      if (window.requestAnimationFrame) {
        requestAnimationFrame(run);
      } else {
        setTimeout(run, 50);
      }
    }

    installButton();
    new MutationObserver(scheduleInstall).observe(document.documentElement, {
      childList: true,
      subtree: true
    });
    setInterval(installButton, 2000);
  })();

  function applyPerfProfile() {
    var f = window.__stctrlPerfFactor || 1;
    var level = f <= 0.25 ? 'save' : f < 0.5 ? 'light' : f < 0.75 ? 'balanced' : 'max';
    document.documentElement.setAttribute('data-st-perf', level);
    var animOpt = tavernPerfAnimEnabled();
    document.documentElement.setAttribute('data-st-anim', animOpt ? 'on' : 'off');
    window.__stctrlTimerThrottle = animOpt && f < 0.25;
    window.__stctrlThrottleFactor = 2;
  }
  window.__stctrlPerfApply = applyPerfProfile;
  applyPerfProfile();

  // Blob exports can run in the main document or in a srcdoc iframe. Install
  // the same bridge in both so anchor.click() always reaches Android.
  function installExportBridge(win) {
    if (!win || win.__stctrlExportBridgeInstalled) return;
    var urlApi = win.URL;
    var anchorProto = win.HTMLAnchorElement && win.HTMLAnchorElement.prototype;
    if (!urlApi || !anchorProto) return;
    win.__stctrlExportBridgeInstalled = true;

    var blobs = {};
    var origCreate = urlApi.createObjectURL;
    urlApi.createObjectURL = function(blob) {
      var url = origCreate.call(urlApi, blob);
      blobs[url] = blob;
      return url;
    };

    var origRevoke = urlApi.revokeObjectURL;
    urlApi.revokeObjectURL = function(url) {
      // Keep the Blob reference until the asynchronous FileReader finishes.
      win.setTimeout(function() { delete blobs[url]; }, 30000);
      try { origRevoke.call(urlApi, url); } catch (e) {}
    };

    function sendChunk(dataUrl, mime, name, index, total) {
      try {
        if (window.AndroidBridge && AndroidBridge.saveChunk) {
          AndroidBridge.saveChunk(dataUrl, mime, name, index, total);
          return true;
        }
      } catch (e) {}
      return false;
    }

    var origClick = anchorProto.click;
    anchorProto.click = function() {
      var href = this.getAttribute('href') || '';
      if (!href.startsWith('blob:')) return origClick.call(this);
      var blob = blobs[href];
      if (!blob) return origClick.call(this);

      var name = this.getAttribute('download') || ('tavern-export-' + Date.now());
      var mime = blob.type || 'application/octet-stream';
      var CHUNK = 256 * 1024;
      var pos = 0;
      var index = 0;
      var total = Math.max(1, Math.ceil(blob.size / CHUNK));
      var Reader = win.FileReader;

      function sendNext() {
        if (pos >= blob.size && index >= total) return;
        var end = Math.min(pos + CHUNK, blob.size);
        var part = blob.slice(pos, end);
        var reader = new Reader();
        reader.onloadend = function() {
          if (reader.result) sendChunk(reader.result, mime, name, index, total);
          pos = end;
          index++;
          if (pos < blob.size) sendNext();
        };
        reader.onerror = function() {
          try { window.AndroidBridge.saveBytes('', mime, name); } catch (e) {}
        };
        reader.readAsDataURL(part);
      }
      sendNext();
    };
  }
  installExportBridge(window);

  // SRCDOC frames have their own URL/anchor prototypes. Scan them independently
  // of compatibility mode so exports keep working on modern WebViews too.
  (function() {
    var scanScheduled = false;

    function installFrameBridge(frame) {
      if (!frame) return;
      try {
        installExportBridge(frame.contentWindow);
      } catch (e) {}
    }

    function scanFrameBridges() {
      scanScheduled = false;
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) installFrameBridge(frames[i]);
    }

    function scheduleFrameBridgeScan() {
      if (scanScheduled) return;
      scanScheduled = true;
      if (window.requestIdleCallback) {
        window.requestIdleCallback(scanFrameBridges, { timeout: 300 });
      } else {
        setTimeout(scanFrameBridges, 60);
      }
    }

    document.addEventListener('load', function(event) {
      if (event.target && event.target.nodeName === 'IFRAME') scheduleFrameBridgeScan();
    }, true);

    new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var nodes = mutations[i].addedNodes;
        for (var j = 0; j < nodes.length; j++) {
          var node = nodes[j];
          if (node.nodeType !== 1) continue;
          if (node.nodeName === 'IFRAME' ||
              (node.querySelector && node.querySelector('iframe'))) {
            scheduleFrameBridgeScan();
            return;
          }
        }
      }
    }).observe(document.documentElement, { childList: true, subtree: true });

    scanFrameBridges();
  })();

  // APIs the bundled front-ends call without a guard. Written as one installer because the
  // front-end cards render inside frames: those are separate realms, so a shim installed in
  // the page never reaches them, and this installer is stringified into the frame markup.
  // Must run with compat mode off too: the page itself needs the shims regardless.
  function installMissingApis(scope) {
    // Installing by assignment defines an enumerable property, and `for (const key in array)`
    // then hands out the method's own name where the caller expects an element: that is how the
    // prompt-template extension came to ask Array.prototype.toSorted for `includes`. The core's
    // own polyfills have the same defect, so the descriptor is repaired when it is found
    // enumerable, not only when the API is missing. Native methods are non-enumerable.
    function defineShim(target, name, value) {
      var existing = Object.getOwnPropertyDescriptor(target, name);
      if (existing && !existing.configurable) return;
      if (existing && existing.enumerable === false) return;
      if (existing && (existing.get || existing.set)) return;
      try {
        Object.defineProperty(target, name, {
          value: existing ? existing.value : value,
          writable: true,
          configurable: true,
          enumerable: false
        });
      } catch (error) {
        // A host object can refuse redefinition; a plain assignment still beats no shim at all.
        target[name] = value;
      }
    }
    defineShim(Array.prototype, 'at', function(n) { return n >= 0 ? this[n] : this[this.length + n]; });
    defineShim(String.prototype, 'at', function(n) {
      var index = n >= 0 ? n : this.length + n;
      return index < 0 || index >= this.length ? undefined : this.charAt(index);
    });
    var typedArrayNames = ['Int8Array','Uint8Array','Uint8ClampedArray','Int16Array','Uint16Array','Int32Array','Uint32Array','Float32Array','Float64Array','BigInt64Array','BigUint64Array'];
    for (var ta = 0; ta < typedArrayNames.length; ta++) {
      var TypedArr = scope[typedArrayNames[ta]];
      if (TypedArr) {
        defineShim(TypedArr.prototype, 'at', function(n) { return n >= 0 ? this[n] : this[this.length + n]; });
      }
    }
    defineShim(Object, 'hasOwn', function(obj, key) { return Object.prototype.hasOwnProperty.call(obj, key); });
    // findLast and findLastIndex share one scan so the two cannot drift apart.
    function scanBackwards(list, test, thisArg) {
      var i = list.length;
      while (i-- > 0) {
        if (test.call(thisArg, list[i], i, list)) return i;
      }
      return -1;
    }
    defineShim(Array.prototype, 'findLast', function(fn, thisArg) {
      var at = scanBackwards(this, fn, thisArg);
      return at < 0 ? undefined : this[at];
    });
    defineShim(Array.prototype, 'findLastIndex', function(fn, thisArg) { return scanBackwards(this, fn, thisArg); });
    // Explicit copy rather than slice(): the copy methods read every index, so a
    // hole becomes undefined the way the native implementation does.
    defineShim(Array.prototype, 'toReversed', function() {
      var out = [];
      for (var i = this.length - 1; i >= 0; i--) out.push(this[i]);
      return out;
    });
    defineShim(Array.prototype, 'toSorted', function(compareFn) {
      var out = [];
      for (var i = 0; i < this.length; i++) out.push(this[i]);
      out.sort(compareFn);
      return out;
    });
    defineShim(Array.prototype, 'toSpliced', function(start, deleteCount) {
      var copy = this.slice();
      copy.splice.apply(copy, arguments);
      return copy;
    });
    defineShim(Array.prototype, 'with', function(index, value) {
      var copy = this.slice();
      copy[index < 0 ? copy.length + index : index] = value;
      return copy;
    });
    defineShim(Promise, 'withResolvers', function() {
      var resolve, reject;
      var promise = new Promise(function(res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    });
    defineShim(scope, 'structuredClone', function(value) {
        if (value == null || typeof value !== 'object') return value;
        if (value instanceof Date) return new Date(value.getTime());
        if (value instanceof RegExp) return new RegExp(value.source, value.flags);
        if (value instanceof ArrayBuffer) return value.slice(0);
        if (ArrayBuffer.isView(value)) return new value.constructor(value);
        if (value instanceof Map) {
          return new Map(Array.from(value, function(entry) {
            return [scope.structuredClone(entry[0]), scope.structuredClone(entry[1])];
          }));
        }
        if (value instanceof Set) {
          return new Set(Array.from(value, function(item) { return scope.structuredClone(item); }));
        }
        if (Array.isArray(value)) return value.map(function(item) { return scope.structuredClone(item); });
        var out = {};
        for (var key in value) {
          if (Object.prototype.hasOwnProperty.call(value, key)) {
            out[key] = scope.structuredClone(value[key]);
          }
        }
        return out;
      });
    if (scope.crypto && scope.crypto.getRandomValues) {
      defineShim(scope.crypto, 'randomUUID', function() {
        var bytes = scope.crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        var hex = Array.from(bytes, function(b) { return b.toString(16).padStart(2, '0'); }).join('');
        return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
      });
    }
    if (scope.AbortSignal && scope.AbortController) {
      defineShim(scope.AbortSignal, 'timeout', function(ms) {
        var controller = new scope.AbortController();
        setTimeout(function() {
          controller.abort(new DOMException('TimeoutError', 'TimeoutError'));
        }, ms);
        return controller.signal;
      });
    }
  }
  installMissingApis(window);

  // The active-world picker is a multi-select whose height the core's mobile stylesheet caps at
  // two rows, so finding a world book means scrolling a 50px window. Asking for the rows through
  // `size` raises it without editing the core's stylesheet.
  (function() {
    var WB_SEL = '#world_info';
    function sizeSelects() {
      document.querySelectorAll(WB_SEL).forEach(function(sel) {
        var count = sel.options.length;
        if (count > 1) sel.size = Math.min(count, 7);
      });
    }
    sizeSelects();
    new MutationObserver(sizeSelects).observe(document.body, { childList: true, subtree: true });
  })();

  // Old WebViews can keep a stale calc(var()) value after the slider writes
  // only --blurStrength. Mirror it directly to the concrete blur variable.
  (function() {
    try {
      if (!window.AndroidBridge || AndroidBridge.compatModeEnabled() !== '1') return;
    } catch (e) { return; }
    var BLUR_ID = 'blur_strength';
    function syncBlurStrength() {
      var el = document.getElementById(BLUR_ID);
      if (!el) return;
      var value = Number(el.value);
      if (Number.isFinite(value)) {
        var root = document.documentElement;
        var next = value + 'px';
        if (root.style.getPropertyValue('--SmartThemeBlurStrength') !== next) {
          root.style.setProperty('--SmartThemeBlurStrength', next);
        }
      }
    }
    function handleBlurInput(event) {
      var target = event && event.target;
      if (target && (target.id === BLUR_ID ||
          (target.getAttribute && target.getAttribute('data-for') === BLUR_ID))) {
        setTimeout(syncBlurStrength, 0);
      }
    }
    document.addEventListener('input', handleBlurInput, true);
    document.addEventListener('change', handleBlurInput, true);
    new MutationObserver(syncBlurStrength).observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['style']
    });
    setTimeout(syncBlurStrength, 0);
  })();

  // Mobile input & keyboard optimizations
  (function() {
    var isMobile = /Mobi|Android/i.test(navigator.userAgent);
    if (!isMobile) return;

    var CHAT_ID = '#chat';
    var TEXTAREA_ID = '#send_textarea';

    // 2. Enter = newline on mobile
    document.addEventListener('keydown', function(e) {
      var t = e.target;
      if (!(t instanceof HTMLTextAreaElement) || t.id !== 'send_textarea') return;
      if (e.isComposing || e.shiftKey || e.ctrlKey || e.altKey) return;
      if (e.key !== 'Enter') return;
      e.stopImmediatePropagation();
    }, true);

    // 3. (removed) Keyboard viewport fix via --doc-height
    // Native adjustResize (AndroidManifest) already shrinks the WebView so the
    // send bar follows the keyboard with no JS delay. Driving #sheld/#chat off
    // a JS-measured --doc-height lagged behind the keyboard (left a black gap
    // on dismiss and the input bar resolved late). Removed in favor of the
    // browser's natural 100dvh + adjustResize layout.

    // 4. ResizeObserver: keep chat visible when textarea grows
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

    // Shared chat scroll hub: one passive listener feeds all mobile chat features.
    (function() {
      var attached = null;
      var listeners = [];
      var frame = null;
      var lastTop = null;

      function flush() {
        frame = null;
        if (!attached || !attached.isConnected) return;
        var top = attached.scrollTop;
        var delta = lastTop === null ? 0 : Math.abs(top - lastTop);
        var now = performance.now();
        lastTop = top;
        for (var i = 0; i < listeners.length; i++) {
          try { listeners[i]({ top: top, delta: delta, time: now }); } catch (_) {}
        }
      }

      function onScroll() {
        if (frame) return;
        frame = requestAnimationFrame(flush);
      }

      function attach() {
        var chat = document.getElementById('chat');
        if (chat === attached) return;
        if (attached) attached.removeEventListener('scroll', onScroll);
        attached = chat;
        lastTop = attached ? attached.scrollTop : null;
        if (attached) attached.addEventListener('scroll', onScroll, { passive: true });
      }

      window.__stctrlChatScroll = {
        subscribe: function(listener) {
          if (typeof listener === 'function' && listeners.indexOf(listener) < 0) listeners.push(listener);
          attach();
        },
        unsubscribe: function(listener) {
          var index = listeners.indexOf(listener);
          if (index >= 0) listeners.splice(index, 1);
        },
        getElement: function() {
          attach();
          return attached;
        }
      };

      attach();
      setInterval(attach, 1000);
    })();

    // 6. Swipe guard + long-press
    (function() {
      // 6a. Swipe guard
      var swMinDx = 60, swDxDyRatio = 1.8, swBlockMs = 180, swMeaningful = 8;
      var swLastTs = 0, swLastTop = null, swLastDelta = 0;
      if (window.__stctrlChatScroll) {
        window.__stctrlChatScroll.subscribe(function(scroll) {
          swLastDelta = scroll.delta;
          swLastTop = scroll.top;
          swLastTs = scroll.time;
        });
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

    // 7. Android back-button handler
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

  // Compat mode polyfills
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

    // Front-end cards (TavernHelper's status bars and similar) render inside <iframe srcdoc>
    // documents. Those are separate realms, so the shims installed in this document never reach
    // them: the card scripts are parser-created and ask for APIs an old WebView does not have,
    // which stopped the card half-rendered. Their dependencies are otherwise loaded exactly as
    // upstream loads them, from the card's own CDN.
    //
    // Vue is the exception, because it decides whether a card runs at all: on an old WebView the
    // CDN request for it fails outright (measured: no Vue in any frame), while the core already
    // ships the same build. The pattern is deliberately narrow — only the runtime-only global
    // build of Vue 3, which is what /vue-runtime.js is. A card asking for the full build, an ESM
    // build, or Vue 2 keeps its own URL, because substituting a different build or major version
    // would break it in a way the CDN would not.
    var VUE_RUNTIME_REWRITE =
      [/https?:\/\/[^"'\s>]*jsdelivr\.net\/npm\/vue(?:@3[^/"'\s>]*)?\/dist\/vue\.runtime\.global(?:\.prod)?(?:\.min)?\.js/gi,
       '/vue-runtime.js'];

    function localizeFrameAssets(html) {
      if (typeof html !== 'string' || html.indexOf('jsdelivr.net') < 0) return html;
      return html.replace(VUE_RUNTIME_REWRITE[0], VUE_RUNTIME_REWRITE[1]);
    }

    // Evaluated in the frame realm, where `window` is the frame's own. The shims must be the
    // first script the frame runs: the card scripts that need them are parser-created, so
    // nothing else can get in front of them.
    var FRAME_SHIM_TAG = '<script>(' + installMissingApis.toString() + ')(window)<' + '/script>';

    function injectFrameShims(html) {
      if (html.indexOf('installMissingApis') >= 0) return html;
      var head = /<head[^>]*>/i.exec(html);
      if (head) {
        var at = head.index + head[0].length;
        return html.slice(0, at) + FRAME_SHIM_TAG + html.slice(at);
      }
      var root = /<html[^>]*>/i.exec(html);
      if (root) {
        var rootAt = root.index + root[0].length;
        return html.slice(0, rootAt) + FRAME_SHIM_TAG + html.slice(rootAt);
      }
      var doctype = /<!doctype[^>]*>/i.exec(html);
      if (doctype) {
        var doctypeAt = doctype.index + doctype[0].length;
        return html.slice(0, doctypeAt) + FRAME_SHIM_TAG + html.slice(doctypeAt);
      }
      return FRAME_SHIM_TAG + html;
    }

    function prepareFrameDocument(html) {
      if (typeof html !== 'string') return html;
      return injectFrameShims(localizeFrameAssets(html));
    }

    function installFrameDocumentPreparer() {
      var proto = window.HTMLIFrameElement && window.HTMLIFrameElement.prototype;
      if (!proto) return;
      var descriptor = Object.getOwnPropertyDescriptor(proto, 'srcdoc');
      if (descriptor && descriptor.get && descriptor.set) {
        Object.defineProperty(proto, 'srcdoc', {
          configurable: true,
          enumerable: descriptor.enumerable,
          get: descriptor.get,
          set: function(value) { return descriptor.set.call(this, prepareFrameDocument(value)); }
        });
      }
      var origSetAttribute = proto.setAttribute;
      if (origSetAttribute) {
        proto.setAttribute = function(name, value) {
          if (String(name).toLowerCase() === 'srcdoc') value = prepareFrameDocument(value);
          return origSetAttribute.call(this, name, value);
        };
      }
      // Frames that are already in the document keep the markup they parsed. Re-assigning
      // srcdoc to fix them would reload the card mid-render, so only new writes are rewritten.
    }

    installFrameDocumentPreparer();

    // Polyfill missing CSS rule constructors (old WebView lacks them)
    if (typeof CSSMediaRule === 'undefined') window.CSSMediaRule = function(){};
    if (typeof CSSStyleRule === 'undefined') window.CSSStyleRule = function(){};
    if (typeof CSSKeyframesRule === 'undefined') window.CSSKeyframesRule = function(){};
    if (typeof CSSSupportsRule === 'undefined') window.CSSSupportsRule = function(){};
    if (typeof CSSImportRule === 'undefined') window.CSSImportRule = function(){};
    if (typeof CSSContainerRule === 'undefined') window.CSSContainerRule = function(){};

    // CSS polyfill: intercept <style> tags to fix color-mix/backdrop-filter
    (function() {
      var MODERN_CSS = /color-mix|oklch|backdrop-filter|rgb\(from|cq[a-z]+|@layer|:where|:has\(|@container|::backdrop|dvh|dvw|calc\(\s*(?:-?)infinity|var\(--tw-[\w-]+,\)|in\s+(?:oklab|oklch|lab|lch|xyz)/i;

      // Resolve a CSS colour value (var(--x), rgb(), rgba(), #hex, named) → {r,g,b,a}
      function splitTopLevel(value) {
        var parts = [], depth = 0, start = 0;
        for (var i = 0; i < value.length; i++) {
          if (value[i] === '(') depth++;
          else if (value[i] === ')') depth--;
          else if (value[i] === ',' && depth === 0) {
            parts.push(value.slice(start, i).trim());
            start = i + 1;
          }
        }
        parts.push(value.slice(start).trim());
        return parts;
      }

      // A color-mix weight is a percentage, possibly written through a variable, as in
      // `color-mix(in srgb, var(--yy) var(--opacity), transparent)`. An unreadable weight is a
      // third state rather than an omitted one: guessing an even split there paints a colour
      // the engine this fallback stands in for does not paint at all. An unresolved variable is
      // unreadable even when it carries a fallback, because that fallback is only the value when
      // nothing ever defines the variable: a measured card wrote `var(--dream-st-bg, white)` and
      // had its dark fill frozen into white.
      function colourWeightPercent(expr, lookup, depth) {
        depth = depth || 0;
        var text = String(expr == null ? '' : expr).trim();
        if (!text) return null;
        var literal = text.match(/^(-?\d*\.?\d+)\s*%$/);
        if (literal) return parseFloat(literal[1]);
        if (depth > 12 || !lookup || !/^var\(/i.test(text)) return null;
        var open = text.indexOf('(');
        var close = text.lastIndexOf(')');
        if (open < 0 || close <= open) return null;
        var inner = splitTopLevel(text.slice(open + 1, close));
        var name = (inner.shift() || '').trim();
        var value = lookup(name);
        if (value) return colourWeightPercent(value, lookup, depth + 1);
        return null;
      }

      function parseColourWeight(value, lookup) {
        var s = String(value).trim();
        var match = s.match(/^(.+?)\s+(-?\d*\.?\d+)\s*%$/);
        if (match) return { c: match[1].trim(), p: parseFloat(match[2]) };
        var trailing = s.match(/^(.+?)\s+(var\([\s\S]+\))$/i);
        if (trailing) {
          var percent = colourWeightPercent(trailing[2], lookup, 0);
          return percent === null
            ? { c: trailing[1].trim(), p: -1, weightUnreadable: true }
            : { c: trailing[1].trim(), p: percent };
        }
        return { c: s, p: -1 };
      }

      function parseAlphaValue(value) {
        if (value == null) return 1;
        var text = String(value).trim().replace(/^\//, '');
        if (!text) return 1;
        var parsed = parseFloat(text);
        if (isNaN(parsed)) return 1;
        return text.endsWith('%') ? parsed / 100 : parsed;
      }

      function parseOklchAlpha(args) {
        var match = String(args || '').match(/\/\s*([\d.]+%?)\s*$/);
        return match ? parseAlphaValue(match[1]) : 1;
      }

      function protectSupportsConditions(css) {
        var conditions = [], out = '', i = 0;
        while (i < css.length) {
          var idx = css.indexOf('@supports', i);
          if (idx < 0) { out += css.slice(i); break; }
          out += css.slice(i, idx);
          var pos = idx + '@supports'.length, depth = 0, open = -1;
          for (; pos < css.length; pos++) {
            var ch = css[pos];
            if (ch === '(') depth++;
            else if (ch === ')') depth--;
            else if (ch === '{' && depth === 0) { open = pos; break; }
          }
          if (open < 0) { out += css.slice(idx); break; }
          var key = '__STCTRL_SUPPORTS_' + conditions.length + '__';
          conditions.push(css.slice(idx, open));
          out += key;
          i = open;
        }
        return { css: out, conditions: conditions };
      }

      function restoreSupportsConditions(css, conditions) {
        for (var i = 0; i < conditions.length; i++) {
          css = css.split('__STCTRL_SUPPORTS_' + i + '__').join(conditions[i]);
        }
        return css;
      }

      function convertContainerQueries(css) {
        return css.replace(
          /@container(?:\s+[-\w]+)?\s*\(\s*((?:inline-size|width|min-width|max-width|min-inline-size|max-inline-size)\s*(?::|[<>]=?)\s*[^)]*)\)/gi,
          '@media ($1)'
        );
      }

      var hasSelectorRules = {};
      var hasSelectorByInner = {};
      var hasSelectorSeq = 0;
      var hasRefreshScheduled = new WeakMap();
      var hasMarkedByDoc = new WeakMap();

      function stripHasFromSelector(selector) {
        var out = '', i = 0;
        while (i < selector.length) {
          var idx = selector.indexOf(':has(', i);
          if (idx < 0) { out += selector.slice(i); break; }
          out += selector.slice(i, idx);
          var open = idx + 4, depth = 1, pos = open + 1;
          while (pos < selector.length && depth > 0) {
            if (selector[pos] === '(') depth++;
            else if (selector[pos] === ')') depth--;
            pos++;
          }
          i = pos;
        }
        return out.replace(/\s+/g, ' ').trim();
      }

      function hasSelectorClass(inner, bases) {
        var key = inner + '\u0000' + bases.join(',');
        if (hasSelectorByInner[key]) return hasSelectorByInner[key];
        var hash = 5381;
        for (var i = 0; i < key.length; i++) hash = ((hash << 5) + hash) ^ key.charCodeAt(i);
        var existing = 'st-has-' + (hash >>> 0).toString(36);
        if (hasSelectorRules[existing] && hasSelectorRules[existing].key !== key) {
          existing += '-' + (++hasSelectorSeq);
        }
        hasSelectorRules[existing] = { key: key, inner: inner, bases: bases };
        hasSelectorByInner[key] = existing;
        return existing;
      }

      function rewriteHasSelectors(css) {
        var out = '', i = 0;
        while (i < css.length) {
          var idx = css.indexOf(':has(', i);
          if (idx < 0) { out += css.slice(i); break; }
          out += css.slice(i, idx);
          var open = idx + 4, depth = 1, pos = open + 1;
          while (pos < css.length && depth > 0) {
            if (css[pos] === '(') depth++;
            else if (css[pos] === ')') depth--;
            pos++;
          }
          if (depth !== 0) { out += css.slice(idx); break; }
          var inner = css.slice(open + 1, pos - 1).trim();
          var selectorStart = Math.max(css.lastIndexOf('}', idx), css.lastIndexOf(';', idx)) + 1;
          var selectorEnd = css.indexOf('{', pos);
          if (selectorEnd < 0) selectorEnd = css.length;
          var selectorText = css.slice(selectorStart, selectorEnd).trim();
          var bases = selectorText.split(',').map(stripHasFromSelector).filter(Boolean);
          out += '.' + hasSelectorClass(inner, bases);
          i = pos;
        }
        return out;
      }

      function collectHasRulesFromStyleSheets(doc) {
        if (!doc || !doc.styleSheets) return;
        for (var s = 0; s < doc.styleSheets.length; s++) {
          var rules;
          try { rules = doc.styleSheets[s].cssRules; } catch (e) { continue; }
          if (!rules) continue;
          for (var r = 0; r < rules.length; r++) {
            var rule = rules[r];
            if (!rule.style) continue;
            for (var p = 0; p < rule.style.length; p++) {
              var name = rule.style[p];
              if (name.indexOf('--st-has-') !== 0) continue;
              var value = rule.style.getPropertyValue(name).trim();
              try {
                if (value.charAt(0) === '"') value = JSON.parse(value);
              } catch (e) {}
              var className = name.slice(2);
              var parsed = value;
              try {
                if (typeof value === 'string' && value.charAt(0) === '{') parsed = JSON.parse(value);
              } catch (e) {}
              if (parsed && typeof parsed === 'object' && parsed.inner) {
                hasSelectorRules[className] = {
                  key: parsed.key || className,
                  inner: parsed.inner,
                  bases: parsed.bases || ['*']
                };
                hasSelectorByInner[hasSelectorRules[className].key] = className;
              }
            }
          }
        }
      }

      function refreshHasSelectors(doc) {
        if (!doc || !doc.querySelectorAll) return;
        var classNames = Object.keys(hasSelectorRules);
        if (classNames.length === 0) return;
        var marked = hasMarkedByDoc.get(doc) || [];
        for (var i = 0; i < marked.length; i++) {
          for (var c = marked[i].classList.length - 1; c >= 0; c--) {
            if (marked[i].classList[c].indexOf('st-has-') === 0) marked[i].classList.remove(marked[i].classList[c]);
          }
        }
        var nextMarked = [];
        var operations = 0;
        var operationLimit = 5000;
        for (var ruleClass in hasSelectorRules) {
          if (operations >= operationLimit) break;
          var rule = hasSelectorRules[ruleClass];
          var inner = String(rule.inner || '').trim();
          var childOnly = inner.charAt(0) === '>';
          if (childOnly) inner = inner.slice(1).trim();
          if (!inner || inner.length > 160 || inner.charAt(0) === '+' || inner.charAt(0) === '~') continue;
          if (!/^[.#\w\s>\[\]="'*-]+$/.test(inner) || inner.indexOf('*') >= 0) continue;
          var bases = rule.bases && rule.bases.length ? rule.bases : ['*'];
          for (var b = 0; b < bases.length && operations < operationLimit; b++) {
            var candidates;
            try { candidates = doc.querySelectorAll(bases[b]); } catch (e) { continue; }
            var candidateLimit = Math.min(candidates.length, 500);
            for (var m = 0; m < candidateLimit && operations < operationLimit; m++) {
              var candidate = candidates[m];
              var matched = false;
              try { matched = !!candidate.querySelector(childOnly ? ':scope > ' + inner : inner); } catch (e) {}
              if (!matched) continue;
              candidate.classList.add(ruleClass);
              nextMarked.push(candidate);
              operations++;
            }
          }
        }
        hasMarkedByDoc.set(doc, nextMarked);
      }

      function scheduleHasRefresh(doc) {
        if (!doc || hasRefreshScheduled.get(doc)) return;
        hasRefreshScheduled.set(doc, true);
        function run() {
          hasRefreshScheduled.delete(doc);
          try { refreshHasSelectors(doc); } catch (e) {}
        }
        if (window.requestIdleCallback) window.requestIdleCallback(run, { timeout: 180 });
        else setTimeout(run, 60);
      }

      function resolveColour(val, root, local, depth) {
        depth = depth || 0;
        if (depth > 12) return null;
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
          if (r) return resolveColour(r, root, local, depth + 1);
          // 2. Try local map (from this CSS text)
          if (local[name]) return resolveColour(local[name], root, local, depth + 1);
          // 3. An unresolved variable stays unresolved: its fallback is only the value when
          // nothing defines the variable, and a card that sets these at runtime ended up with
          // the fallback baked in (a white fill where the engine paints dark).
          return null;
        }
        if (/^color-mix\(/i.test(val)) {
          var open = val.indexOf('(');
          var close = val.lastIndexOf(')');
          if (open < 0 || close <= open) return null;
          var parts = splitTopLevel(val.slice(open + 1, close));
          if (parts.length < 3) return null;
          var weightLookup = function(name) {
            var fromRoot = root.getPropertyValue(name).trim();
            return fromRoot || local[name] || null;
          };
          var a = parseColourWeight(parts[1], weightLookup);
          var b = parseColourWeight(parts[2], weightLookup);
          if (a.weightUnreadable || b.weightUnreadable) return null;
          if (a.p < 0 && b.p < 0) { a.p = 50; b.p = 50; }
          else if (a.p < 0) a.p = Math.max(0, 100 - b.p);
          else if (b.p < 0) b.p = Math.max(0, 100 - a.p);
          return mix(
            resolveColour(a.c, root, local, depth + 1),
            resolveColour(b.c, root, local, depth + 1),
            a.p,
            b.p
          );
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
        // A zero alpha has no digits to keep, and the trimming below would emit
        // `rgba(0,0,0,)`: an invalid declaration the engine drops, which is not the same
        // result as the fully transparent colour the mix computed.
        if (c.a <= 0) return 'transparent';
        return 'rgba(' + c.r + ',' + c.g + ',' + c.b + ',' + c.a.toFixed(3).replace(/0+$/, '').replace(/\.$/, '') + ')';
      }

      // Mix two sRGB colours using premultiplied alpha.
      function mix(c1, c2, p1, p2) {
        if (!c1 || !c2) return null;  // need both colours
        var t = p1 + p2;
        if (t === 0) return c1;
        if (t > 100) {
          p1 = p1 * 100 / t;
          p2 = p2 * 100 / t;
        }
        var a1 = c1.a * p1;
        var a2 = c2.a * p2;
        var alpha = (a1 + a2) / 100;
        if (alpha <= 0) return { r: 0, g: 0, b: 0, a: 0 };
        return {
          r: Math.round((c1.r * a1 + c2.r * a2) / (a1 + a2)),
          g: Math.round((c1.g * a1 + c2.g * a2) / (a1 + a2)),
          b: Math.round((c1.b * a1 + c2.b * a2) / (a1 + a2)),
          a: Math.min(1, +alpha.toFixed(4))
        };
      }

      // Convert oklch(L C H [/A]) → rgb/rgba string
      function oklchToRGB(L, C, H, alpha) {
        if (isNaN(L) || isNaN(C)) return null;
        // oklch → oklab
        var hRad = (H || 0) * Math.PI / 180;
        var a = C * Math.cos(hRad);
        var b = C * Math.sin(hRad);
        // oklab → linear sRGB. The matrices are the reference values Björn
        // Ottosson published with Oklab; the transform has one published
        // definition, so every implementation carries the same numbers.
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
        if (alpha <= 0) return 'transparent';
        return 'rgba(' + r + ',' + g + ',' + bVal + ',' + alpha.toFixed(3).replace(/0+$/, '').replace(/\.$/, '') + ')';
      }

      function fixLegacyValueSyntax(css) {
        var identities = {
          '--tw-backdrop-blur': 'blur(0px)',
          '--tw-backdrop-brightness': 'brightness(1)',
          '--tw-backdrop-contrast': 'contrast(1)',
          '--tw-backdrop-grayscale': 'grayscale(0)',
          '--tw-backdrop-hue-rotate': 'hue-rotate(0deg)',
          '--tw-backdrop-invert': 'invert(0)',
          '--tw-backdrop-opacity': 'opacity(1)',
          '--tw-backdrop-saturate': 'saturate(1)',
          '--tw-backdrop-sepia': 'sepia(0)',
          '--tw-blur': 'blur(0px)',
          '--tw-brightness': 'brightness(1)',
          '--tw-contrast': 'contrast(1)',
          '--tw-grayscale': 'grayscale(0)',
          '--tw-hue-rotate': 'hue-rotate(0deg)',
          '--tw-invert': 'invert(0)',
          '--tw-saturate': 'saturate(1)',
          '--tw-sepia': 'sepia(0)',
          '--tw-drop-shadow': 'drop-shadow(0 0 #0000)',
          '--tw-rotate-x': 'rotateX(0deg)',
          '--tw-rotate-y': 'rotateY(0deg)',
          '--tw-rotate-z': 'rotateZ(0deg)',
          '--tw-skew-x': 'skewX(0deg)',
          '--tw-skew-y': 'skewY(0deg)'
        };
        for (var name in identities) {
          css = css.split('var(' + name + ',)').join('var(' + name + ', ' + identities[name] + ')');
        }
        css = css.split('var(--tw-ring-inset,)').join('var(--tw-ring-inset)');
        css = css.replace(/calc\(\s*(-?)infinity\s*\*\s*1(px|%|rem|em|vh|vw)\s*\)/gi, function(_, sign, unit) {
          return (sign || '') + '9999' + unit;
        });
        css = css.replace(/(--tw-gradient-position\s*:\s*[^;{}]+?)\s+in\s+(?:oklab|oklch|lab|lch|xyz(?:-[a-z0-9-]+)?)/gi, '$1');
        css = css.replace(/((?:linear|radial|conic)-gradient\([^)]*?)\s+in\s+(?:oklab|oklch|lab|lch|xyz(?:-[a-z0-9-]+)?)/gi, '$1');
        return css;
      }

      function fixCSS(css) {
        var originalCss = css;
        try {
        css = fixLegacyValueSyntax(css);
        var protectedCss = protectSupportsConditions(css);
        css = protectedCss.css;
        css = rewriteHasSelectors(css);
        css = convertContainerQueries(css);
        // Strip @layer blocks without leaving orphan closing braces.
        var layerOut = '', li = 0;
        while (li < css.length) {
          var liIdx = css.indexOf('@layer', li);
          if (liIdx < 0) { layerOut += css.slice(li); break; }
          layerOut += css.slice(li, liIdx);
          var layerOpen = css.indexOf('{', liIdx);
          var layerSemi = css.indexOf(';', liIdx);
          if (layerOpen < 0 || (layerSemi >= 0 && layerSemi < layerOpen)) {
            li = layerSemi >= 0 ? layerSemi + 1 : css.length;
            continue;
          }
          if (layerOpen - liIdx > 100) { layerOut += css.slice(liIdx, layerOpen + 1); li = layerOpen + 1; continue; }
          var layerDepth = 1, layerPos = layerOpen + 1;
          while (layerPos < css.length && layerDepth > 0) {
            if (css[layerPos] === '{') layerDepth++;
            else if (css[layerPos] === '}') layerDepth--;
            layerPos++;
          }
          layerOut += css.slice(layerOpen + 1, layerPos - 1);
          li = layerPos;
        }
        css = layerOut;
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
          var alpha = parseOklchAlpha(args);
          var rgb = oklchToRGB(L, C, H, alpha);
          return rgb || 'transparent';
        });

        // Replace rgb(from var(--X) r g b / A) → var(--X) (relative colour syntax)
        css = css.replace(/rgb\(from\s+(var\(--[\w-]+\))\s+r\s+g\s+b\s*\/?\s*[\d.]*\)/gi, '$1');

        // Container-query width units (cqw/cqi/cqmin/cqmax/cqh/cqb) need
        // Chromium 105+. On Chromium 91 a `clamp(14px, 2.5cqw, 18px)`
        // font-size becomes fully invalid and drops back to the inherited
        // size (which renders statusbar time+location as one equal size).
        // Swap them for the closest 91-compatible viewport units. The Chat
        // UIs that use them are ~viewport-width, so vw is a good stand-in.
        css = css.replace(/(-?\d*\.?\d+)cqw/gi, '$1vw');
        css = css.replace(/(-?\d*\.?\d+)cqi/gi, '$1vw');
        css = css.replace(/(-?\d*\.?\d+)cqmax/gi, '$1vmax');
        css = css.replace(/(-?\d*\.?\d+)cqmin/gi, '$1vmin');
        css = css.replace(/(-?\d*\.?\d+)cqh/gi, '$1vh');
        css = css.replace(/(-?\d*\.?\d+)cqb/gi, '$1vh');
        css = css.replace(/(\d+(?:\.\d+)?)dvh/gi, 'calc(var(--tavern-dvh-px) * $1 / 100)');
        css = css.replace(/(\d+(?:\.\d+)?)dvw/gi, 'calc(var(--tavern-dvw-px) * $1 / 100)');

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

          var weightLookup = function(name) {
            var fromRoot = root.getPropertyValue(name).trim();
            return fromRoot || localMap[name] || null;
          };
          var a = parseColourWeight(parts[1], weightLookup), b = parseColourWeight(parts[2], weightLookup);
          // Leave a mix whose weight cannot be read exactly as written: the old engine then
          // drops the one declaration, where an invented even split would repaint the element.
          if (a.weightUnreadable || b.weightUnreadable) { result += css.slice(idx, j); i = j; continue; }
          if (a.p < 0 && b.p < 0) { a.p = 50; b.p = 50; }
          else if (a.p < 0) a.p = Math.max(0, 100 - b.p);
          else if (b.p < 0) b.p = Math.max(0, 100 - a.p);

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
        return restoreSupportsConditions(result, protectedCss.conditions);
        } catch(e) { return String(originalCss); }
      }

      // Hook textContent
      var nd = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent');
      if (nd && nd.set) {
        var origTC = nd.set;
        Object.defineProperty(HTMLStyleElement.prototype, 'textContent', {
          get: nd.get, configurable: true, enumerable: true,
          set: function(v) {
            if (typeof v === 'string' && MODERN_CSS.test(v)) {
              this._st_fix = true;
              v = fixCSS(v);
            }
            return origTC.call(this, v);
          }
        });
      }
      // Hook innerHTML
      try {
        var hd = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML');
        if (hd && hd.set) {
          var origHTML = hd.set;
          Object.defineProperty(HTMLStyleElement.prototype, 'innerHTML', {
            get: hd.get, configurable: true, enumerable: true,
            set: function(v) {
              if (typeof v === 'string' && MODERN_CSS.test(v)) {
                this._st_fix = true;
                v = fixCSS(v);
              }
              return origHTML.call(this, v);
            }
          });
        }
      } catch(_) {}
      // Hook appendChild / insertBefore
      var origAC = Node.prototype.appendChild;
      Node.prototype.appendChild = function(child) {
        if (child.nodeName === 'STYLE' && child.textContent && !child._st_fix) {
          try {
            var f = fixCSS(child.textContent);
            if (typeof f === 'string') {
              child._st_fix = true;
              child.textContent = f;
            }
          } catch(e) {}
        }
        if (this.nodeName === 'STYLE' && child.nodeType === 3 && MODERN_CSS.test(child.nodeValue||'')) {
          try { this._st_fix = true; child.nodeValue = fixCSS(child.nodeValue); } catch(e) {}
        }
        return origAC.call(this, child);
      };
      var origIB = Node.prototype.insertBefore;
      Node.prototype.insertBefore = function(child, ref) {
        if (child.nodeName === 'STYLE' && child.textContent && !child._st_fix) {
          try {
            var f = fixCSS(child.textContent);
            if (typeof f === 'string') {
              child._st_fix = true;
              child.textContent = f;
            }
          } catch(e) {}
        }
        if (this.nodeName === 'STYLE' && child.nodeType === 3 && MODERN_CSS.test(child.nodeValue||'')) {
          try { this._st_fix = true; child.nodeValue = fixCSS(child.nodeValue); } catch(e) {}
        }
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
        if (st.textContent && MODERN_CSS.test(st.textContent)) {
          var p = st.parentNode; if (!p) continue;
          var n = st.nextSibling;
          st._st_fix = true;
          st.textContent = fixCSS(st.textContent);
          p.removeChild(st);
          if (n) p.insertBefore(st, n); else p.appendChild(st);
        }
      }
      console.log('[st-ctrl] CSS polyfill ready, scanned ' + styles.length + ' style tags');
      collectHasRulesFromStyleSheets(document);
      scheduleHasRefresh(document);
      setTimeout(function() {
        collectHasRulesFromStyleSheets(document);
        scheduleHasRefresh(document);
      }, 1500);
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

      // Safety net for styles injected/updated through paths not covered by the
      // textContent/innerHTML/appendChild hooks above (e.g. SillyTavern's
      // #custom-style custom-CSS panel rewrites an existing node). Any <style>
      // that still carries color-mix/oklch after those hooks is fixed here.
      function fixStyleNode(el) {
        if (!el || el.nodeName !== 'STYLE') return;
        if (el._st_fix) return;
        var t = el.textContent;
        if (typeof t !== 'string' || !MODERN_CSS.test(t)) return;
        var fixed;
        try { fixed = fixCSS(t); } catch (e) { fixed = null; }
        if (typeof fixed === 'string') {
          el._st_fix = true;
          if (fixed !== t) el.textContent = fixed;
          scheduleHasRefresh(el.ownerDocument);
        }
      }
      function fixInlineStyleElement(el) {
        if (!el || !el.getAttribute) return;
        var value = el.getAttribute('style');
        if (!value || !MODERN_CSS.test(value)) return;
        var fixed;
        try { fixed = fixCSS(value); } catch (e) { fixed = null; }
        if (typeof fixed === 'string' && fixed !== value) {
          el.setAttribute('style', fixed);
        }
        scheduleHasRefresh(el.ownerDocument);
      }
      var mo = new MutationObserver(function(muts) {
        for (var i = 0; i < muts.length; i++) {
          var m = muts[i];
          // m.target is the node whose child/data changed.
          if (m.type === 'characterData' && m.target && m.target.parentNode) {
            fixStyleNode(m.target.parentNode);
          } else if (m.type === 'childList') {
            scheduleHasRefresh(document);
            // If a text node was added directly into a <style>, fix that style.
            if (m.target && m.target.nodeName === 'STYLE') fixStyleNode(m.target);
            var nodes = m.addedNodes;
            for (var j = 0; j < nodes.length; j++) {
              if (nodes[j].nodeName === 'STYLE') fixStyleNode(nodes[j]);
              if (nodes[j].nodeName === 'LINK' ||
                  (nodes[j].nodeName === 'STYLE' && /--st-has-/.test(nodes[j].textContent || ''))) {
                setTimeout(function() {
                  collectHasRulesFromStyleSheets(document);
                  scheduleHasRefresh(document);
                }, 200);
              }
              if (nodes[j].nodeType === 1) fixInlineStyleElement(nodes[j]);
              if (nodes[j].nodeName === 'IFRAME' ||
                  (nodes[j].querySelector && nodes[j].querySelector('iframe'))) {
                scheduleIframeScan();
              }
            }
          } else if (m.type === 'attributes' && m.target) {
            fixInlineStyleElement(m.target);
          }
        }
      });
      mo.observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true,
        attributeFilter: ['style']
      });
      var inlineStyles = document.querySelectorAll('[style]');
      for (var isi = 0; isi < inlineStyles.length; isi++) fixInlineStyleElement(inlineStyles[isi]);
      scheduleHasRefresh(document);

      // TH-render and similar render custom-HTML blocks as <iframe srcdoc>
      // documents. Those are separate documents, so the <style> hooks and
      // MutationObserver above never reach them. Re-apply the pure-string CSS
      // fallbacks (container-query units, oklch, backdrop-filter) to any <style>
      // inside such iframes — cqw needs Chromium 105+, and on 91 a
      // `clamp(14px,2.5cqw,18px)` font-size drops to the inherited size.
      function fixIframeStyles(doc) {
        var styles = doc.querySelectorAll('style');
        var allCss = '';
        for (var ai = 0; ai < styles.length; ai++) allCss += '\n' + (styles[ai].textContent || '');
        var iframeCss = stripIframeLayerBlocks(allCss);
        var iframeVars = collectActiveVars(doc, iframeCss, collectIframeVars(doc, iframeCss));
        for (var i = 0; i < styles.length; i++) {
          var t = styles[i].textContent || '';
          if (!/cqw|cqi|cqh|cqb|cqmin|cqmax|oklch|backdrop-filter|color-mix|dvh|dvw|@layer|:where|:has\(|@container|:root,:host|rgb\(from|calc\(\s*(?:-?)infinity|var\(--tw-[\w-]+,\)|in\s+(?:oklab|oklch|lab|lch|xyz)/i.test(t)) continue;
          var protectedIframeCss = protectSupportsConditions(String(t));
          var f = fixLegacyValueSyntax(stripIframeLayerBlocks(protectedIframeCss.css))
            .replace(/:root,:host/g, ':root')
            .replace(/:where\(([^)]*)\)/g, '$1')
            .replace(/oklch\(([^)]+)\)/g, function(_, args) {
              var parts = args.split(/\s+/).filter(Boolean);
              var L = parseFloat(parts[0]) / (parts[0].indexOf('%') >= 0 ? 100 : 1);
              var C = parseFloat(parts[1]) || 0;
              var H = parseFloat(parts[2]) || 0;
              var alpha = parseOklchAlpha(args);
              return oklchToRGB(L, C, H, alpha) || 'transparent';
            })
            .replace(/rgb\(from\s+(var\(--[\w-]+\))\s+r\s+g\s+b\s*\/?\s*[\d.]*\)/gi, '$1')
            .replace(/(-?\d*\.?\d+)cqw/gi, '$1vw')
            .replace(/(-?\d*\.?\d+)cqi/gi, '$1vw')
            .replace(/(-?\d*\.?\d+)cqmax/gi, '$1vmax')
            .replace(/(-?\d*\.?\d+)cqmin/gi, '$1vmin')
            .replace(/(-?\d*\.?\d+)cqh/gi, '$1vh')
            .replace(/(-?\d*\.?\d+)cqb/gi, '$1vh')
            .replace(/(\d+(?:\.\d+)?)dvh/gi, 'calc(var(--tavern-dvh-px) * $1 / 100)')
            .replace(/(\d+(?:\.\d+)?)dvw/gi, 'calc(var(--tavern-dvw-px) * $1 / 100)');
          f = convertContainerQueries(rewriteHasSelectors(f));
          f = restoreSupportsConditions(f, protectedIframeCss.conditions);
          // color-mix() needs Chromium 111+. Inside the iframe the --dream-* /
          // --SmartTheme* vars are self-contained, so a string downgrade keeps
          // the declaration valid. See downgradeColorMix() for the rules.
          f = downgradeColorMix(f, iframeVars);
          if (!supportsBackdropFilter) {
            f = f.replace(/backdrop-filter\s*:\s*[^;!}\n]+/g, 'backdrop-filter: none')
              .replace(/-webkit-backdrop-filter\s*:\s*[^;!}\n]+/g, '-webkit-backdrop-filter: none');
          }
          if (f !== t) styles[i].textContent = f;
        }
        // The .dream-options index chip uses `background: var(--dream-soft)`,
        // where --dream-soft = color-mix(accent 11%, transparent) — a faint
        // translucent chip that carries the accent number. On Chromium 91 the
        // colour+transparent color-mix downgrades to transparent (see
        // downgradeColorMix), so the chip would vanish. Re-add a subtle tinted
        // chip from the resolved --dream-accent, re-reading it each pass so it
        // picks up the accent once the options render.
        var chip = 'rgba(128,144,110,0.13)';
        try {
          var pn = doc.querySelector('.dream-options');
          if (pn) {
            var pac = doc.defaultView.getComputedStyle(pn).getPropertyValue('--dream-accent').trim();
            var pm = pac.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
            if (pm) chip = 'rgba(' + pm[1] + ',' + pm[2] + ',' + pm[3] + ',0.13)';
          }
        } catch (e) {}
        var isf = doc.getElementById('__st_index_fix');
        if (!isf) {
          isf = doc.createElement('style');
          isf.id = '__st_index_fix';
          var h = doc.head || doc.documentElement;
          if (h) h.appendChild(isf);
        }
        var chipCss = '.dream-options__index{background:' + chip + '!important;box-shadow:none!important;color:var(--dream-accent)!important;}';
        // Only rewrite when the resolved colour actually changes, to avoid
        // forcing a CSS reparse on every scan.
        if (isf && isf.textContent !== chipCss) isf.textContent = chipCss;
        collectHasRulesFromStyleSheets(doc);
        scheduleHasRefresh(doc);
        // A declaration whose variable is not visible yet is left untouched rather than
        // guessed, so one more pass is worth scheduling: the card sets its --dream-st-* colours
        // on an element after the frame renders, and only then can they be resolved. The long
        // fallback rescan (8-12s) would catch it far too late to avoid a visible flash.
        if (!doc.__stctrlCompatSettleScheduled) {
          doc.__stctrlCompatSettleScheduled = true;
          setTimeout(function() {
            try { fixIframeStyles(doc); } catch (e) {}
          }, 1200);
        }
      }
      function stripIframeLayerBlocks(css) {
        var out = '', i = 0;
        while (i < css.length) {
          var idx = css.indexOf('@layer', i);
          if (idx < 0) { out += css.slice(i); break; }
          out += css.slice(i, idx);
          var open = css.indexOf('{', idx);
          var semi = css.indexOf(';', idx);
          if (open < 0 || (semi >= 0 && semi < open)) {
            i = semi >= 0 ? semi + 1 : css.length;
            continue;
          }
          if (open - idx > 100) {
            out += css.slice(idx, open + 1);
            i = open + 1;
            continue;
          }
          var depth = 1, pos = open + 1;
          while (pos < css.length && depth > 0) {
            if (css[pos] === '{') depth++;
            else if (css[pos] === '}') depth--;
            pos++;
          }
          out += css.slice(open + 1, pos - 1);
          i = pos;
        }
        return out;
      }
      function collectIframeVars(doc, css) {
        var vars = {};
        var nodes = [];
        if (doc.documentElement) nodes.push(doc.documentElement);
        if (doc.body && doc.body !== doc.documentElement) nodes.push(doc.body);
        var inline = doc.querySelectorAll('[style]');
        for (var i = 0; i < inline.length; i++) nodes.push(inline[i]);
        for (var n = 0; n < nodes.length; n++) {
          var text = nodes[n].getAttribute && nodes[n].getAttribute('style');
          if (!text) continue;
          var re = /(--[\w-]+)\s*:\s*([^;]+)/g, match;
          while ((match = re.exec(text)) !== null) {
            var value = match[2].trim();
            if (value.endsWith('!important')) value = value.slice(0, -10).trim();
            vars[match[1]] = value;
          }
        }
        var names = {}, nameRe = /(--[\w-]+)\s*:/g, nameMatch;
        while ((nameMatch = nameRe.exec(css || '')) !== null) names[nameMatch[1]] = true;
        var scopes = [doc.documentElement, doc.body].filter(Boolean);
        if (doc.defaultView) {
          for (var name in names) {
            if (vars[name]) continue;
            for (var s = 0; s < scopes.length; s++) {
              var resolved = doc.defaultView.getComputedStyle(scopes[s]).getPropertyValue(name).trim();
              if (resolved) { vars[name] = resolved; break; }
            }
          }
        }
        return vars;
      }
      function collectActiveVars(doc, css, vars) {
        var root = doc.querySelector('.dream-options') || doc.body || doc.documentElement;
        if (!root || !root.matches) return vars;
        // Selector matching is measurable on the Android 9 ARM translation
        // layer. Only the dream-options component needs selector-aware variable
        // resolution; other iframe styles use their inline/computed fallbacks.
        if (!root.classList || !root.classList.contains('dream-options')) return vars;
        var ruleRe = /([^{}]+)\{([^{}]*)\}/g, rule;
        while ((rule = ruleRe.exec(css)) !== null) {
          var selector = rule[1].trim();
          var declarations = rule[2];
          if (!selector || selector.charAt(0) === '@' || selector.length > 400) continue;
          if (declarations.indexOf('--') < 0 || selector.indexOf('@') >= 0) continue;
          try {
            if (!root.matches(selector)) continue;
          } catch (e) {
            continue;
          }
          var declRe = /(--[\w-]+)\s*:\s*([^;{}]+)/g, decl;
          while ((decl = declRe.exec(declarations)) !== null) {
            var value = decl[2].trim();
            if (value.endsWith('!important')) value = value.slice(0, -10).trim();
            vars[decl[1]] = value;
          }
        }
        return vars;
      }
      function resolveIframeColor(value, vars, depth) {
        depth = depth || 0;
        if (depth > 16 || typeof value !== 'string') return null;
        var s = value.trim();
        if (!s) return null;
        if (/^var\(/i.test(s)) {
          var open = s.indexOf('(');
          var close = s.lastIndexOf(')');
          if (open < 0 || close <= open) return null;
          var parts = splitTopLevel(s.slice(open + 1, close));
          var name = (parts.shift() || '').trim();
          if (vars[name]) return resolveIframeColor(vars[name], vars, depth + 1);
          // No fallback substitution: an unresolved variable may be defined at runtime, and
          // baking its fallback froze a white card fill the engine paints dark. Leaving the
          // declaration alone keeps the next pass (mutation or rescan) able to resolve it.
          return null;
        }
        if (/^color-mix\(/i.test(s)) {
          var mixOpen = s.indexOf('(');
          var mixClose = s.lastIndexOf(')');
          if (mixOpen < 0 || mixClose <= mixOpen) return null;
          var mixParts = splitTopLevel(s.slice(mixOpen + 1, mixClose));
          if (mixParts.length < 3) return null;
          var weightLookup = function(name) { return vars[name] || null; };
          var a = parseColourWeight(mixParts[1], weightLookup);
          var b = parseColourWeight(mixParts[2], weightLookup);
          if (a.weightUnreadable || b.weightUnreadable) return null;
          if (a.p < 0 && b.p < 0) { a.p = 50; b.p = 50; }
          else if (a.p < 0) a.p = Math.max(0, 100 - b.p);
          else if (b.p < 0) b.p = Math.max(0, 100 - a.p);
          return mix(
            resolveIframeColor(a.c, vars, depth + 1),
            resolveIframeColor(b.c, vars, depth + 1),
            a.p,
            b.p
          );
        }
        return parseRGBA(s);
      }
      function downgradeColorMix(css, vars) {
        vars = vars || {};
        var out = '', i = 0;
        while (i < css.length) {
          var idx = css.indexOf('color-mix(', i);
          if (idx < 0) { out += css.slice(i); break; }
          out += css.slice(i, idx);
          var depth = 1, j = idx + 10;
          while (j < css.length && depth > 0) { if (css[j] === '(') depth++; else if (css[j] === ')') depth--; j++; }
          var body = css.slice(idx + 10, j - 1);
          var parts = [], d = 0, s = 0;
          for (var k = 0; k < body.length; k++) {
            if (body[k] === '(') d++; else if (body[k] === ')') d--;
            else if (body[k] === ',' && d === 0) { parts.push(body.slice(s, k).trim()); s = k + 1; }
          }
          parts.push(body.slice(s).trim());
          // parts: ['in srgb', 'A P%', 'B Q%'].
          if (parts.length >= 3) {
            var a = parts[1] || '', b = parts[2] || '';
            var weightLookup = function(name) { return vars[name] || null; };
            var parsedA = parseColourWeight(a, weightLookup);
            var parsedB = parseColourWeight(b, weightLookup);
            var ac = parsedA.c, bc = parsedB.c;
            var ap = parsedA.p, bp = parsedB.p;
            // Same rule as the other two implementations: never invent a weight that could
            // not be read, because the invented split is what repaints a transparent quote.
            if (parsedA.weightUnreadable || parsedB.weightUnreadable) { out += css.slice(idx, j); i = j; continue; }
            if (ap < 0 && bp < 0) { ap = 50; bp = 50; }
            else if (ap < 0) ap = Math.max(0, 100 - bp);
            else if (bp < 0) bp = Math.max(0, 100 - ap);
            var ca = resolveIframeColor(ac, vars, 0);
            var cb = resolveIframeColor(bc, vars, 0);
            if (ca && cb) {
              var mixed = mix(ca, cb, ap, bp);
              if (mixed) {
                out += rgbaToStr(mixed);
                i = j;
                continue;
              }
            }
            if ((!ca && /^var\(/i.test(ac)) || (!cb && /^var\(/i.test(bc))) {
              out += css.slice(idx, j);
              i = j;
              continue;
            }
            // A faint colour + transparent (e.g. accent 5%/11% + transparent)
            // is meant to be near-invisible tint. Naively keeping A makes it a
            // solid accent (bright chip / strong gradient edge). Emit transparent
            // so it stays a subtle tint like on a modern engine.
            if (/transparent/i.test(b) && ap >= 0 && ap <= 25) {
              out += 'transparent';
            } else if (/^(white|black)$/i.test(ac) && /transparent/i.test(b)) {
              var rgba = ac.toLowerCase() === 'white' ? '255,255,255' : '0,0,0';
              out += 'rgba(' + rgba + ',' + (ap >= 0 ? ap / 100 : 1) + ')';
            } else {
              out += ac || 'transparent';
            }
          } else {
            out += 'transparent';
          }
          i = j;
        }
        return out;
      }
      function handleIframe(ifr) {
        if (!ifr) return;
        var idoc = null;
        try { idoc = ifr.contentDocument; } catch (e) { return; }
        if (!idoc || !idoc.documentElement) return;
        try { installExportBridge(idoc.defaultView || ifr.contentWindow); } catch (e) {}
        try {
          var dvhPx = window.visualViewport ? window.visualViewport.height : window.innerHeight;
          var dvwPx = window.visualViewport ? window.visualViewport.width : window.innerWidth;
          idoc.documentElement.style.setProperty('--tavern-dvh-px', dvhPx + 'px');
          idoc.documentElement.style.setProperty('--tavern-dvw-px', dvwPx + 'px');
        } catch (e) {}
        try { fixIframeStyles(idoc); } catch (e) {}
        observeIframeDoc(idoc);
      }

      var observedIframeDocs = new WeakSet();
      var pendingIframeDocFixes = [];
      var iframeFixScheduled = false;
      var iframeScanScheduled = false;
      var iframePerf = {
        scans: 0,
        scanMs: 0,
        docFixes: 0,
        observed: 0,
        iframeCount: 0
      };
      window.__stctrlIframePerf = iframePerf;

      function scheduleIframeScan() {
        if (iframeScanScheduled) return;
        iframeScanScheduled = true;
        function runScan() {
          iframeScanScheduled = false;
          scanIframes();
        }
        if (window.requestIdleCallback) {
          window.requestIdleCallback(runScan, { timeout: 300 });
        } else {
          setTimeout(runScan, 60);
        }
      }

      // Debounce per-document repairs so plugins that write CSS in many small
      // mutations only trigger one reparse once the write burst settles.
      function scheduleIframeDocFix(doc) {
        if (!doc || pendingIframeDocFixes.indexOf(doc) >= 0) return;
        pendingIframeDocFixes.push(doc);
        if (iframeFixScheduled) return;
        iframeFixScheduled = true;
        function runFixes() {
          iframeFixScheduled = false;
          var docs = pendingIframeDocFixes;
          pendingIframeDocFixes = [];
          for (var i = 0; i < docs.length; i++) {
            try { fixIframeStyles(docs[i]); } catch (e) {}
            iframePerf.docFixes++;
          }
        }
        if (window.requestIdleCallback) {
          window.requestIdleCallback(runFixes, { timeout: 250 });
        } else {
          setTimeout(runFixes, 80);
        }
      }

      function observeIframeDoc(doc) {
        if (!doc || observedIframeDocs.has(doc)) return;
        observedIframeDocs.add(doc);
        iframePerf.observed++;
        try {
          var obs = new MutationObserver(function() {
            scheduleIframeDocFix(doc);
            scheduleHasRefresh(doc);
          });
          obs.observe(doc.documentElement, {
            childList: true,
            subtree: true,
            characterData: true,
            attributes: true,
            attributeFilter: ['style', 'class']
          });
        } catch (e) {}
      }

      function scanIframes() {
        var t0 = performance.now();
        var all = document.querySelectorAll('iframe');
        for (var i = 0; i < all.length; i++) handleIframe(all[i]);
        iframePerf.scans++;
        iframePerf.iframeCount = all.length;
        iframePerf.scanMs += performance.now() - t0;
      }

      // srcdoc content is filled asynchronously after the iframe is inserted,
      // so capture the frame load event as well as the main-document mutation.
      document.addEventListener('load', function(e) {
        if (e.target && e.target.nodeName === 'IFRAME') scheduleIframeScan();
      }, true);
      window.addEventListener('resize', scheduleIframeScan);
      if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', scheduleIframeScan);
      }

      // Main-document MutationObserver above catches insertion, but a rare path
      // can inject a frame without a childList mutation. Keep a long fallback
      // scan so those iframes still get fixed without the old 600ms poll.
      scanIframes();
      function fallbackIframeScan() {
        scanIframes();
        var delay = (window.__stctrlPerfFactor || 1) <= 0.25 ? 12000 : 8000;
        setTimeout(fallbackIframeScan, delay);
      }
      setTimeout(fallbackIframeScan, (window.__stctrlPerfFactor || 1) <= 0.25 ? 12000 : 8000);
    })();

    // Check if dvw/dvh polyfill is needed (don't exit all compat fixes!)
    var needsDvwPolyfill = true;
    if (typeof CSS !== 'undefined' && CSS.supports) {
      try { if (CSS.supports('width', '1dvw')) needsDvwPolyfill = false; } catch (_) {}
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
      // #sheld height left to the browser (official mobile body:fixed 100dvh +
      // adjustResize). A JS-height override made the send bar lag the keyboard.
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

    // Dream-scene family (world-environment card): the plugin writes ALL styling via
    // inline `style` (JSS), including color-mix() and white-space:normal —
    // which the <style>-only fix below never sees. On Chromium 91 the inline
    // color-mix makes border/background/shadow invalid (transparent), and the
    // block-level __value wraps glyph-by-glyph into a tall column. Override the
    // visual bits with theme vars (var + rgba default resolve fine on 91) and
    // force value chips to a single horizontal line.
    out += ' .custom-dream-scene-bar{' +
      'border-color:var(--SmartThemeBorderColor,rgba(255,255,255,0.18))!important;' +
      'background:var(--SmartThemeBorderColor,rgba(255,255,255,0.18))!important;' +
      'box-shadow:none!important;' +
      'flex-wrap:nowrap!important;}' +
      '.custom-dream-scene-bar__item{' +
      'flex:0 0 auto!important;width:auto!important;min-width:0!important;' +
      'align-items:center!important;}' +
      '.custom-dream-scene-bar__value{' +
      'display:flex!important;align-items:center!important;width:auto!important;' +
      'white-space:nowrap!important;overflow-wrap:normal!important;}';

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
    // NB: do NOT set a JS-driven #sheld height here — it makes the send bar lag
    // behind the keyboard and leaves a black gap on dismiss. #sheld height is
    // owned by the browser (official mobile body:fixed + 100dvh + adjustResize).
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

  // Prevent auto-focus/auto-keyboard on page load
  // Only allow focus when the user physically taps the input itself.
  // SillyTavern auto-focuses send_textarea after loading a chat
  // ($('#send_textarea').trigger('click').trigger('focus')), which can open
  // the keyboard without a tap. Track the last touch/mouse-down target and
  // permit focusin only on that element.
  (function() {
    var tappedTarget = null;
    document.addEventListener('touchstart', function(e) {
      tappedTarget = e.target;
    }, { capture: true, passive: true });
    document.addEventListener('mousedown', function(e) {
      tappedTarget = e.target;
    }, { capture: true, passive: true });
    document.addEventListener('focusin', function(e) {
      var t = e.target;
      if (!t) return;
      var tag = t.tagName ? String(t.tagName) : '';
      // Permit focus only if the user just tapped/clicked this very element.
      if (tappedTarget === t || t.contains && t.contains(tappedTarget) || tappedTarget != null && tappedTarget.contains && tappedTarget.contains(t)) {
        return;
      }
      if (/INPUT|TEXTAREA|SELECT/.test(tag) || t.isContentEditable) {
        t.blur();
      }
    }, true);
  })();

  // Post-load utilities

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

})();
