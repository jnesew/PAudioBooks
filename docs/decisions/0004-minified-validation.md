# 0004: Minified release configuration and local validation buildType

See [Building](../BUILDING.md), [Testing](../TESTING.md), and
[Signing](../SIGNING.md) for the reusable procedures.

## Context and constraints

Release verification requires that the production
application builds under R8 code minification and resource shrinking, produces an
unsigned release artifact with no debug or logging artifacts, and executes its core
offline listening journeys correctly under optimization.

Three critical constraints govern this gate:

1. **No Release Signing or Publication**: The repository contains no production
   signing keys, keystores, or credentials. The production release artifact must
   remain strictly unsigned (`app-release-unsigned.apk`).
2. **On-Device Package Verification**: The Android OS package manager (`pm install`)
   rejects completely unsigned binaries (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`).
   Testing R8-optimized bytecode on an emulator or physical device therefore requires
   a signature.
3. **Data Preservation**: Debug installations may contain a populated library.
   Installing a build with a different signing key under the same package can fail
   with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Keep validation data separate and
   never clear/uninstall a preserved library to bypass that failure.

## Selected configuration

### 1. Production release buildType
Configure `release` in `app/build.gradle.kts` with full R8 optimization and resource
shrinking:

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

The `release` buildType has **no** `signingConfig`. It compiles against the checked-in
dependencies and strict verification metadata, intended to produce an unsigned APK for release
auditing and size verification, conditional upon inspecting actual output artifacts and
signature verification.

### 2. Minified validation buildType
Introduce a dedicated local testing buildType `minifiedValidation`:

```kotlin
    create("minifiedValidation") {
        initWith(getByName("release"))
        matchingFallbacks += listOf("release")
        applicationIdSuffix = ".validation"
        isDebuggable = false
        signingConfig = signingConfigs.getByName("debug")
    }
```

- `initWith(getByName("release"))` inherits all release settings, including R8 code
  shrinking, optimization, resource shrinking, and ProGuard configuration files.
- `matchingFallbacks += listOf("release")` ensures library dependencies resolve their
  release variants.
- `applicationIdSuffix = ".validation"` changes the installed package name to
  `io.github.jnesew.paudiobooks.validation`. This is intended to isolate validation app data. Confirm
  separation on-device by
  checking application IDs, provider authorities, and preserved debug library state.
- `isDebuggable = false` ensures the runtime runs under production non-debuggable ART
  semantics.
- `signingConfig = signingConfigs.getByName("debug")` signs the artifact with the local
  Android SDK debug keystore, enabling `adb install -r` on test devices without touching
  or creating release credentials.

### 3. ProGuard / R8 rules policy
Rely on upstream consumer ProGuard rules packaged inside official AndroidX AAR
dependencies (`androidx.room`, `androidx.media3`, `androidx.compose`, `kotlinx.coroutines`).
The architecture establishes:

- Standard default consumer rules without blanket keep rules for Media3, session,
  library, entities, or DAOs.
- No blanket `-dontwarn` rules.
- Narrow rules may only be introduced if accompanied by an actual build or runtime
  failure and documented rationale.

## Artifact equivalence and limitations

Using the same optimizer settings (`isMinifyEnabled = true`, `isShrinkResources = true`,
`proguard-android-optimize.txt`) does **not** guarantee identical artifact behavior or
bytecode between `release` and `minifiedValidation`:

- The application ID suffix (`.validation`) changes manifest entries, package references,
  generated resource mappings, and internal identifier strings.
- The APK signature scheme and certificate metadata differ (unsigned vs. debug certificate).
- Variant-specific resources or dependency resolution paths may differ.

The `minifiedValidation` APK is strictly an internal developer verification artifact for
testing R8-optimized execution on-device. It is **never** a release-ready, publishable,
or distributable application.

## Component and namespace identifiers

With `namespace = "io.github.jnesew.paudiobooks"` and `applicationIdSuffix = ".validation"`:

- Application ID: `io.github.jnesew.paudiobooks.validation`
- Launcher Component: `io.github.jnesew.paudiobooks.validation/io.github.jnesew.paudiobooks.ui.MainActivity`
- Playback Service: `io.github.jnesew.paudiobooks.validation/io.github.jnesew.paudiobooks.playback.PlaybackService`
- Isolated Metadata Service: `io.github.jnesew.paudiobooks.validation/io.github.jnesew.paudiobooks.metadata.InspectionService`

## References

- Android Developers: [Shrink, obfuscate, and optimize your app](https://developer.android.com/studio/build/shrink-code)
- Android Developers: [Configure build variants](https://developer.android.com/build/build-variants)
