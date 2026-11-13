plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Подпись. Если заданы переменные SITESCOPE_SIGNING_P12 / SITESCOPE_KEYSTORE_PASSWORD (как в прежнем
// build-direct-release.sh) — APK подписывается исходным ключом и ставится поверх 0.39.
// Иначе используется бета-ключ из репозитория, а приложение получает отдельный id и ставится рядом.
val originalKey: String? = System.getenv("SITESCOPE_SIGNING_P12")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "pro.sitescope.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "pro.sitescope.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1_000_031
        versionName = "1.0.0-beta31"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("beta") {
            storeFile = rootProject.file("signing/beta.keystore")
            storePassword = "sitescope-beta"; keyAlias = "beta"; keyPassword = "sitescope-beta"
            storeType = "PKCS12"
        }
        if (originalKey != null) create("original") {
            storeFile = file(originalKey)
            storePassword = System.getenv("SITESCOPE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SITESCOPE_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "unifiedcore"
            keyPassword = System.getenv("SITESCOPE_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("SITESCOPE_KEYSTORE_PASSWORD")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            // R8 выключен намеренно: нативные Xray/HEV вызывают Java-классы по именам.
            isMinifyEnabled = false
            if (originalKey != null) {
                signingConfig = signingConfigs.getByName("original")
            } else {
                signingConfig = signingConfigs.getByName("beta")
                applicationIdSuffix = ".next"
                versionNameSuffix = "-side"
            }
        }
        debug { applicationIdSuffix = ".debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    }
    lint { checkReleaseBuilds = true; abortOnError = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.compilerArgs.add("-Xlint:-options") }
tasks.withType<Test>().configureEach {
    // Robolectric's TLS server fixture on JDK 17 uses InetAddress through reflection.
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
}

// ── Обход DPI: исходники byedpi (MIT) собираются NDK-clang в исполняемый файл libciadpi_exec.so (arm64).
// Он упаковывается как jni-библиотека, распаковывается в nativeLibraryDir и запускается отдельным процессом.
val ciadpiOut = layout.buildDirectory.dir("ciadpi/jniLibs/arm64-v8a")
val buildCiadpi = tasks.register("buildCiadpi") {
    val sources = layout.projectDirectory.dir("src/main/cpp/byedpi")
    inputs.dir(sources)
    outputs.dir(ciadpiOut)
    doLast {
        val roots = listOfNotNull(System.getenv("ANDROID_NDK_HOME"), System.getenv("ANDROID_NDK_LATEST_HOME"), System.getenv("ANDROID_NDK_ROOT"))
            .map { File(it) }.toMutableList()
        listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).forEach { sdk ->
            File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.let { roots.addAll(it) }
        }
        val os = System.getProperty("os.name").lowercase()
        val host = when { os.contains("win") -> "windows-x86_64"; os.contains("mac") -> "darwin-x86_64"; else -> "linux-x86_64" }
        val clang = roots.map { File(it, "toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android28-clang" + if (os.contains("win")) ".cmd" else "") }
            .firstOrNull { it.exists() } ?: throw GradleException("Не найден Android NDK (ANDROID_NDK_HOME): он нужен для сборки byedpi")
        val out = ciadpiOut.get().asFile.also { it.mkdirs() }
        val files = listOf("conev", "desync", "extend", "main", "mpool", "packets", "proxy").map { File(sources.asFile, "$it.c").absolutePath }
        val command = listOf(clang.absolutePath, "-O2", "-std=c99", "-D_DEFAULT_SOURCE", "-fPIE", "-pie", "-s", "-Wno-unused",
            "-Wl,-z,max-page-size=16384", "-I" + sources.asFile.absolutePath, "-o", File(out, "libciadpi_exec.so").absolutePath) + files
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("Сборка byedpi не удалась:\n$log")
    }
}
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("ciadpi/jniLibs"))
tasks.named("preBuild") { dependsOn(buildCiadpi) }

dependencies {
    implementation(files("libs/jsch-0.2.16.jar", "libs/libv2ray.jar"))

    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")   // OCR эмблемы канала (данные rus.traineddata в assets)

    implementation("androidx.webkit:webkit:1.12.1")                           // ProxyController: WebView через локальный прокси Onion/I2P

    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
