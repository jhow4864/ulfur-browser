/* Beast Browser helper - privileged (parent process) part.
 * Uses Gecko's own per-site HTTPS-Only exception permission ("https-only-load-insecure"),
 * the same one desktop Firefox sets from its "Continue to HTTP Site" button. Session-scoped. */
"use strict";
/* global ExtensionAPI, Services, Ci */

const PERM = "https-only-load-insecure";
// nsIHttpsOnlyModePermission.LOAD_INSECURE_ALLOW_SESSION
const ALLOW_SESSION = (Ci.nsIHttpsOnlyModePermission && Ci.nsIHttpsOnlyModePermission.LOAD_INSECURE_ALLOW_SESSION) || 9;

function principalsFor(host, isPrivate) {
  if (!/^[a-z0-9.\-\[\]:]+$/i.test(host)) { throw new Error("invalid host"); }
  const attrs = isPrivate ? { privateBrowsingId: 1 } : {};
  return ["http", "https"].map(scheme =>
    Services.scriptSecurityManager.createContentPrincipal(Services.io.newURI(`${scheme}://${host}`), attrs));
}

this.beastHttps = class extends ExtensionAPI {
  getAPI(context) {
    return {
      beastHttps: {
        async allowInsecure(host, isPrivate) {
          for (const p of principalsFor(host, isPrivate)) {
            Services.perms.addFromPrincipal(p, PERM, ALLOW_SESSION, Ci.nsIPermissionManager.EXPIRE_SESSION);
          }
          return true;
        },
        async isExempt(host, isPrivate) {
          return principalsFor(host, isPrivate).some(p =>
            Services.perms.testExactPermissionFromPrincipal(p, PERM) !== Ci.nsIPermissionManager.UNKNOWN_ACTION);
        },
      },
    };
  }
};
