// plugin-intent — allowlisted hand-offs to other apps (calendar, clock, map,
// dialer, sms/mail, contacts). Brings the one install-time permission
// (SET_ALARM) and the <queries> block that package visibility needs; an
// instrument that doesn't include this module can't start any of them.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.intent"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
}
