# Project documentation

## Using the app

- [Usage guide](USAGE.md): adding books, listening tools, library management, and recovery.

## Developer guides

- [Building](BUILDING.md): toolchain, SDK setup, variants, and installation.
- [Testing](TESTING.md): checks, synthetic fixtures, and device verification.
- [Signing](SIGNING.md): unsigned artifacts, signing identity, and manual APK signing.
- [Forking](FORKING.md): license, independent app identity, and distribution considerations.
- [Demo library](demo/README.md): fictional screenshot content and reproducible media generation.
- [Documentation assets](assets/README.md): Obtainium badge source and license.

## Product and technical specifications

- [Design plan](DESIGN_PLAN.md): scope and expected user behavior.
- [UI specification](design/PROTOTYPE_TARGETS.md): current presentation and interaction rules.
- [Architecture](ARCHITECTURE.md): data, storage, inspection, and playback boundaries.
- [Security and permissions](SECURITY_AND_PERMISSIONS.md): offline contract and trust boundaries.
- [Implementation plan](IMPLEMENTATION_PLAN.md): milestones and release criteria.
- [Architecture decisions](decisions/): dependency reviews and lasting tradeoffs.

Shared documentation describes reproducible procedures and required behavior.
Machine paths, device inventories, test outputs, dated reports, design exploration,
user notes, and live status/acceptance tracking belong in the gitignored
`docs/local/` directory. That directory is optional in a fresh checkout and must
not be required by builds, tests, or shared documentation links. Keep durable
requirements here when moving local evidence out of tracked files.
