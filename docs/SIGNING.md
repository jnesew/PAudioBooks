# Signing

## Repository configuration

`debug` and `minifiedValidation` use Android's development signing key. The
production `release` variant has no signing configuration and produces
`app/build/outputs/apk/release/app-release-unsigned.apk`. It cannot be installed
until signed. See [the variant decision](decisions/0004-minified-validation.md).

Build and verify first using [Building](BUILDING.md) and [Testing](TESTING.md).
An unsigned release should fail `apksigner verify` because it has no signature;
inspect the actual error rather than treating every verification failure as
expected. Development signatures and optimized validation builds do not establish
release readiness.

## Manual APK signing

This procedure is for an explicitly authorized release. It does not configure
Gradle signing, create a key, choose a distribution channel, or publish anything.
Use `apksigner` and `zipalign` from the SDK Build Tools selected for the build.

1. Select the intended production keystore and alias. For updates, preserve the
   existing application ID and signing identity; plan any supported key rotation
   separately. Increment `versionCode` appropriately before building an update.
2. Store the keystore outside the repository and back it up securely. Never commit
   private keys, passwords, or signing properties. Use interactive password prompts
   rather than command-line password arguments or shell history.
3. Build a fresh unsigned release and complete its checks. AGP aligns its APKs;
   verify alignment before signing. Any alignment or other APK modification must
   happen before the signature is applied.
4. Sign to a separate output and verify both its signature and certificate.

The following POSIX-shell example expects `RELEASE_KEYSTORE` and `RELEASE_KEY_ALIAS`
to be set to the intended keystore path and alias. Passwords remain interactive.

```sh
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
apksigner sign --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS" --out app/build/outputs/apk/release/app-release-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release-signed.apk
```

Do not modify the signed APK afterward. Compare its certificate digest with the
intended identity, record its hash and source revision, and test the actual signed
artifact offline and through an update journey before distribution. A debug-signed
installation with the same package cannot normally be updated by a different
production key; do not uninstall a preserved library to bypass that mismatch.

App bundles and store-specific signing need a separate distribution procedure.
They are not configured by this guide. Android documents the signing model in
[Sign your app](https://developer.android.com/studio/publish/app-signing), command
options in [apksigner](https://developer.android.com/tools/apksigner), and the
alignment order in [zipalign](https://developer.android.com/tools/zipalign).

## Before distribution

Retain the project's [MIT notice](../LICENSE) and bundled third-party notices with
distributed copies. Publish only the intended signed production artifact; debug,
test, unsigned, and `.validation` APKs are not release downloads. Review the
version, certificate, artifact hash, known limitations, and acceptance evidence
before tagging or uploading. The README's Obtainium link uses this repository's
GitHub release assets, so it needs an actual signed APK there. A fork must update
that source to its own repository. No workflow here automatically signs or publishes.
