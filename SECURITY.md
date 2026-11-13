# Security policy

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/jnesew/PAudioBooks/security/advisories/new)
when it is enabled for this repository. If the private form is unavailable, open
an issue asking the maintainer to enable private reporting, without including
vulnerability details. Do not put exploit instructions, private files or sensitive
diagnostics in a public issue.

Include the app version, Android version, affected feature, expected impact and
minimal reproduction steps. Use synthetic audio and redacted examples. Never
send commercial audiobooks, progress exports containing personal notes, a raw
library database, signing keys, access tokens or private document URIs.

Ordinary playback, format and UI problems can use
[public issues](https://github.com/jnesew/PAudioBooks/issues), with the same privacy
precautions. The app has no remote crash reporting, so reports are user initiated.

## Updates and scope

Security fixes target the latest published release. Older releases do not have
separate maintenance branches. There is no in-app update checker; obtain updates
from this repository's releases or your chosen release-download tool.

PAudioBooks uses local, user-selected audio without app networking or broad storage
permission. Its trust boundaries and permission budget are in
[Security and permissions](docs/SECURITY_AND_PERMISSIONS.md). The Android version,
device codecs and document providers also affect security. Known build-tool
dependency caveats are documented in
[decision 0008](docs/decisions/0008-security-updates.md); this policy does not claim
that every dependency or device is free of vulnerabilities.
