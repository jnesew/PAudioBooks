# Testing

Follow [Building](BUILDING.md) for prerequisites. These are repeatable procedures,
not claims that the current source or every device has passed. Keep exact results,
source/APK hashes, commands, device identities, failures, and remaining acceptance
in gitignored `docs/local/`. Required behavior and the device/format matrix live
in the [implementation plan](IMPLEMENTATION_PLAN.md#verification-matrix).

## Build, lint, unit tests, and release boundary

```sh
./gradlew --dependency-verification strict :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:processReleaseMainManifest --console plain
./gradlew --dependency-verification strict :app:assembleRelease :app:lintRelease :app:assembleMinifiedValidation --console plain
mkdir -p app/build/reports
./gradlew --dependency-verification strict :app:dependencies --configuration releaseRuntimeClasspath --console plain > app/build/reports/release-runtime-dependencies.txt
python3 tools/verification/check_release.py app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml app/build/reports/release-runtime-dependencies.txt
python3 -m unittest discover -s tools/verification -v
```

Add `--offline` only with a complete dependency cache. Do not regenerate dependency
checksums to suppress a mismatch. Review changed artifacts against official
upstream sources and [the dependency decisions](decisions/0001-foundation.md).

The release checker enforces manifest permissions, exports, backup rules, and the
reviewed runtime module set. It complements dependency/license review and device
tests. Check Room schema changes with `git diff -- app/schemas`; preserve installed
library data and use non-destructive migrations. Current-schema persistence,
changed migrations, transfer safety, and progress retention remain required;
a complete historical schema/transfer matrix is outside the pre-release scope.

The [CI workflow](../.github/workflows/android.yml) runs a subset of these host
checks. Its presence does not establish that a remote run passed.

## Dependency advisory review

The wrapper distribution and JVM/SDK updates need upstream security review as well
as project dependencies. To inventory every resolved plugin, lint, KSP, compile,
runtime and test configuration and query its public Maven names/versions against
OSV, run:

```sh
./gradlew --no-daemon --dependency-verification strict -I tools/verification/dependency_inventory.init.gradle dependencySecurityInventory --console plain
python3 tools/verification/check_advisories.py app/build/reports/dependency-inventory.json --output app/build/reports/dependency-advisories.json
```

The second command requires network access and sends only package coordinates and
versions. It fails on matched advisories, incomplete inventory/API responses, and
unfinished pagination. Review scopes/reachability and upstream fixes; no match is
not proof of safety. The inventory does not include jars bundled inside Gradle,
the JDK, SDK executables, or the OS; review their official advisories separately.
[Decision 0008](decisions/0008-security-updates.md) records current security pins
and the remaining vendor-bundled Guava advisory. Retain exact dated results locally.
No online advisory service is required by the installed app or the build workflow.

For a clean-cache reproduction of CI verification, set `GRADLE_USER_HOME` to a new
empty ignored directory and use a separate `--project-cache-dir`. Run the workflow's
actual tasks without `--offline`, then run the release dependency/manifest gate.
Do not populate the fresh dependency cache by copying the ordinary cache. This
exposes parent-POM and module metadata missing from a warm-cache review. Do not
regenerate checksums as an automatic response to a failure.

## Device setup and instrumentation

Use a GMS-free AOSP emulator for offline verification and stock physical hardware
for lifecycle, routing, acoustic, removable-storage, battery, and accessibility
acceptance. Select images appropriate to the support matrix. Verify actual image,
AVD, serial, offline state, and installed package identity before a run; serials
can be reused. Record these values locally rather than fixing them in shared docs.

Run one emulator at a time and reuse suitable persistent test data. Never wipe,
uninstall, or clear a preserved library. Finish/reap instrumentation before UI
inspection, stop the owned emulator gracefully, and verify exit before launching
another. Reboot without resetting data if automation registration needs recovery.
Host emulator memory can exceed guest RAM. Avoid heavy builds during device suites.

Build the matching debug and test APKs successfully, then install both:

```sh
adb devices -l
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Replace `DEVICE_SERIAL` with the verified serial. To exercise metadata and format
cases, provision generated synthetic fixtures on a dedicated test emulator:

```sh
ANDROID_SERIAL=DEVICE_SERIAL bash tools/metadata-validation/run.sh
```

This harness requires FFmpeg/ffprobe, generates and checks its own media, provisions
it into the debug app, and runs metadata instrumentation. It removes temporary
host files on exit; capture its output in a local evidence directory when needed.
No personal audio is required or uploaded. Fixture generation and provenance are
in [the generator](../tools/fixtures/generate.py) and
[checker](../tools/fixtures/check.py).

Run explicit suites and inspect the runner's `OK` summary and failures, not just
the shell exit code. Examples using classes in the current test tree:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r -e hostDriven true -e class io.github.jnesew.paudiobooks.ui.BatchImportAcceptanceTest,io.github.jnesew.paudiobooks.playback.LinkedPlaybackAcceptanceTest io.github.jnesew.paudiobooks.test/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE_SERIAL shell am instrument -w -r -e hostDriven true -e sourceMode linked -e class io.github.jnesew.paudiobooks.metadata.FormatPlaybackTest io.github.jnesew.paudiobooks.test/androidx.test.runner.AndroidJUnitRunner
```

Choose suites for the change from `app/src/androidTest/`; some require explicit
host-driven setup or seeded baselines. Read their prerequisites rather than running
all destructive/fault cases indiscriminately. For adaptive UI checks, save and
restore the device's exact locale, font, rotation, and theme values, and assert
the Activity actually entered the intended configuration. Test both themes,
200% text, RTL, keyboard/IME, TalkBack, and switch access. Native UI assertions
do not substitute for a listener's assistive-technology journey.

## Transfer security regression suites

The native `TransferJsonTest`, `TransferDocumentAccessTest`,
`ProgressTransferCancellationTest` and `ProgressPortabilityTest` cover hostile-width
JSON, token/count/type/UTF-8 bounds, provider denial and blocked-open cancellation,
late completion, matching, version compatibility and transactional progress retention.
`BoundedTransferWorkerTest` is a JVM suite for stuck open/read/write/close and
cancellation callbacks, retry admission, late resources and close failures.
Run these on API 26/current GMS-free images after a matching successful build; keep
physical-provider behavior and the actual signed artifact as separate gates.

## Lifecycle and fault tools

Host drivers in `tools/verification/` cover sampled process death, real paused
idle expiry, the isolated inspection watchdog, and explicit storage exhaustion.
Read each driver's `--help` and its matching instrumentation class before use.
They require a successful matching installed APK pair, verified offline devices,
synthetic baselines, and a new persistent output directory. Process/idle drivers
support `--source-mode linked`; their default is private test audio.

Watchdog preparation uses the public JDK debugging API:

```sh
mkdir -p docs/local/generated/watchdog-classes
javac --add-modules jdk.jdi -d docs/local/generated/watchdog-classes tools/verification/HoldInspectionWorker.java
```

The storage-exhaustion harness has a hard-coded dedicated AVD identity in both
host and instrumentation code. This is a safety interlock, not a reusable local
device inventory. Inspect those guards before creating its isolated synthetic
setup; never run it on a shared or personal AVD. Keep actual AVD paths and setup
records local. It may allocate substantial guest storage. Distinguish EDQUOT
from global ENOSPC and confirm cleanup of only test-owned filler.

Record partial, interrupted, and failed runs as such. Source-specific fixtures,
component tests, native journeys, and physical evidence establish different
scopes; none alone closes the entire release matrix. Verify the optimized
validation variant separately, then verify the final signed artifact if release
signing is authorized. See [Signing](SIGNING.md).

## Documentation-only changes

Check relative links and anchors, `git diff --check`, and consistency with source
configuration. No app build or emulator run is needed when only prose/reference
files change. Keep local-only evidence out of tracked links and build inputs.
