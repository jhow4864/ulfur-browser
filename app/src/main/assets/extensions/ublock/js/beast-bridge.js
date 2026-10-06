/*******************************************************************************

    Beast Browser bridge for the built-in uBlock Origin (added by Beast Browser,
    not part of upstream uBO). Licensed GPLv3 like the rest of uBlock Origin.

    Lets the Android app read/set uBO's per-site "trusted site" switch (the big
    power button in uBO's panel = µb.netWhitelist) over a GeckoView native
    messaging port, and pushes a notification to the app whenever the switch is
    flipped from anywhere (uBO panel, dashboard "Trusted sites", or the app).

*******************************************************************************/

import µb from './background.js';
import './ublock.js';

const NATIVE_APP = 'beast_ubo';
let port = null;
let retryDelay = 1000;

const siteState = url => {
    try { return µb.getNetFilteringSwitch(url) !== false; } catch { return true; }
};

const notify = msg => {
    if ( port === null ) { return; }
    try { port.postMessage(msg); } catch { }
};

// Wrap the toggle so changes made in uBO's own UI are mirrored in the app.
const originalToggle = µb.toggleNetFilteringSwitch;
µb.toggleNetFilteringSwitch = function(url, scope, newState) {
    const before = siteState(url);
    const result = originalToggle.call(this, url, scope, newState);
    const after = siteState(url);
    if ( before !== after ) {
        notify({ type: 'siteChanged', url, enabled: after });
    }
    return result;
};
const originalSaveWhitelist = µb.saveWhitelist;
µb.saveWhitelist = function(...args) {
    const r = originalSaveWhitelist.apply(this, args);
    notify({ type: 'whitelistChanged' });
    return r;
};

const onMessage = msg => {
    if ( msg instanceof Object === false ) { return; }
    const { id, type, url } = msg;
    try {
        switch ( type ) {
        case 'getSite':
            notify({ id, ok: true, enabled: siteState(url) });
            break;
        case 'setSite': {
            const want = msg.enabled === true;
            if ( siteState(url) !== want ) {
                µb.toggleNetFilteringSwitch(url, '', want);
            }
            notify({ id, ok: true, enabled: siteState(url) });
            break;
        }
        case 'ping':
            notify({ id, ok: true, version: browser.runtime.getManifest().version });
            break;
        default:
            notify({ id, ok: false, error: `unknown type ${type}` });
            break;
        }
    } catch (e) {
        notify({ id, ok: false, error: String(e) });
    }
};

const connect = ( ) => {
    try {
        port = browser.runtime.connectNative(NATIVE_APP);
    } catch {
        port = null;
        setTimeout(connect, retryDelay);
        retryDelay = Math.min(retryDelay * 2, 30000);
        return;
    }
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(( ) => {
        port = null;
        setTimeout(connect, retryDelay);
        retryDelay = Math.min(retryDelay * 2, 30000);
    });
    retryDelay = 1000;
    notify({ type: 'hello' });
};

connect();
