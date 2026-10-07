// lamina — the windowed data viewer as a lead-acid instrument: open any file,
// however large, scroll / filter / sort / derive / export it, never resident. Artifact built in the monorepo (node build.js
// --target=lamina → lamina.html), synced from leadacid.artifactsDir at build time.
// POSTURE A: lamina is Sealed (connect-src 'none'); LaminaActivity opts into
// shellConnect so the shell opens that one directive to 'self' at serve time —
// the app has no INTERNET permission, so nothing can leave the device anyway.
// Plugins: intake ("open with lamina" from Files, a shared .csv/.dm/.parquet …), share (exports out).
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val artifactsDir = providers.gradleProperty("leadacid.artifactsDir").get()
val artifactSource = rootProject.file("$artifactsDir/lamina.html")

android {
    namespace = "org.gentropic.lamina"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.gentropic.lamina"
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
        // A debug build installs BESIDE the real instrument (different id), so
        // developing never clobbers — or is blocked by — the signed install.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
    description = "Copy the built lamina.html from the monorepo into assets/artifact.html"
    doFirst {
        if (!artifactSource.exists()) throw GradleException(
            "lamina artifact not found at $artifactSource — build it in the monorepo " +
                "(node build.js --target=lamina) or point -Pleadacid.artifactsDir at it",
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
    implementation(project(":plugin-intake"))   // "open with lamina": shared / opened files arrive as fs tokens (mmap reads)
    implementation(project(":plugin-share"))    // exports out: publish to Downloads + the share sheet (brings the FileProvider)
}
