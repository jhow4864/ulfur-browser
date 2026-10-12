<!--
  Ulfur release-notes template. Fill it in with tools/release_notes.sh rather than by hand:

    tools/release_notes.sh <version> <path/to/Ulfur-<version>-arm64.apk> [changes.md] > Ulfur-<version>-release-notes.md

  The script computes the APK SHA-256 and the signer certificate SHA-256 from the actual APK, reads the GeckoView and
  uBlock Origin versions from the source tree, and refuses to run if the APK is not signed with the Ulfur release key
  or GeckoView is still on a beta/nightly channel. Paste the result into the GitHub release "Ulfur <version>" and save
  a copy next to the APK in the Drive "Ulfur Project Backup" folder (docs/release-checklist.md, steps 4–5).

  Placeholders: {{VERSION}} {{DATE}} {{CHANGES}} {{APK_NAME}} {{APK_SHA256}} {{APK_SIZE}} {{CERT_SHA256}}
                {{CERT_SHA256_COLON}} {{CERT_DN}} {{GECKOVIEW_VERSION}} {{UBO_VERSION}} {{SOURCE_URL}} {{TAG}}
-->
# Ulfur {{VERSION}}

Released {{DATE}}.

{{CHANGES}}

## Download and verify

| | |
|---|---|
| APK | `{{APK_NAME}}` ({{APK_SIZE}}) |
| APK SHA-256 | `{{APK_SHA256}}` |
| Signer certificate SHA-256 | `{{CERT_SHA256}}` |
| | (`{{CERT_SHA256_COLON}}`) |
| Signer | `{{CERT_DN}}` |
| Engine | Mozilla GeckoView {{GECKOVIEW_VERSION}} |
| Ad blocking | uBlock Origin {{UBO_VERSION}} (built in) |
| Source code | [{{TAG}}]({{SOURCE_URL}}) |

* **arm64 only.** This APK runs on 64-bit ARM (arm64-v8a) phones, which is nearly every Android phone from the last
  several years. There is no 32-bit ARM or x86 build.
* **Installs over the previous release.** It's signed with the same Ulfur release key as the previous release, so
  it updates in place and keeps your tabs, bookmarks, passwords and private vault. If Android says the app isn't
  compatible or asks you to uninstall first, stop: the APK isn't a genuine Ulfur release.
* Check it yourself: `sha256sum {{APK_NAME}}` must print the APK SHA-256 above, and
  `apksigner verify --print-certs {{APK_NAME}}` must show the signer certificate SHA-256 above.

## Source code and licence

Ulfur is free software under the GNU General Public License v3.0 (GPL-3.0). It includes uBlock Origin, which is also
GPLv3. The complete corresponding source code for this exact APK is the tag **{{TAG}}**:
{{SOURCE_URL}}

GeckoView is © Mozilla and licensed under the MPL 2.0. Ulfur is an independent browser built on Mozilla's GeckoView;
it isn't made by, affiliated with or endorsed by Mozilla. See `THIRD_PARTY_NOTICES.md` for all third-party licences.

Signed with the Ulfur release key, SHA-256 {{CERT_SHA256}}.
