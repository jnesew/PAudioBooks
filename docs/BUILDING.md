# Building

Run commands from the repository root. Examples use a POSIX shell; on Windows,
use `gradlew.bat` and adapt environment-variable and path syntax as needed.

## Requirements

- JDK 17, with `JAVA_HOME` selecting it.
- Android SDK command-line tools and Platform Tools (`adb`).
- SDK platform `android-37.0` (API 37) and Build Tools `36.0.0`.
- Python 3 for verification scripts; FFmpeg/ffprobe for synthetic device fixtures.

Install the SDK packages with Android Studio's SDK Manager or `sdkmanager`:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
```

Accept the SDK licenses when prompted. Set `ANDROID_HOME` to your SDK directory,
or create an ignored root `local.properties` with `sdk.dir` set to that directory.
Keep machine paths out of tracked files. Put Platform Tools and the selected
Build Tools on your command path when using `adb`, `apksigner`, or `zipalign`.

Use the checked-in wrapper, which pins Gradle 9.6.0. AGP 9.4.0, Kotlin, AndroidX,
and Media3 pins are in [the version catalog](../gradle/libs.versions.toml).
The app uses minSdk 26 and compile/target SDK 37. Repository configuration is the
source of truth for these versions; do not replace it with global Gradle settings.

App version names and Android version codes follow [Releasing](RELEASING.md).
Choose them once per release cycle in `app/build.gradle.kts`; ordinary builds do
not bump them.

The first build may download the wrapper, plugins, and dependencies. Later builds
can add `--offline` when all required artifacts are cached. Build-time downloads
do not add networking to the installed app.

The [CI workflow](../.github/workflows/android.yml) uses GitHub's
[Ubuntu 24.04 image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md).
It selects JDK 17 in a separate step before installing SDK packages, since
[`GITHUB_ENV` and `GITHUB_PATH`](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#environment-files)
apply to subsequent steps. It invokes the preinstalled SDK manager through
`$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager`, without requiring it on
`PATH`, and explicitly installs the SDK platform and Build Tools listed above.

## Build variants

```sh
./gradlew --dependency-verification strict :app:assembleDebug --console plain
./gradlew --dependency-verification strict :app:assembleRelease :app:assembleMinifiedValidation --console plain
```

| Variant | APK under `app/build/outputs/apk/` | Behavior |
| --- | --- | --- |
| `debug` | `debug/app-debug.apk` | Debuggable, development signed; package `io.github.jnesew.paudiobooks` |
| `release` | `release/app-release-unsigned.apk` | R8 and resource shrinking, unsigned; same package |
| `minifiedValidation` | `minifiedValidation/app-minifiedValidation.apk` | Nondebuggable, shrunk, development signed; package `io.github.jnesew.paudiobooks.validation` |

The validation package has separate app data. It supports runtime checks of
optimized code, but its package identity and signature differ from production.
See [Signing](SIGNING.md) before preparing an installable release artifact.

Avoid concurrent builds against one checkout. Run heavy builds separately from
emulator suites, and stop idle Gradle daemons with `./gradlew --stop` when finished.

## Install a development build

After a successful current-source build, inspect `adb devices -l` and select the
intended device. Replace `DEVICE_SERIAL` below with its actual serial:

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL shell am start -n io.github.jnesew.paudiobooks/.ui.MainActivity
```

For the optimized validation variant:

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/minifiedValidation/app-minifiedValidation.apk
adb -s DEVICE_SERIAL shell am start -n io.github.jnesew.paudiobooks.validation/io.github.jnesew.paudiobooks.ui.MainActivity
```

Updates require a compatible signing identity. If installation fails, inspect the
failure; do not uninstall or clear data to bypass it on a preserved library.
Install only completed successful builds, never a leftover APK after a build failure.

Continue with [Testing](TESTING.md) for lint, unit tests, release-boundary checks,
and device journeys. Android's [command-line build guide](https://developer.android.com/build/building-cmdline)
describes wrapper tasks and build variants.
