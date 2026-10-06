// App module build script. Owner: engine-build (see 工程布局与版本锁定.md §3).
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------------------------
// Rust cross-compilation (工程布局与版本锁定.md §4.3).
//
// The NDK's clang is wired in as the linker for each Android target instead of depending on
// cargo-ndk, so the build needs no extra tooling and stays reproducible offline once the NDK is
// installed. `cargoNdkBuild` writes libarttodo_core.so into build/generated/jniLibs/<abi>/.
// ---------------------------------------------------------------------------------------------
val abiTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)
val ndkVersionPin = "27.2.12479018"
// minSdk drives the NDK API level directory in the clang wrapper name.
val ndkApiLevel = 26
val rustRoot = rootProject.layout.projectDirectory.dir("rust")
// Host cdylib produced by `generateUniffiBindings` (see the note on that task).
val hostLibraryDir = rootProject.layout.projectDirectory.dir("rust/build/rust-host/release")
val cargoExecutable = providers.environmentVariable("CARGO").getOrElse("cargo")
val ndkHostTag = if (OperatingSystem.current().isWindows) "windows-x86_64" else "linux-x86_64"
val ndkToolchainBin = providers.environmentVariable("ANDROID_HOME").orNull
    ?.let { "$it/ndk/$ndkVersionPin/toolchains/llvm/prebuilt/$ndkHostTag/bin" }

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the host cdylib and generates the Kotlin UniFFI bindings from it."
    // library mode must run inside the crate directory (工程布局与版本锁定.md §4.1 constraint 1)
    workingDir = rustRoot.asFile
    val outDir = layout.buildDirectory.dir("generated/uniffi").get().asFile.absolutePath
    // The binding is generated from the *host* cdylib: no NDK needed, and reproducible.
    inputs.dir(rustRoot.dir("src"))
    inputs.files(
        rustRoot.file("Cargo.toml"),
        rustRoot.file("Cargo.lock"),
        rustRoot.file("rust-toolchain.toml"),
        rustRoot.file("uniffi.toml"),
    )
    outputs.dir(outDir)
    if (OperatingSystem.current().isWindows) {
        commandLine(
            "cmd", "/c",
            "set CARGO_TARGET_DIR=build\\rust-host&& " +
                "$cargoExecutable build --release --lib --offline && " +
                "$cargoExecutable run --release --bin uniffi-bindgen --offline -- " +
                "generate --library build\\rust-host\\release\\arttodo_core.dll " +
                "--language kotlin --no-format --out-dir \"$outDir\"",
        )
    } else {
        commandLine(
            "sh", "-c",
            "CARGO_TARGET_DIR=build/rust-host $cargoExecutable build --release --lib --offline && " +
                "CARGO_TARGET_DIR=build/rust-host $cargoExecutable run --release --bin uniffi-bindgen --offline -- " +
                "generate --library build/rust-host/release/libarttodo_core.so " +
                "--language kotlin --no-format --out-dir \"$outDir\"",
        )
    }
}

val cargoNdkBuild by tasks.registering {
    group = "rust"
    description = "Cross-compiles libarttodo_core.so for every declared ABI into generated/jniLibs."
    inputs.dir(rustRoot.dir("src"))
    inputs.files(
        rustRoot.file("Cargo.toml"),
        rustRoot.file("Cargo.lock"),
        rustRoot.file("rust-toolchain.toml"),
    )
    val jniRoot = layout.buildDirectory.dir("generated/jniLibs").get().asFile
    outputs.dir(jniRoot)
    // Fail loudly instead of silently shipping an APK without the domain core.
    doFirst {
        if (ndkToolchainBin == null) {
            throw GradleException(
                "ANDROID_HOME is not set, so the NDK linker cannot be located. " +
                    "Set ANDROID_HOME (e.g. E:\\\\Android\\\\Sdk) before building.",
            )
        }
        val ndkDir = File(ndkToolchainBin).parentFile.parentFile.parentFile
        if (!ndkDir.isDirectory) {
            throw GradleException(
                "Android NDK $ndkVersionPin not found at ${ndkDir.absolutePath}. " +
                    "Install it with: sdkmanager \"ndk;$ndkVersionPin\"",
            )
        }
    }
    doLast {
        val toolchain = File(ndkToolchainBin!!)
        for ((abi, target) in abiTargets) {
            val clang = toolchain.resolve("${target}${ndkApiLevel}-clang")
            val wrapper = if (OperatingSystem.current().isWindows) File("${clang.absolutePath}.cmd") else clang
            if (!wrapper.isFile) {
                throw GradleException("NDK clang wrapper missing: ${wrapper.absolutePath}")
            }
            val envKey = "CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER"
            val destDir = File(jniRoot, abi)
            destDir.mkdirs()
            val process = ProcessBuilder(
                cargoExecutable, "build", "--release", "--lib", "--offline", "--target", target,
            )
                .directory(rustRoot.asFile)
                .inheritIO()
                .apply {
                    environment()[envKey] = wrapper.absolutePath
                    environment()["CARGO_TARGET_DIR"] =
                        layout.buildDirectory.dir("rust-android").get().asFile.absolutePath
                }
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("cargo build for $target failed with exit code $exitCode")
            }
            val built = layout.buildDirectory
                .dir("rust-android/$target/release")
                .get().asFile
                .resolve("libarttodo_core.so")
            if (!built.isFile) {
                throw GradleException("Cross-compiled library missing: ${built.absolutePath}")
            }
            built.copyTo(File(destDir, "libarttodo_core.so"), overwrite = true)
        }
    }
}

android {
    namespace = "app.arttodo"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.arttodo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only ABIs that are actually built and tested (工程布局与版本锁定.md §6.3).
        ndk { abiFilters += abiTargets.keys }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // Cross-compiled .so land here; the directory is generated, never committed.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
    // Generated UniFFI bindings are source, not checked in.
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/uniffi"))

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // `generateUniffiBindings` runs cargo with the crate directory as its working
            // directory, so the host cdylib lands inside `rust/`. Point JNA at it: this is what lets
            // the JVM tests exercise the real domain core without a device.
            all { test ->
                test.systemProperty("jna.library.path", hostLibraryDir.asFile.absolutePath)
            }
        }
    }

    // The pinned Compose BOM and friends are intentional; upgrading needs platforms;android-37 and
    // AGP >= 9.1.0 (工程布局与版本锁定.md §5.2).
    lint {
        warningsAsErrors = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    buildTypes.getByName("debug") { matchingFallbacks += listOf("release") }
}

// Bindings and native libraries must exist before Kotlin compiles / the APK is packaged.
tasks.named("preBuild") { dependsOn(generateUniffiBindings) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(cargoNdkBuild)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(cargoNdkBuild)
}
tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
    dependsOn(cargoNdkBuild)
}
tasks.withType<Test>().configureEach { dependsOn(generateUniffiBindings) }

ksp {
    // app/schemas/** must be committed: migrations and backup compatibility depend on it
    // (工程布局与版本锁定.md §1). Room's Gradle plugin is not applied, so this is set directly.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    // Provides the host activity Compose UI tests launch into; debug-only, like ui-tooling.
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Must be an @aar so libjnidispatch.so is packaged (工程布局与版本锁定.md §4.2).
    implementation(libs.jna.aar) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // Plain JNA jar for the host JVM: the @aar above only carries Android natives.
    testImplementation(libs.jna.jar)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
