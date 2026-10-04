const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/qq_lifecycle_bridge.js', 'utf8');

function createPage({ now = 1_000, path = '/cgi-bin/xlogin', timeout = 10_000 } = {}) {
  const listeners = { capture: [], bubble: [] };
  const timers = [];
  const activeTimers = new Map();
  const microtasks = [];
  const eventOrder = [];
  let nextTimer = 1;
  const clearInterval = (id) => activeTimers.delete(id);
  const clearTimeout = (id) => activeTimers.delete(id);
  const provider = {
    loginState: 2,
    qrlogin_clock: 1,
    qrlogin_timeout: 2,
    qrloginGetTime: now - 500,
    qrlogin_timeout_time: timeout,
    qrlogin_invalid: false,
    submitCalls: 0,
    invalidCalls: 0,
    cancelCalls: 0,
    originalBegins: 0,
    qrlogin_submit() { this.submitCalls += 1; },
    cancle_qrlogin() {
      this.cancelCalls += 1;
      clearInterval(this.qrlogin_clock);
      clearTimeout(this.qrlogin_timeout);
      this.qrlogin_clock = null;
      this.qrlogin_timeout = null;
      this.qrlogin_invalid = true;
    },
    set_qrlogin_invalid() {
      this.invalidCalls += 1;
      clearInterval(this.qrlogin_clock);
      clearTimeout(this.qrlogin_timeout);
      this.qrlogin_clock = null;
      this.qrlogin_timeout = null;
      this.qrlogin_invalid = true;
    },
    begin_qrlogin() { this.originalBegins += 1; },
  };
  const document = {
    visibilityState: 'visible',
    addEventListener(type, listener, capture) {
      if (type === 'visibilitychange') listeners[capture === true ? 'capture' : 'bubble'].push(listener);
    },
    fire(visibility) {
      this.visibilityState = visibility;
      for (const listener of listeners.capture) listener();
      while (microtasks.length) microtasks.shift()();
      for (const listener of listeners.bubble) listener();
    },
  };
  const context = {
    window: { pt: { plogin: provider } }, document,
    location: { hostname: 'xui.ptlogin2.qq.com', pathname: path },
    Date: { now: () => now },
    setInterval(fn, ms) {
      const id = nextTimer++;
      const timer = { id, kind: 'interval', fn, ms };
      timers.push(timer); activeTimers.set(id, timer); return id;
    },
    clearInterval,
    setTimeout(fn, ms) {
      const id = nextTimer++;
      const timer = { id, kind: 'timeout', fn, ms };
      timers.push(timer); activeTimers.set(id, timer); return id;
    },
    clearTimeout,
    queueMicrotask(fn) { microtasks.push(fn); },
  };
  vm.runInNewContext(source, context);
  // This deliberately places a microtask between document-start capture listeners
  // and QQ's later bubble listener. The bridge must use a timer for its visible turn.
  document.addEventListener('visibilitychange', () => {
    context.queueMicrotask(() => eventOrder.push(`microtask:${document.visibilityState}`));
  }, true);
  document.addEventListener('visibilitychange', () => {
    eventOrder.push(`qq:${document.visibilityState}`);
    if (document.visibilityState === 'hidden') provider.cancle_qrlogin();
    else provider.begin_qrlogin();
  });
  return {
    provider,
    document,
    timers,
    activePollingCount: () => [...activeTimers.values()]
      .filter(t => t.kind === 'interval' && t.ms === 3000).length,
    eventOrder,
    runZeroTimers: () => timers
      .filter(t => t.kind === 'timeout' && t.ms === 0 && activeTimers.has(t.id))
      .forEach(t => { activeTimers.delete(t.id); t.fn(); }),
  };
}

function cancelForHiddenPage(page) {
  page.document.fire('hidden');
}

{ // The bridge resumes only the original live page once after a background round trip.
  const page = createPage();
  cancelForHiddenPage(page);
  page.document.fire('visible');
  assert.equal(page.provider.originalBegins, 0);
  assert.equal(page.provider.qrlogin_invalid, false);
  assert.equal(page.activePollingCount(), 1);
  assert.deepEqual(page.eventOrder, ['microtask:hidden', 'qq:hidden', 'microtask:visible', 'qq:visible']);
  page.runZeroTimers();
  assert.equal(page.provider.submitCalls, 1);
  page.provider.begin_qrlogin();
  assert.equal(page.provider.originalBegins, 1, 'a second visible call must not create another polling timer');
}

{ // An expired code must go through QQ's normal path instead of being revived.
  const page = createPage({ now: 10_000, timeout: 1_000 });
  page.provider.qrloginGetTime = 0;
  cancelForHiddenPage(page);
  page.document.fire('visible');
  assert.equal(page.provider.originalBegins, 1);
  assert.equal(page.activePollingCount(), 0);
}

{ // An in-flight terminal result after the hidden listener invalidates the snapshot.
  const page = createPage();
  cancelForHiddenPage(page);
  page.runZeroTimers();
  page.provider.set_qrlogin_invalid();
  page.provider.qrlogin_invalid = false; // The bridge must not rely on this mutable UI flag alone.
  page.document.fire('visible');
  assert.equal(page.provider.originalBegins, 1);
  assert.equal(page.activePollingCount(), 0);
}

{ // Two distinct HOME cycles on the same QR leave only the latest poller active.
  const page = createPage();
  cancelForHiddenPage(page);
  page.document.fire('visible');
  page.runZeroTimers();
  cancelForHiddenPage(page);
  page.document.fire('visible');
  assert.equal(page.provider.originalBegins, 0);
  assert.equal(page.activePollingCount(), 1);
  assert.equal(page.timers.filter(t => t.kind === 'interval' && t.ms === 3000).length, 2);
  page.provider.begin_qrlogin();
  assert.equal(page.provider.originalBegins, 1);
}

{ // The same unexpired QR session may survive a later, separate background round trip.
  const page = createPage();
  cancelForHiddenPage(page);
  page.document.fire('visible');
  page.runZeroTimers();

  cancelForHiddenPage(page);
  page.document.fire('visible');
  assert.equal(page.provider.originalBegins, 0);
  assert.equal(page.activePollingCount(), 1);
}

{ // A terminal callback that changes QQ's login state cannot be resumed later.
  const page = createPage();
  cancelForHiddenPage(page);
  page.runZeroTimers();
  page.provider.loginState = 0;
  page.provider.set_qrlogin_invalid();
  page.provider.loginState = 2;
  page.provider.qrlogin_invalid = false;
  page.document.fire('visible');
  assert.equal(page.provider.originalBegins, 1);
}

{ // The document-start script cannot be active on unrelated QQ pages or carry credentials.
  const page = createPage({ path: '/jump' });
  assert.equal(page.document.fire('hidden'), undefined);
  assert.equal(page.provider.cancelCalls, 1, 'QQ bubble listener still receives the hidden event');
  assert.equal(page.provider.begin_qrlogin(), undefined);
  assert.equal(page.provider.originalBegins, 1);
  assert.doesNotMatch(source, /authqrsig|ptqrtoken|document\.cookie|localStorage|sessionStorage/i);
}

console.log('qq lifecycle bridge contract: ok');
