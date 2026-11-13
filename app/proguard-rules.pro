# PAudioBooks ProGuard / R8 Rules
#
# AndroidX dependencies (Room, Media3, Compose, Coroutines) supply consumer
# ProGuard rules embedded in their respective AARs. The application build uses
# standard default consumer rules without blanket keep or -dontwarn directives.
#
# Application-specific keep rules are added only when justified by proven
# optimization or serialization failures during build or runtime verification.
