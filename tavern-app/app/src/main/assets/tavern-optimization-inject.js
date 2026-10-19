(function() {
  'use strict';

  if (window.__stctrlOptimizationBundle) return;
  window.__stctrlOptimizationBundle = true;

  function optEnabled(name, fallback) {
    try {
      if (window.AndroidBridge && AndroidBridge.tavernOpt) {
        return AndroidBridge.tavernOpt(name) !== '0';
      }
    } catch (e) {}
    return fallback !== false;
  }

  function installAutoLoadMore() {
    var attachedChat = null;
    var loading = false;
    var lastTriggerAt = 0;

    function triggerOlderMessages(chat) {
      if (!optEnabled('chat_virtual', true)) return;
      if (loading || chat.scrollTop > 260) return;
      if (document.getElementById('curEditTextarea')) return;
      var button = document.getElementById('show_more_messages');
      if (!button || button.offsetParent === null) return;

      var now = Date.now();
      if (now - lastTriggerAt < 500) return;
      lastTriggerAt = now;
      loading = true;

      try {
        button.click();
      } catch (e) {
        try {
          button.dispatchEvent(new MouseEvent('mouseup', {
            bubbles: true,
            cancelable: true,
            view: window,
          }));
        } catch (ignored) {}
      }

      setTimeout(function() {
        loading = false;
      }, 450);
    }

    function onScroll() {
      if (attachedChat && attachedChat.isConnected) triggerOlderMessages(attachedChat);
    }

    function attach() {
      var chat = document.getElementById('chat');
      if (chat === attachedChat) return;
      if (attachedChat) attachedChat.removeEventListener('scroll', onScroll);
      attachedChat = chat;
      if (attachedChat) attachedChat.addEventListener('scroll', onScroll, { passive: true });
    }

    if (window.__stctrlChatScroll && window.__stctrlChatScroll.subscribe) {
      attachedChat = window.__stctrlChatScroll.getElement();
      window.__stctrlChatScroll.subscribe(function(scroll) {
        if (!attachedChat || !attachedChat.isConnected) {
          attachedChat = window.__stctrlChatScroll.getElement();
        }
        if (scroll && scroll.top <= 260) onScroll();
      });
    } else {
      attach();
      setInterval(attach, 1000);
    }
  }

  installAutoLoadMore();
})();
