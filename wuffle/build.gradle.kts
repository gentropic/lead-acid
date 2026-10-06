// wuffle — the geological compass, as an instrument (the first real app through
// the packaging recipe). Its artifact is BUILT in the monorepo
// (auditable: `node build.js --target=wuffle` → wuffle.html) and synced into
// assets at build time from `leadacid.artifactsDir` (gradle.properties, override
// with -Pleadacid.artifactsDir=…). The synced file is gitignored: an input, not
// a source of this repo.
//
// wuffle v2 reads the FUSED rotation vector through the sensor plugin (no
// permission — not a body sensor), so its merged manifest still carries no
// permission and no provider; the dependency below and WuffleActivity.plugins
// are the two lines that agree on it.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val artifactsDir = providers.gradleProperty("leadacid.artifactsDir").get()
val artifactSource = rootProject.file("$artifactsDir/wuffle.html")

android {
    namespace = "org.gentropic.wuffle"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.gentropic.wuffle"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }

    // SPEC §6: the keystore never enters the repo. `keystore.properties` at the
    // root (gitignored) supplies it; without one, release builds come out
    // UNSIGNED (apk) / unsigned (aab) — installable nowhere, by design.
    val ksProps = rootProject.file("keystore.properties")
    if (ksProps.exists()) {
        val p = Properties().apply { ksProps.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
                // v3 carries the proof-of-rotation lineage (docs/signing.md) —
                // AGP leaves it off by default; we want it from the first build.
                // (minSdk 29 ≥ 28, so AGP then signs v3 alone — v2 is redundant.)
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksProps.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

val syncArtifact by tasks.registering(Copy::class) {
    description = "Copy the built wuffle.html from the monorepo into assets/artifact.html"
    doFirst {
        if (!artifactSource.exists()) throw GradleException(
            "wuffle artifact not found at $artifactSource — build it in the monorepo " +
                "(node build.js --target=wuffle) or point -Pleadacid.artifactsDir at it",
        )
    }
    from(artifactSource)
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "artifact.html" }
}
tasks.matching { it.name.startsWith("generate") || it.name.startsWith("merge") }.configureEach {
    dependsOn(syncArtifact)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-sensor"))   // v2: the fused rotation vector + compass accuracy
}
