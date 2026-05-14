import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ─── Bundled (Tier-0) offline assets (auto-fetched before every build) ───────
//
// The APK ships with the bits that must be available with zero network and
// zero per-region downloads (plan §3 Tier 0):
//   * app/libs/brouter.jar                                   (routing engine)
//   * app/src/main/assets/bundled/brouter-profiles/*.brf,
//     app/src/main/assets/bundled/brouter-profiles/lookups.dat (BRouter profiles)
//   * app/src/main/assets/bundled/style.json,
//     app/src/main/assets/bundled/density-grid.bin,
//     app/src/main/assets/bundled/presets.json               (in git, ~520 KB)
//   * app/src/main/assets/bundled/skeleton.mbtiles           (built on demand,
//     plan §3 — global z0–z6 vector basemap, target ≈ 30 MB)
//
// Per-region detail packs (Tier 1/2) are NOT bundled — they're downloaded at
// runtime by PackDownloader (plan §4). This keeps the APK ≤ 100 MB.
//
// On a fresh checkout the brouter.jar + profiles don't exist yet, and
// `mergeDebugNativeLibs` fails with "File/directory does not exist:
// app/libs/brouter.jar". The fetch task below grabs them automatically the
// first time anyone runs ./gradlew. The skeleton mbtiles is opt-in (heavy
// build, see scripts/build-pack/skeleton-build.sh) and absence is handled
// gracefully at runtime — the map just falls back to the style.json
// background until a regional pack is installed.

val brouterVersion = "1.7.9"
val brouterZipUrl =
    "https://github.com/abrensch/brouter/releases/download/v$brouterVersion/" +
        "brouter-$brouterVersion.zip"

val brouterJar = file("libs/brouter.jar")
val brouterProfilesDir = file("src/main/assets/bundled/brouter-profiles")
val brouterProfileFiles = listOf("trekking.brf", "fastbike.brf", "car-fast.brf", "lookups.dat")
val skeletonMbtiles = file("src/main/assets/bundled/skeleton.mbtiles")

fun download(url: String, dest: File) {
    dest.parentFile.mkdirs()
    val tmp = File(dest.parentFile, "${dest.name}.partial")
    logger.lifecycle("    downloading ${dest.name} ← $url")
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 30_000
    conn.readTimeout = 30 * 60 * 1000
    conn.instanceFollowRedirects = true
    if (conn.responseCode !in 200..299) {
        error("Download failed (${conn.responseCode} ${conn.responseMessage}): $url")
    }
    conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
    if (dest.exists()) dest.delete()
    check(tmp.renameTo(dest)) { "Failed to rename ${tmp.name} → ${dest.name}" }
}

val fetchBrouterDist by tasks.registering {
    description = "Downloads the BRouter all-jar + routing profiles into bundled/."
    group = "offline-data"
    val needs = !brouterJar.exists() ||
        brouterProfileFiles.any { !File(brouterProfilesDir, it).exists() }
    onlyIf { needs }
    doLast {
        val tmpDir = layout.buildDirectory.dir("brouter-dist").get().asFile.also {
            it.deleteRecursively(); it.mkdirs()
        }
        val zipFile = File(tmpDir, "brouter.zip")
        download(brouterZipUrl, zipFile)
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val out = File(tmpDir, e.name)
                out.parentFile.mkdirs()
                zip.getInputStream(e).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        val unpacked = File(tmpDir, "brouter-$brouterVersion")
        brouterJar.parentFile.mkdirs()
        File(unpacked, "brouter-$brouterVersion-all.jar").copyTo(brouterJar, overwrite = true)
        brouterProfilesDir.mkdirs()
        for (name in brouterProfileFiles) {
            File(unpacked, "profiles2/$name").copyTo(File(brouterProfilesDir, name), overwrite = true)
        }
        logger.lifecycle("    BRouter $brouterVersion staged into bundled/.")
    }
}

// Opt-in: building the planet skeleton is multi-hour and needs ~80 GB free
// disk. We don't dependsOn this from preBuild so a fresh checkout still
// builds. Run `./gradlew :app:buildSkeletonMbtiles` (or the script directly)
// before producing a release APK so the world basemap actually ships.
val buildSkeletonMbtiles by tasks.registering {
    description = "Builds the global z0–z6 vector skeleton mbtiles via scripts/build-pack/skeleton-build.sh (heavy: ~3h, ~80GB disk for planet build)."
    group = "offline-data"
    onlyIf { !skeletonMbtiles.exists() || skeletonMbtiles.length() == 0L }
    doLast {
        val script = rootProject.file("scripts/build-pack/skeleton-build.sh")
        check(script.exists()) { "Missing $script" }

        val javaCheck = ProcessBuilder("java", "-version")
            .redirectErrorStream(true)
            .start()
        val javaOut = javaCheck.inputStream.bufferedReader().readText()
        check(javaCheck.waitFor() == 0) {
            "Planetiler needs JDK 21+ on PATH but `java -version` failed:\n$javaOut"
        }

        logger.lifecycle("    building $skeletonMbtiles (planet z0–z6 — multi-hour)…")
        val proc = ProcessBuilder("bash", script.absolutePath)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().forEachLine { logger.lifecycle("      $it") }
        val rc = proc.waitFor()
        check(rc == 0) { "skeleton-build.sh exited with status $rc" }
    }
}

val setupBundledAssets by tasks.registering {
    description = "Ensures the bundled (Tier-0) offline assets are present (BRouter jar + profiles). Skeleton is opt-in."
    group = "offline-data"
    dependsOn(fetchBrouterDist)
}

// Wire into preBuild so any gradle build task runs setupBundledAssets first.
// AGP creates `preBuild` lazily, so we hook on afterEvaluate.
afterEvaluate {
    tasks.named("preBuild").configure { dependsOn(setupBundledAssets) }
}

android {
    namespace = "com.example.emergency"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.emergency"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    androidResources {
        // Bundled skeleton mbtiles + brouter profiles + lookups.dat are
        // already binary/SQLite — AAPT compression buys nothing and forces
        // an unnecessary decompress step before we can copy them out to
        // filesDir. Keeping them uncompressed also lets OfflineAssets
        // fast-path the copy via AssetManager.openFd() +
        // FileChannel.transferTo() (sendfile(2)). .rd5 stays in the list
        // for the future bundled skeleton.rd5 (plan §3) even though no
        // .rd5 currently ships under assets/.
        noCompress += listOf("rd5", "mbtiles", "brf", "dat")
    }

    packaging {
        resources {
            // BRouter's all-jar bundles protobuf which carries license files
            // that collide with other AARs on Maven Central if we ever pull
            // one in. Excluding them up-front avoids future merge errors.
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/proguard/**",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.play.services.location)
    // Gemma LLM
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // Image loading
    implementation(libs.coil.compose)
    // Markdown rendering
    implementation(libs.compose.markdown)
    // Interactive map (MapLibre — uses Mapbox SDK 6 namespace)
    implementation(libs.maplibre.android.sdk)
    // Offline routing engine (BRouter, vendored as a JAR fetched at build
    // time by the setupBundledAssets task). The all-jar bundles protobuf +
    // osmosis-osm-binary so no transitive deps are needed.
    implementation(files("libs/brouter.jar"))
    // Localhost tile server: serves /{z}/{x}/{y}.pbf vector tiles from the
    // bundled skeleton + installed region packs to MapLibre, which doesn't
    // ship an mbtiles:// scheme handler.
    implementation(libs.nanohttpd)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}