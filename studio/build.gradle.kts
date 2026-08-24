plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

/**
 * The SQL Server studio, drawn natively inside JCode's own process.
 *
 * What ships is `classes.dex`, not the APK the build produces around it: this plugin owns no
 * resources — every icon it draws is a vector built in code — so there is no resource table for
 * JCode's `addAssetPath` to attach.
 *
 * **The dependency rules are the ABI.** Anything JCode already ships is `compileOnly`: the plugin
 * must resolve those classes from JCode at runtime, because the composition it returns is spliced
 * into JCode's own and two Compose runtimes in one process do not interoperate. Nothing here is
 * bundled, which is why this dex is small — it is this plugin's code and nothing else.
 */
android {
    namespace = "dev.blamspot.jcode.ext.sql"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // Never installed as an app; this only names the archive the dex comes out of.
        applicationId = "dev.blamspot.jcode.ext.sql"
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // JCode does not minify either, and an obfuscated entry class cannot be found by name.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JCode's, resolved from JCode at runtime. Versions must match what JCode ships.
    compileOnly(files("libs/jcode-ext-api-abi7.jar"))
    // JCode's design system — the spacing scale, the compact buttons, the icon vocabulary and the
    // semantic colours the Explorer badges use. compileOnly like Compose: these classes come from
    // JCode at runtime, so the panel is drawn out of the same parts the rest of the IDE is, and a
    // change to the app's density or palette moves this panel with it.
    compileOnly(files("libs/jcode-design.jar"))
    compileOnly(platform("androidx.compose:compose-bom:2025.01.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
    compileOnly("androidx.compose.material:material-icons-extended")
    compileOnly("androidx.core:core-ktx:1.15.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
