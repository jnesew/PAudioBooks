# Forking PAudioBooks

PAudioBooks is available under the [MIT license](../LICENSE). You may modify and
redistribute it; retain the copyright and license notice. Packaged dependencies
keep their own licenses and notices in
[the license assets](../app/src/main/assets/licenses/).

## Start a fork

Fork the repository on GitHub, clone your fork, and optionally add the original
repository as an upstream remote:

```sh
git clone https://github.com/YOUR_ACCOUNT/PAudioBooks.git
cd PAudioBooks
git remote add upstream https://github.com/jnesew/PAudioBooks.git
git switch -c your-change
```

Follow [Building](BUILDING.md) and [Testing](TESTING.md). The checked-in wrapper,
version catalog, verification metadata, Room schemas, and synthetic demo generator
are sufficient; ignored local status, emulator inventories, and evidence are not
part of a fresh clone. Keep machine setup and new test results in ignored local
files. Do not commit SDK paths, keystores, passwords, personal audio, or libraries.

## Choose an installation identity

For contributions intended for upstream, keep the application identity and make
focused changes. The `minifiedValidation` variant already uses a separate package
for optimized development checks.

For an independently distributed fork that should coexist with upstream:

1. Choose your own application ID in `app/build.gradle.kts`, and a distinct visible
   name in string resources. Give its validation variant a separate ID as well.
2. The Kotlin/Android namespace can stay unchanged. If you rename it too, update
   source packages, imports, resource references, Room schema paths, and manifest
   component names together. Avoid an unchecked global text replacement.
3. Review the merged manifest, generated receiver permission, component names,
   provider authorities in tests, instrumentation targets, and every host script
   that contains the original application ID. In particular, the release checker
   currently checks `io.github.jnesew.paudiobooks`; adapt that explicit boundary
   for your fork without weakening permission/export checks.
4. Update README links, the Obtainium source/configuration, repository branding,
   and any distribution metadata to identify your fork accurately.
5. Use your own signing key and keep it for future updates. Follow
   [Signing](SIGNING.md); never distribute a development-signed build as a release.

Changing the application ID creates a separate library. A different signing key
cannot normally update an existing installation with the same ID. Do not tell
listeners to uninstall or clear data to work around this: export progress first,
import the audio into the other installation, then explicitly preview and import
matching progress. Exports contain no audio or source-access grants.

## Keep changes reviewable

Preserve the offline/local-file contract and data-retention boundaries when
contributing upstream. Run relevant builds, lint, tests, manifest/dependency checks,
and native journeys. Describe behavior changes and the evidence actually gathered.
Keep synthetic screenshot assets separate from app resources and user data.
Review dependency additions and retain third-party notices. Publishing a fork
and signing its releases are separate deliberate steps.
