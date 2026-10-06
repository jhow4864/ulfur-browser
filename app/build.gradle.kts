import com.android.apksig.ApkVerifier
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.jar.JarFile

plugins {
    id("com.android.application")
}

val geckoviewVersion = "157.0.20260924084938"   // latest stable (release channel) on maven.mozilla.org
// One ABI per build keeps the APK sensible (~110 MB). Default arm64-v8a (phones);
// `-Pbeast.abi=x86_64` builds an emulator/Chromebook APK, `armeabi-v7a` for old 32-bit phones.
val beastAbi = (findProperty("beast.abi") as String?) ?: "arm64-v8a"

// Release signing: read from a keystore.properties that lives OUTSIDE the source tree
// (default ../beast-keys/keystore.properties, override with -Pbeast.keystoreProperties=/path).
// Keys: storeFile, storePassword, keyAlias, keyPassword.
//
// STRICT: there is no debug-key fallback for release. 2.3.4-2.3.8 were silently debug-signed when the key was
// missing, which forced every user to reinstall. Now any release packaging (assembleRelease / bundleRelease)
// fails up front if the key is missing or unusable, and the packaged APK/AAB is checked afterwards: it must not
// be signed with "CN=Android Debug" and must match ulfur.expectedCertSha256 (blank = skip the pin).
// Debug builds never need the key. Messages name files and missing keys only, never passwords.
val keystorePropsFile = rootProject.file(
    (findProperty("beast.keystoreProperties") as String?) ?: "../beast-keys/keystore.properties"
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseKey = keystorePropsFile.isFile &&
    signingKeys.all { !keystoreProps.getProperty(it).isNullOrBlank() } &&
    file(keystoreProps.getProperty("storeFile")).isFile
// Real release certificate (SHA-256 of the DER cert). Override with -Pulfur.expectedCertSha256=..., blank disables.
val expectedCertSha256 = ((findProperty("ulfur.expectedCertSha256") as String?)
    ?: "1f04b66b6d0bdf3c6e39640c23b95e517d7a144549385885e1789e3b92f6dd16")
    .lowercase().replace(":", "").replace(" ", "").trim()

/** Why release signing can't work, or null if the key is present and opens. Never includes secrets. */
fun releaseSigningProblem(): String? {
    if (!keystorePropsFile.isFile) return "keystore properties file not found: ${keystorePropsFile.absolutePath}"
    val missing = signingKeys.filter { keystoreProps.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) return "${keystorePropsFile.absolutePath} is missing: ${missing.joinToString()}"
    val store = file(keystoreProps.getProperty("storeFile"))
    if (!store.isFile) return "keystore file not found: ${store.absolutePath} (storeFile in ${keystorePropsFile.name})"
    val alias = keystoreProps.getProperty("keyAlias")
    return try {
        val ks = KeyStore.getInstance(store, keystoreProps.getProperty("storePassword").toCharArray())
        when {
            !ks.containsAlias(alias) -> "keystore ${store.name} has no key with alias '$alias'"
            ks.getKey(alias, keystoreProps.getProperty("keyPassword").toCharArray()) == null -> "alias '$alias' in ${store.name} is not a private key"
            else -> null
        }
    } catch (e: Exception) {
        // Exception class only: messages from keystore loading never contain the password, but stay minimal.
        "keystore ${store.name} could not be opened with the configured storePassword/keyPassword (${e.javaClass.simpleName})"
    }
}

android {
    namespace = "com.jamhowman.beastbrowser"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }   // GeckoView 157 requires 37.1
    }

    defaultConfig {
        applicationId = "com.jamhowman.beastbrowser"
        minSdk = 26
        targetSdk = 37
        // 2.3.8 shipped as versionCode 13; rebuilt releases must sit above it to install as an update.
        versionCode = 15
        versionName = "2.4.1"
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
            // Release key only. Without it the variant is left unsigned and validateReleaseSigning fails the build.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
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

// ---------------------------------------------------------------------------- strict release signing

/** Fails release packaging before anything is built when the release key is missing or unusable. */
abstract class ValidateReleaseSigning : DefaultTask() {
    @get:Input abstract val problem: Property<String>   // "" = OK

    @TaskAction fun validate() {
        val p = problem.get()
        if (p.isNotEmpty()) throw GradleException(
            "Release signing is not configured: $p\n" +
                "Release builds never fall back to the debug key. Provide keystore.properties (storeFile, storePassword, " +
                "keyAlias, keyPassword) at ../beast-keys/keystore.properties or pass -Pbeast.keystoreProperties=/path."
        )
    }
}

/** Checks the signer of the packaged release APK (APK Signature Scheme via apksig) or AAB (JAR signature). */
abstract class VerifyReleaseSigner : DefaultTask() {
    @get:InputFiles @get:Optional abstract val apkDir: DirectoryProperty
    @get:InputFiles @get:Optional abstract val bundle: RegularFileProperty
    @get:Internal abstract val loader: Property<BuiltArtifactsLoader>
    @get:Input abstract val expectedSha256: Property<String>

    @TaskAction fun verify() {
        val files = buildList {
            if (apkDir.isPresent) {
                val built = loader.get().load(apkDir.get()) ?: throw GradleException("No release APK found in ${apkDir.get()}")
                built.elements.forEach { add(File(it.outputFile)) }
            }
            if (bundle.isPresent) add(bundle.get().asFile)
        }
        if (files.isEmpty()) throw GradleException("Nothing to verify")
        files.forEach { f ->
            val certs: List<X509Certificate> = if (f.name.endsWith(".aab")) aabSigners(f) else apkSigners(f)
            if (certs.isEmpty()) throw GradleException("${f.name} is not signed")
            certs.forEach { c ->
                val dn = c.subjectX500Principal.name
                val sha = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }
                if (dn.contains("CN=Android Debug", ignoreCase = true)) {
                    throw GradleException("${f.name} is signed with the DEBUG certificate ($dn). Refusing to ship it.")
                }
                val pin = expectedSha256.get()
                if (pin.isNotEmpty() && sha != pin) {
                    throw GradleException("${f.name} signer SHA-256 $sha does not match ulfur.expectedCertSha256 $pin ($dn)")
                }
                logger.lifecycle("Release signer OK: ${f.name} -> $dn, SHA-256 $sha")
            }
        }
    }

    private fun apkSigners(f: File): List<X509Certificate> {
        val result = ApkVerifier.Builder(f).build().verify()
        if (!result.isVerified) throw GradleException("${f.name} signature does not verify: ${result.errors.joinToString()}")
        return result.signerCertificates
    }

    private fun aabSigners(f: File): List<X509Certificate> = JarFile(f, true).use { jar ->
        val buf = ByteArray(8192)
        val signers = LinkedHashSet<X509Certificate>()
        jar.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }.forEach { e ->
            jar.getInputStream(e).use { while (it.read(buf) >= 0) Unit }   // must be read fully to check the digest
            val cs = e.codeSigners ?: throw GradleException("${f.name}: unsigned entry ${e.name}")
            cs.forEach { s -> (s.signerCertPath.certificates.firstOrNull() as? X509Certificate)?.let(signers::add) }
        }
        signers.toList()
    }
}

val validateReleaseSigning = tasks.register<ValidateReleaseSigning>("validateReleaseSigning") {
    group = "verification"
    description = "Fails if the release signing key is missing or unusable (no debug fallback)."
    problem.set(provider { releaseSigningProblem().orEmpty() })
}
// Release packaging needs the key; run the check first so a missing key fails in seconds, not after R8.
tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle") }.configureEach {
    dependsOn(validateReleaseSigning)
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { mustRunAfter(validateReleaseSigning) }

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val apkCheck = tasks.register<VerifyReleaseSigner>("verifyReleaseApkSigner") {
            group = "verification"
            description = "Verifies the release APK is signed with the release key (not CN=Android Debug)."
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            expectedSha256.set(expectedCertSha256)
        }
        val bundleCheck = tasks.register<VerifyReleaseSigner>("verifyReleaseBundleSigner") {
            group = "verification"
            description = "Verifies the release AAB is signed with the release key (not CN=Android Debug)."
            bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
            expectedSha256.set(expectedCertSha256)
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(apkCheck) }
        tasks.matching { it.name == "bundleRelease" }.configureEach { dependsOn(bundleCheck) }
    }
}
