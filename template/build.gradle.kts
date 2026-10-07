// template — the copy-me instrument. The five-minute recipe (PLAN M2):
//   1. copy this directory, rename it
//   2. set applicationId + label below
//   3. drop your built artifact at src/main/assets/artifact.html
//   4. list the plugins your artifact needs in InstrumentActivity (and depend on
//      their modules here — the dependency is what carries their permissions)
//   5. gradlew :<yours>:assembleDebug
// Nothing else to touch. core brings lead-acid.js and the posture.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.template"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.gentropic.leadacid.template"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }

    // SPEC §6: the keystore never enters the repo. A root keystore.properties
    // (gitignored) signs release; without one, release builds are UNSIGNED.
    // See docs/signing.md.
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

dependencies {
    implementation(project(":core"))
    // One plugin, to show the shape. Add :plugin-sensor / :plugin-attest / …
    // here AND in InstrumentActivity.plugins; remove both to drop a capability.
    implementation(project(":plugin-share"))
}
