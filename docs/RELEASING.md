# Versioning and release preparation

PAudioBooks follows the version-bump and release-cycle rules used by
[ComicReader](https://github.com/jnesew/ComicReader/blob/main/docs/RELEASING.md#version-notes-and-source).
Version values live in `defaultConfig` in
[app/build.gradle.kts](../app/build.gradle.kts). The current development values
are `versionName = "0.1.0"` and `versionCode = 1`; they are not a publication record.

## Choose the bump

Use a three-part numeric `MAJOR.MINOR.PATCH` version name. Choose the largest bump
required by the complete set of changes in the release:

| Bump | Use | Example from 1.2.3 |
| --- | --- | --- |
| Patch | Compatible fixes, cleanup, build tooling, or documentation | 1.2.4 |
| Minor | Compatible features or substantial functionality improvements | 1.3.0 |
| Major | Deliberate breaking changes, including dropping supported Android versions or requiring data resets | 2.0.0 |

A migration that preserves library data does not by itself require a major bump.
A version bump does not authorize a destructive migration or data reset; the
[data-retention contract](ARCHITECTURE.md#data-model) still applies.
These project rules also apply during `0.x` development.

## One version per release cycle

Choose the version once for the complete release cycle. Feature and fix branches
inherit the version already selected on their staging branch, or on `main` when
there is no separate staging branch. If the intended version is already set,
keep it. Do not bump again for each task, commit, review fix, build, or merge.
When the planned scope requires a larger bump, revise that cycle's target once
and carry the revised version forward.

For a new cycle, compare numeric versions from the current source and published
releases for this application ID. Increment the selected component from the
higher version base and reset lower components to zero. For example, a minor
bump from 1.2.3 is 1.3.0. Published prereleases count in that baseline; drafts and
local test tags do not. New release targets use plain `X.Y.Z` names, including
when GitHub marks the release as a prerelease.

Unpublished development work and documentation-only commits can share the assigned
cycle version. The initial development version can remain until the first
publication cycle is deliberately prepared.

## Android version code

`versionCode` is a separate positive integer used by Android to order updates;
it is not calculated from the semantic version components. See
[Android's versioning guidance](https://developer.android.com/studio/publish/versioning).

Every newly distributed APK whose contents changed needs a higher code. When
starting a cycle, assign one above the highest current or published code for the
same application ID. Published prereleases count; drafts and local test tags do
not. If a changed APK is distributed later within that cycle, increase the code
again. Ordinary local builds do not consume codes.

Check the actual published APK's package, version name, code, and signing
certificate rather than guessing from its filename or tag. Stop and resolve
missing or conflicting release information before choosing the baseline. A
different application ID has its own version history; a fork uses its own
published baseline.

## Notes, tags, and APKs

Prepare a dated `CHANGELOG.md` entry and `docs/RELEASE-NOTES-X.Y.Z.md` when preparing
a release. Cover the complete cycle, user-visible changes, compatibility, and
known limitations. Review the date and remove placeholders before release.

The version name, release notes, and Git tag must agree: version `X.Y.Z` uses
`vX.Y.Z`. The tag must identify the exact clean, committed source on the release
branch (`main`) used to build the reviewed APK. Never move a published tag or
replace a published APK with changed contents under the same version/code.
An exact rebuild of an existing release must preserve its source, version/code,
and byte-identical signed APK; otherwise prepare a new version.

Run [Building](BUILDING.md) and [Testing](TESTING.md), review the
[release gates](IMPLEMENTATION_PLAN.md#release-criteria), then follow
[Signing](SIGNING.md) for an explicitly authorized release. Version selection,
committing, signing, pushing tags, and publishing are separate actions; this
policy does not authorize publication. Preparation is manual; this repository
has no automatic version-bump or release-publishing helper.
