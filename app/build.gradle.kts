plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.duck.notes"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.duck.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "app.duck.notes.settings.SettingsTestRunner"
        testInstrumentationRunnerArguments["notClass"] = "app.duck.notes.startup.WelcomeStartupTest,app.duck.notes.startup.WelcomeStartupGateTest,app.duck.notes.startup.OfflineActivityTest,app.duck.notes.data.repository.BackupTransportTest"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // TextFieldBuffer.addStyle — the styled OutputTransformation the editor is built on.
        freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // collectAsStateWithLifecycle — the shell must stop collecting Room flows when backgrounded.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Disclosure chevrons for the sidebar tag tree. material3 pulls this at runtime only.
    implementation(libs.androidx.material.icons.core)

    // Attachment strip thumbnails. Local files only — no coil-network artifact needed.
    implementation(libs.coil.compose)

    implementation(libs.hilt.android)
    // hiltViewModel() only. No NavHost — Phase 6 derives panes from state, not from routes.
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Bundled SQLite: Android's system SQLite does not reliably ship the FTS5 module.
    // Instrumented tests get this at runtime because the androidTest APK is instrumented into the
    // app's own process — NOT by configuration inheritance. androidTestImplementation does not
    // extend implementation, which is also why the Compose BOM has to be declared separately below.
    implementation(libs.androidx.sqlite.bundled)

    // Cold path only — export and AST queries. The editor's per-keystroke path never calls these.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.footnotes)
    implementation(libs.commonmark.ext.yaml.front.matter)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
