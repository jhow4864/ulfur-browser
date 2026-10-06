import java.util.Properties

plugins {
    id("com.android.application")
}

val geckoviewVersion = "157.0.20260924084938"   // latest stable (release channel) on maven.mozilla.org
// One ABI per build keeps the APK sensible (~110 MB). Default arm64-v8a (phones);
// `-Pbeast.abi=x86_64` builds an emulator/Chromebook APK, `armeabi-v7a` for old 32-bit phones.
val beastAbi = (findProperty("beast.abi") as String?) ?: "arm64-v8a"

// Release signing: read from a keystore.properties that lives OUTSIDE the source tree
// (default ../beast-keys/keystore.properties, override with -Pbeast.keystoreProperties=/path).
// Keys: storeFile, storePassword, keyAlias, keyPassword. If absent, release falls back to the debug key.
val keystorePropsFile = rootProject.file(
    (findProperty("beast.keystoreProperties") as String?) ?: "../beast-keys/keystore.properties"
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).isFile } == true

android {
    namespace = "com.jamhowman.beastbrowser"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }   // GeckoView 157 requires 37.1
    }

    defaultConfig {
        applicationId = "com.jamhowman.beastbrowser"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "2.3.3"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += beastAbi }
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking. GeckoView ships its own consumer keep rules for JNI.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Beast release key when keystore.properties is present, else the local debug key.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // uBlock Origin ships a `_locales` folder; aapt's default pattern drops dirs starting with "_".
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
        noCompress += listOf("xpi")
    }

    packaging {
        jniLibs { useLegacyPackaging = true }   // compress libxul (~150 MB raw) inside the APK
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                it.systemProperty("preview.dir", rootProject.file("../beast-browser-screens").absolutePath)
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-$beastAbi:$geckoviewVersion")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// In-app updater (com.jamhowman.beastbrowser.update): the GitHub repo to check, from GITHUB_REPO in
// gradle.properties (or -PGITHUB_REPO=owner/name). Blank / OWNER/REPO disables the updater.
val updateRepo = ((findProperty("GITHUB_REPO") as String?) ?: "").trim()
require(updateRepo.isEmpty() || updateRepo.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+"))) {
    "GITHUB_REPO must look like owner/name, got '$updateRepo'"
}
android {
    defaultConfig {
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
}
