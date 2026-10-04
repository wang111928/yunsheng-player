(function () {
    'use strict';
    if (location.hostname !== 'xui.ptlogin2.qq.com' || location.pathname !== '/cgi-bin/xlogin') return;
    if (window.__nmlQqLifecycleBridgeInstalled) return;
    window.__nmlQqLifecycleBridgeInstalled = true;

    var state = {
        provider: null,
        pending: null,
        visibleTurn: false,
        hiddenCancellationWindow: false
    };
    var attempts = 0;

    function provider() {
        var candidate = window.pt && window.pt.plogin;
        return candidate && typeof candidate.begin_qrlogin === 'function' &&
            typeof candidate.qrlogin_submit === 'function' &&
            typeof candidate.set_qrlogin_invalid === 'function' &&
            typeof candidate.cancle_qrlogin === 'function' ? candidate : null;
    }

    function isFiniteNumber(value) { return typeof value === 'number' && isFinite(value); }

    function hook(candidate) {
        if (!candidate || state.provider === candidate) return;
        var originalBegin = candidate.begin_qrlogin;
        state.provider = candidate;

        function recordVisibilityCancellation() {
            var saved = state.pending;
            if (state.hiddenCancellationWindow && saved &&
                saved.generation === candidate.qrloginGetTime && candidate.loginState === 2) {
                saved.normalCancellation = true;
            }
        }

        // The provider's visibility listener calls cancle_qrlogin synchronously.
        // It is the only cancellation eligible for resumption. A server result
        // calls set_qrlogin_invalid and must always discard our timing snapshot.
        var originalCancelQrLogin = candidate.cancle_qrlogin;
        candidate.cancle_qrlogin = function () {
            recordVisibilityCancellation();
            return originalCancelQrLogin.apply(this, arguments);
        };
        var originalInvalid = candidate.set_qrlogin_invalid;
        candidate.set_qrlogin_invalid = function () {
            state.pending = null;
            return originalInvalid.apply(this, arguments);
        };

        candidate.begin_qrlogin = function () {
            var saved = state.pending;
            state.pending = null;
            var now = Date.now();
            var canResume = state.visibleTurn && saved &&
                saved.generation === candidate.qrloginGetTime &&
                saved.normalCancellation && candidate.loginState === 2 &&
                !candidate.qrlogin_clock && now < saved.deadline;
            if (!canResume) return originalBegin.apply(this, arguments);

            clearInterval(candidate.qrlogin_clock);
            clearTimeout(candidate.qrlogin_timeout);
            candidate.qrlogin_invalid = false;
            candidate.qrlogin_clock = setInterval(function () { candidate.qrlogin_submit(); }, 3000);
            candidate.qrlogin_timeout = setTimeout(function () {
                candidate.set_qrlogin_invalid();
            }, saved.deadline - now);
            setTimeout(function () { candidate.qrlogin_submit(); }, 0);
            window.nmlQqQrResumeCount = (window.nmlQqQrResumeCount || 0) + 1;
        };
    }

    function capturePending(candidate) {
        var generation = candidate.qrloginGetTime;
        var timeout = candidate.qrlogin_timeout_time;
        if (candidate.loginState !== 2 || !candidate.qrlogin_clock ||
            !isFiniteNumber(generation) || !isFiniteNumber(timeout)) {
            state.pending = null;
            return;
        }
        var deadline = generation + timeout;
        state.pending = deadline > Date.now()
            ? { generation: generation, deadline: deadline, normalCancellation: false }
            : null;
    }

    // Registered at document start in capture phase so this runs before the provider's later
    // lifecycle listener. It records only timing metadata from the same live document.
    document.addEventListener('visibilitychange', function () {
        var candidate = provider();
        if (candidate) hook(candidate);
        if (document.visibilityState === 'hidden') {
            state.hiddenCancellationWindow = true;
            if (candidate) capturePending(candidate);
            // This remains true for QQ's synchronous visibility listener only. A
            // later server result must not revive an expired or cancelled QR code.
            setTimeout(function () {
                state.hiddenCancellationWindow = false;
            }, 0);
            return;
        }
        state.visibleTurn = true;
        setTimeout(function () {
            state.visibleTurn = false;
            state.pending = null;
        }, 0);
    }, true);

    (function waitForProvider() {
        var candidate = provider();
        if (candidate) { hook(candidate); return; }
        if (++attempts < 100) setTimeout(waitForProvider, 100);
    })();
})();
