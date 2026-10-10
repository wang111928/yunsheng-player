(function () {
    'use strict';
    if (location.protocol !== 'https:' || !/^(xui|ui)\.ptlogin2\.qq\.com$/i.test(location.hostname) ||
        location.pathname !== '/cgi-bin/xlogin' || window.__nmlQqNativeHandoffInstalled) return;
    window.__nmlQqNativeHandoffInstalled = true;

    var armedUntil = 0;
    var issued = false;
    var lastGestureAt = 0;
    var attempts = 0;

    function isOneKeyTarget(node) {
        while (node) {
            if (node.id === 'onekey') return true;
            node = node.parentElement;
        }
        return false;
    }

    function isExactQqRequest(uri) {
        try {
            var parsed = new URL(uri);
            return !parsed.username && !parsed.password && !parsed.port && !parsed.hash &&
                parsed.protocol.toLowerCase() === 'wtloginmqq:' &&
                parsed.hostname.toLowerCase() === 'ptlogin' &&
                parsed.pathname === '/qlogin' &&
                parsed.searchParams.getAll('p').length === 1 && !!parsed.searchParams.get('p');
        } catch (_) { return false; }
    }

    function report(uri) {
        if (!isExactQqRequest(uri)) return false;
        // Once handed to Android, swallow the provider's duplicate iframe/location path too.
        // Letting it launch independently would race the same session's HTTPS return.
        if (issued) return true;
        if (Date.now() > armedUntil) return false;
        var bridge = window.nmlAuthHandoff;
        if (!bridge || typeof bridge.postMessage !== 'function') return false;
        issued = true;
        // The original provider URI is forwarded unchanged. It contains no app-created
        // authorization data, and Android validates it before doing an external handoff.
        bridge.postMessage(JSON.stringify({ type: 'qq-native-handoff', uri: uri }));
        return true;
    }

    function firstSchemaArgument(args) {
        for (var i = 0; i < args.length; i += 1) {
            if (typeof args[i] === 'string') return args[i];
        }
        return null;
    }

    function install() {
        var pt = window.pt;
        if (!pt) return false;
        var installed = false;
        if (pt.browser && typeof pt.browser.setLocation === 'function' && !pt.browser.__nmlSetLocation) {
            var originalSetLocation = pt.browser.setLocation;
            pt.browser.setLocation = function (uri) {
                if (report(uri)) return undefined;
                return originalSetLocation.apply(this, arguments);
            };
            pt.browser.__nmlSetLocation = true;
            installed = true;
        }
        if (pt.nativeApi && typeof pt.nativeApi.callSchemaByIFrame === 'function' && !pt.nativeApi.__nmlCallSchema) {
            var originalCallSchema = pt.nativeApi.callSchemaByIFrame;
            pt.nativeApi.callSchemaByIFrame = function () {
                if (report(firstSchemaArgument(arguments))) return undefined;
                return originalCallSchema.apply(this, arguments);
            };
            pt.nativeApi.__nmlCallSchema = true;
            installed = true;
        }
        return installed;
    }

    function armFromTrustedGesture(event) {
        if (event.isTrusted !== true || !isOneKeyTarget(event.target)) return;
        var now = Date.now();
        // A single touch can emit touchstart, pointerdown and click. Do not let the later
        // compatibility click reopen a handoff that the first handler already issued.
        if (issued && now - lastGestureAt < 750) return;
        issued = false;
        lastGestureAt = now;
        armedUntil = now + 5_000;
    }
    document.addEventListener('touchstart', armFromTrustedGesture, true);
    document.addEventListener('pointerdown', armFromTrustedGesture, true);
    document.addEventListener('click', armFromTrustedGesture, true);

    (function waitForProvider() {
        if (install() || ++attempts >= 100) return;
        setTimeout(waitForProvider, 100);
    })();
})();
