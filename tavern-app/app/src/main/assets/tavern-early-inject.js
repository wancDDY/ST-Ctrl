(function() {
  if (window.__stctrl_timerThrottleInstalled) return;
  window.__stctrl_timerThrottleInstalled = true;

  var originalSetInterval = window.setInterval;
  var originalClearInterval = window.clearInterval;
  var records = {};

  function readEnabled() {
    var factor = 1;
    var animation = true;
    try {
      if (window.AndroidBridge && AndroidBridge.perfFactor) {
        var parsed = parseFloat(AndroidBridge.perfFactor());
        if (isFinite(parsed) && parsed > 0) factor = parsed;
      }
      if (window.AndroidBridge && AndroidBridge.tavernOpt) {
        animation = AndroidBridge.tavernOpt('perf_animation') !== '0';
      }
    } catch (_) {}
    return animation && factor < 0.25;
  }

  function intervalMs(baseMs) {
    if (baseMs && baseMs >= 500 && window.__stctrlTimerThrottle) {
      return baseMs * (Number(window.__stctrlThrottleFactor) || 2);
    }
    return baseMs;
  }

  function start(record) {
    if (record.id != null) {
      originalClearInterval(record.id);
    }
    record.id = originalSetInterval(function() {
      if (window.__tavernTimersPaused) return;
      try {
        record.fn.apply(window, record.args);
      } catch (error) {
        console.warn('[st-ctrl] throttled interval failed:', error);
      }
    }, intervalMs(record.baseMs));
  }

  window.__stctrlTimerThrottle = readEnabled();
  window.__stctrlThrottleFactor = 2;
  window.setInterval = function(fn, ms) {
    if (typeof fn !== 'function') {
      return originalSetInterval.apply(window, arguments);
    }
    var record = {
      fn: fn,
      args: Array.prototype.slice.call(arguments, 2),
      baseMs: ms,
      id: null,
    };
    start(record);
    records[record.id] = record;
    return record.id;
  };
  window.clearInterval = function(id) {
    delete records[id];
    return originalClearInterval(id);
  };
  window.__stctrlApplyTimerThrottle = function() {
    for (var id in records) {
      if (Object.prototype.hasOwnProperty.call(records, id)) {
        start(records[id]);
      }
    }
  };
})();
