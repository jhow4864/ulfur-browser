# Release checklist

Do every step, in order, for each release. Don't publish until every box is ticked.

## 1. Before building
- [ ] Everything for the release is merged to `main` through reviewed PRs.
- [ ] CI is green on the `main` commit you're releasing (build, unit tests, lint).
- [ ] `versionCode` is higher than the last release and `versionName` is the new version (`app/build.gradle.kts`).
- [ ] `CHANGELOG.md` has a section for the new version, in plain language.
- [ ] README "For developers" shows the new version and the GeckoView / uBlock Origin versions actually shipped.

## 2. Build and verify the signer
- [ ] Build on the maintainer machine with the release key: `tools/build_small_apk.sh /path/to/Ulfur-<version>-arm64.apk`
      (or `./gradlew assembleRelease`, which runs `verifyReleaseApkSigner`).
- [ ] Check the signer yourself:
      `apksigner verify --print-certs Ulfur-<version>-arm64.apk`
      The SHA-256 digest must be `1f04b66b6d0bdf3c6e39640c23b95e517d7a144549385885e1789e3b92f6dd16`
      and the DN `CN=Beast Browser, O=Jam Howman, C=GB`. Anything else, especially `CN=Android Debug`: stop.
- [ ] Record the APK's own SHA-256 (`sha256sum Ulfur-<version>-arm64.apk`) for the release notes.
- [ ] GeckoView in `app/build.gradle.kts` is a **stable** build (`geckoviewChannel = ""`); release packaging refuses beta/nightly.
- [ ] Generate the release notes: `tools/release_notes.sh <version> Ulfur-<version>-arm64.apk > Ulfur-<version>-release-notes.md`
      (template: `docs/RELEASE_NOTES_TEMPLATE.md`). It fills in the APK SHA-256, the signer SHA-256, the GeckoView and uBlock
      Origin versions and the link to the `v<version>` source tag, which GPL-3.0 requires with every release.

## 3. Real-phone test
- [ ] Install the exact APK you'll publish **over the previous release** on a real arm64 phone (not a fresh install),
      and confirm tabs, logins, bookmarks and the private vault survived.
- [ ] Smoke test: open a few sites, check uBlock Origin blocks ads, open a private tab, download a file, unlock the
      password vault and the private vault with a fingerprint.
- [ ] Try each new feature in this release, in both light and dark mode.
- [ ] Settings › Check for updates on the previous version offers this release once it's published (check after step 5).

## 4. Back up
- [ ] Copy the APK and the release notes to the Ulfur releases folder on Google Drive.

## 5. Publish
- [ ] Tag `v<version>` on the released commit and push the tag.
- [ ] Create the GitHub release `Ulfur <version>` with `Ulfur-<version>-arm64.apk` attached and the CHANGELOG
      section as notes, ending with:
      `Signed with the Ulfur release key, SHA-256 1f04b66b6d0bdf3c6e39640c23b95e517d7a144549385885e1789e3b92f6dd16.`
- [ ] Download the APK back from the release page and run `apksigner verify --print-certs` on it once more.
