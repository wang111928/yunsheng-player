const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/qq_native_handoff_probe.js', 'utf8');

function page({ host = 'xui.ptlogin2.qq.com', path = '/cgi-bin/xlogin' } = {}) {
  const clickListeners = [];
  const messages = [];
  let now = 1_000;
  let originalCalls = 0;
  const provider = {
    browser: { setLocation(uri) { originalCalls += 1; return `original:${uri}`; } },
    nativeApi: { callSchemaByIFrame(uri) { originalCalls += 1; return `original:${uri}`; } },
  };
  const context = {
    window: {
      pt: provider,
      nmlAuthHandoff: { postMessage(value) { messages.push(JSON.parse(value)); } },
    },
    location: { protocol: 'https:', hostname: host, pathname: path },
    document: {
      addEventListener(type, listener) { if (['touchstart', 'pointerdown', 'click'].includes(type)) clickListeners.push(listener); },
    },
    URL,
    Date: { now: () => now },
    setTimeout() { return 1; },
  };
  vm.runInNewContext(source, context);
  return {
    provider,
    messages,
    gesture(target, isTrusted = true) { clickListeners.forEach(listener => listener({ target, isTrusted })); },
    originalCalls: () => originalCalls,
    advance(ms) { now += ms; },
  };
}

const exact = 'wtloginmqq://ptlogin/qlogin?p=opaque';

{ // Only a real click on #onekey arms the short handoff window.
  const p = page();
  p.provider.browser.setLocation(exact);
  assert.equal(p.messages.length, 0);
  p.gesture({ id: 'onekey', parentElement: null });
  p.provider.browser.setLocation(exact);
  assert.deepEqual(p.messages, [{ type: 'qq-native-handoff', uri: exact }]);
  assert.equal(p.originalCalls(), 1); // first unarmed navigation only
}

{ // Duplicate iframe/location calls cannot launch twice.
  const p = page();
  p.gesture({ id: 'onekey', parentElement: null });
  p.provider.nativeApi.callSchemaByIFrame(exact);
  p.provider.browser.setLocation(exact);
  assert.equal(p.messages.length, 1);
  assert.equal(p.originalCalls(), 0, 'duplicate must not escape through the original browser callback');
}

{ // A later independent user gesture may retry after the first app switch fails.
  const p = page();
  p.gesture({ id: 'onekey', parentElement: null });
  p.provider.browser.setLocation(exact);
  p.advance(751);
  p.gesture({ id: 'onekey', parentElement: null });
  p.provider.browser.setLocation(exact);
  assert.equal(p.messages.length, 2);
}

{ // Wrong endpoints and late programmatic navigation never leave the provider page.
  const p = page();
  p.gesture({ id: 'other', parentElement: null });
  p.provider.browser.setLocation('wtloginmqq://ptlogin/not-qlogin?p=opaque');
  assert.equal(p.messages.length, 0);
  p.gesture({ id: 'onekey', parentElement: null });
  p.advance(5_001);
  p.provider.browser.setLocation(exact);
  assert.equal(p.messages.length, 0);
}

{ // The bridge never runs on a lookalike or a non-login QQ page.
  const p = page({ host: 'evilptlogin2.qq.com' });
  p.gesture({ id: 'onekey', parentElement: null });
  p.provider.browser.setLocation(exact);
  assert.equal(p.messages.length, 0);
}

{ // Synthetic script clicks cannot arm a native login request.
  const p = page();
  p.gesture({ id: 'onekey', parentElement: null }, false);
  p.provider.browser.setLocation(exact);
  assert.equal(p.messages.length, 0);
}

console.log('qq native handoff probe contract passed');
