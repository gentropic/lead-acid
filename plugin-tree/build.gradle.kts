// plugin-tree — a folder the user picks, served as a directory tree (SAF,
// ACTION_OPEN_DOCUMENT_TREE). No permission: the pick is the grant, persisted
// per folder until the user revokes it. The honest project-folder door for
// instruments like micro — the WebView defines showDirectoryPicker but aborts it.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.tree"
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
