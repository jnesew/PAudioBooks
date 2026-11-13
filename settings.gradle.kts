pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PAudioBooks"
include(":app")

// Read only the simple pinned [versions] entries before plugin classpaths resolve.
// Project catalog accessors do not exist at that stage; TOML remains the source of truth.
val securityVersions = Regex("""(?m)^([\w-]+)\s*=\s*"([^"]+)"\s*$""")
    .findAll(file("gradle/libs.versions.toml").readText().substringAfter("[versions]").substringBefore("\n["))
    .associate { it.groupValues[1] to it.groupValues[2] }
val securityPins = mapOf(
    "org.jetbrains.kotlin:kotlin-gradle-plugin" to "kotlin",
    "com.google.guava:guava" to "guava",
    "org.apache.commons:commons-lang3" to "commons-lang3",
    "org.apache.httpcomponents:httpclient" to "httpclient",
    "org.bitbucket.b_c:jose4j" to "jose4j",
    "org.bouncycastle:bcprov-jdk18on" to "bouncycastle",
    "org.bouncycastle:bcpkix-jdk18on" to "bouncycastle",
    "org.bouncycastle:bcutil-jdk18on" to "bouncycastle",
    "org.jdom:jdom2" to "jdom2"
).mapValues { (_, alias) -> requireNotNull(securityVersions[alias]) }
gradle.beforeProject {
    buildscript.configurations.configureEach {
        resolutionStrategy.eachDependency {
            securityPins["${requested.group}:${requested.name}"]?.let { version ->
                useVersion(if (requested.group == "com.google.guava" && requested.name == "guava") "$version-jre" else version)
                because("Reviewed security patch; docs/decisions/0008-security-updates.md")
            }
        }
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            securityPins["${requested.group}:${requested.name}"]?.let { version ->
                useVersion(if (requested.group == "com.google.guava" && requested.name == "guava") {
                    "$version-${if (requested.version?.endsWith("-android") == true) "android" else "jre"}"
                } else version)
                because("Reviewed security patch; docs/decisions/0008-security-updates.md")
            }
        }
    }
}
