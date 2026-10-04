package com.litemusic.app.feature.auth

/** Embed the first-party login component after the desktop site's own bootstrap has loaded it.
 * The component owns key generation, polling and security challenges; no credentials are exported
 * through JavaScript. Parent/type match the site's pt_login LoginModal call.
 */
internal fun officialQrEntryScript(openOtherLoginOptions: Boolean): String = """
    (function () {
        if (window.nmlQrEntryState) return window.nmlQrEntryState;
        window.nmlQrEntryState = 'waiting';
        var openOtherLoginOptions = $openOtherLoginOptions;
        var attempts = 0;
        function open() {
            if (!window.CtWebLogin || !window.CtWebLogin.LoginModal) {
                if (++attempts < 60) { setTimeout(open, 250); return; }
                window.nmlQrEntryState = 'unavailable';
                return;
            }
            try {
                var root = document.createElement('div');
                root.id = 'nml-official-login';
                root.style.cssText = 'width:560px;min-height:600px;margin:0 auto';
                document.body.prepend(root);
                var viewport = document.querySelector('meta[name="viewport"]');
                if (!viewport) { viewport = document.createElement('meta'); document.head.appendChild(viewport); }
                viewport.name = 'viewport';
                viewport.content = 'width=560';
                document.documentElement.style.cssText = 'width:560px;min-width:0!important;height:auto!important;min-height:100vh;overflow:auto!important';
                document.body.style.cssText = 'width:560px;min-width:0!important;height:auto!important;min-height:100vh;overflow:auto!important';
                document.querySelectorAll('.m-top,.m-subnav,#g_iframe,.g-ft,.m-playbar')
                    .forEach(function(n) { n.style.display = 'none'; });
                window.CtWebLogin.LoginModal({
                    type:'page', parentNode:root,
                    onSuccess:function(user) {
                        if (window.g_onUserLogin) window.g_onUserLogin({user:user});
                    }
                });
                if (openOtherLoginOptions) {
                    var optionAttempts = 0;
                    function openOfficialOtherLoginOptions() {
                        var target = Array.prototype.find.call(
                            root.querySelectorAll('a,button,[role="button"]'),
                            function(node) { return node.textContent.trim() === '选择其他登录模式'; }
                        );
                        if (target) {
                            // This is the first-party component's own event handler. It creates
                            // the QQ OAuth state only after the user later accepts its agreement
                            // and selects QQ; this code never performs either of those actions.
                            target.click();
                            window.nmlQrEntryState = 'other-login-options-ready';
                        } else if (++optionAttempts < 60) {
                            setTimeout(openOfficialOtherLoginOptions, 100);
                        } else {
                            window.nmlQrEntryState = 'unavailable';
                        }
                    }
                    openOfficialOtherLoginOptions();
                }
                window.scrollTo(0,0);
                if (!openOtherLoginOptions) window.nmlQrEntryState = 'ready';
            } catch (_) { window.nmlQrEntryState = 'unavailable'; }
        }
        open();
        return window.nmlQrEntryState;
    })();
""".trimIndent()

internal const val OFFICIAL_QR_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
