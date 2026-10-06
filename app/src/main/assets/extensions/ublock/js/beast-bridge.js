/*******************************************************************************

    Beast Browser bridge for the built-in uBlock Origin (added by Beast Browser,
    not part of upstream uBO). Licensed GPLv3 like the rest of uBlock Origin.

    Lets the Android app read/set uBO's per-site "trusted site" switch (the big
    power button in uBO's panel = µb.netWhitelist) over a GeckoView native
    messaging port, and pushes a notification to the app whenever the switch is
    flipped from anywhere (uBO panel, dashboard "Trusted sites", or the app).

    Ulfur 2.7: also switches uBO's stock cookie-notice lists on/off for the
    app's "Hide cookie banners" setting (getCookieLists / setCookieLists),
    and reads/sets uBO's per-site "no-cosmetic-filtering" switch for the
    Shields sheet's "Hide cookie banners on this site" (getCosmetic /
    setCosmetic). That switch is uBO's own eye-dropper toggle, so it also
    stops cosmetic cleanup on that site; network blocking stays on.

*******************************************************************************/

import µb from './background.js';
import './ublock.js';
import { hostnameFromURI } from './uri-utils.js';
import { sessionSwitches } from './filtering-engines.js';

const NATIVE_APP = 'beast_ubo';
let port = null;
let retryDelay = 1000;

// "EasyList/uBO – Cookie Notices" in uBO's Annoyances group (uBO marks it preferred over the AdGuard pair).
const COOKIE_LISTS = [ 'fanboy-cookiemonster', 'ublock-cookies-easylist' ];

const cookieListsOn = ( ) =>
    COOKIE_LISTS.every(k => µb.selectedFilterLists.includes(k));

const setCookieLists = async want => {
    await µb.isReadyPromise;                     // selectedFilterLists is only complete once uBO has started
    if ( cookieListsOn() === want ) { return; }
    const toSelect = want
        ? COOKIE_LISTS
        : µb.selectedFilterLists.filter(k => COOKIE_LISTS.includes(k) === false);
    µb.applyFilterListSelection({ toSelect, merge: want });
    await µb.loadFilterLists();
};

// true = cosmetic filtering (cookie-banner hiding included) applies on the site of [url].
const cosmeticOn = url => {
    const hostname = hostnameFromURI(url);
    if ( hostname === '' ) { return true; }
    return sessionSwitches.evaluateZ('no-cosmetic-filtering', hostname) !== true;
};

const setCosmetic = (url, on) => {
    const hostname = hostnameFromURI(url);
    if ( hostname === '' ) { throw new Error('no hostname'); }
    if ( cosmeticOn(url) === on ) { return; }
    // tabId -1: uBO's live cosmetic-on/off scriptlet is skipped (its executeScript error is swallowed);
    // the app reloads the site's tabs instead, which also re-runs scriptlets.
    µb.toggleHostnameSwitch({
        name: 'no-cosmetic-filtering',
        hostname,
        state: on === false,
        persist: true,
        tabId: -1,
    });
};

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

const onMessage = async msg => {
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
        case 'getCookieLists':
            await µb.isReadyPromise;
            notify({ id, ok: true, enabled: cookieListsOn() });
            break;
        case 'setCookieLists':
            await setCookieLists(msg.enabled === true);
            notify({ id, ok: true, enabled: cookieListsOn() });
            break;
        case 'getCosmetic':
            await µb.isReadyPromise;
            notify({ id, ok: true, enabled: cosmeticOn(url) });
            break;
        case 'setCosmetic':
            await µb.isReadyPromise;
            setCosmetic(url, msg.enabled === true);
            notify({ id, ok: true, enabled: cosmeticOn(url) });
            break;
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
