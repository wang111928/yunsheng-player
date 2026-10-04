(function () {
    'use strict';
    if (location.protocol !== 'https:' || location.hostname !== 'api.weibo.com' ||
        location.pathname !== '/oauth2/authorize' || window.nmlWeiboHandoffProbeStarted) return;
    window.nmlWeiboHandoffProbeStarted = true;
    var attempts = 0;
    function openSelectedProvider() {
        if (window.nmlWeiboHandoffIssued || ++attempts > 60) return;
        var button = document.getElementById('callClient');
        // The provider binds this control only after its own authorization session exists.
        // Clicking it only opens the selected provider; it does not accept authorization.
        if (button) { button.click(); return; }
        if (attempts < 60) setTimeout(openSelectedProvider, 500);
    }
    setTimeout(openSelectedProvider, 500);
})();
