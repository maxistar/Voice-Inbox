import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "me.maxistar.voiceinbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.maxistar.voiceinbox"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    kotlinOptions {
        jvmTarget = "11"
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/*.so"
        }
    }
    ndkVersion = "30.0.14904198"
}

dependencies {

    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val ortNative: Configuration by configurations.creating
dependencies {
    ortNative("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

val extractOrt by tasks.registering(Copy::class) {
    from(ortNative.elements.map { files -> files.map { zipTree(it) } })
    into(layout.buildDirectory.dir("ort-extracted"))
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    dependsOn(extractOrt)
    inputs.files(
        rootProject.fileTree("src") { include("**/*.rs") },
        rootProject.file("Cargo.toml"),
        rootProject.file("Cargo.lock"),
        rootProject.file("build.rs"),
        rootProject.fileTree("transcribe-rs") { include("**/*.rs", "**/Cargo.toml") },
    )
    workingDir = rootProject.projectDir

    val ndkDir = project.findProperty("ndk.dir")?.toString()
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK")
        ?: android.ndkDirectory.absolutePath
    environment("ANDROID_NDK_HOME", ndkDir)

    val extractDir = layout.buildDirectory.dir("ort-extracted").get().asFile
    environment("ORT_LIB_LOCATION", File(extractDir, "jni/arm64-v8a").absolutePath)
    environment("ORT_INCLUDE_DIR", File(extractDir, "headers").absolutePath)

    val jniLibsDir = project.file("src/main/jniLibs")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release",
    )

    doLast {
        val prebuiltDir = file("$ndkDir/toolchains/llvm/prebuilt")
        val libcpp = prebuiltDir.listFiles()
            ?.map { File(it, "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so") }
            ?.firstOrNull(File::exists)
            ?: throw GradleException("libc++_shared.so not found under ${prebuiltDir.absolutePath}")
        val destination = File(jniLibsDir, "arm64-v8a").apply { mkdirs() }
        libcpp.copyTo(File(destination, "libc++_shared.so"), overwrite = true)
    }
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}
