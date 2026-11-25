import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties
import kotlin.math.sign

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val enableReleaseShrinker = providers.gradleProperty("lastchat.release.minify")
    .map(String::toBoolean)
    .orElse(true)

val webUiDir = rootProject.file("web-ui")
val webUiBuildDir = File(webUiDir, "build/client")
val catalogDir = rootProject.file("catalog")
val generatedCatalogAssetsDir = layout.buildDirectory.dir("generated/assets/catalog")

val prepareBundledCatalogAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies the bundled model catalog into app assets without shadowing existing assets."

    from(catalogDir) {
        include("lastchat_catalog.json")
        include("icons/**")
        eachFile {
            val appAsset = layout.projectDirectory.file("src/main/assets/$path").asFile
            if (appAsset.exists()) {
                exclude()
            }
        }
    }
    into(generatedCatalogAssetsDir)
}

val buildWebUi by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the React web UI bundle used by the Android app."
    workingDir = webUiDir

    inputs.dir(File(webUiDir, "app"))
    inputs.dir(File(webUiDir, "public"))
    inputs.file(File(webUiDir, "package.json"))
    inputs.file(File(webUiDir, "react-router.config.ts"))
    inputs.file(File(webUiDir, "tsconfig.json"))
    inputs.file(File(webUiDir, "vite.config.ts"))
    val packageLock = File(webUiDir, "package-lock.json")
    if (packageLock.exists()) {
        inputs.file(packageLock)
    }
    outputs.dir(webUiBuildDir)

    commandLine(
        if (Os.isFamily(Os.FAMILY_WINDOWS)) listOf("cmd", "/c", "npm", "run", "build")
        else listOf("npm", "run", "build")
    )
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            assets.srcDir("../web-ui/build/client")
            assets.srcDir(prepareBundledCatalogAssets.map { it.destinationDir })
        }
    }

    defaultConfig {
        applicationId = "lastchat.rikkafork.cocolal"
        minSdk = 28
        targetSdk = 36
        versionCode = 41
        versionName = "1.4.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    flavorDimensions += "version"

    productFlavors {
        create("stable") {
            dimension = "version"
        }
        create("dev") {
            dimension = "version"
            applicationIdSuffix = ".dev"
        }
    }

    // Low-RAM escape hatch: packaging 3 ABI splits + a universal APK (each
    // ~150-250 MB with the bundled web UI) inside one Gradle daemon OOMs on
    // 8 GB dev machines (see :app:packageStableRelease IncrementalSplitterRunnable).
    //   -Plastchat.abi.splits=false -> single APK (all ABIs), least memory
    // NOTE: there is intentionally no universal-only opt-out: with splits enabled
    // but isUniversalApk=false, AGP rejects the ndk.abiFilters + splits combination
    // ("Conflicting configuration ... cannot be present when splits abi filters
    // are set"). CI defaults (splits + universal APK) are unchanged.
    val disableAbiSplits = providers.gradleProperty("lastchat.abi.splits")
        .map { it.equals("false", ignoreCase = true) }
        .orElse(false)

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle && !disableAbiSplits.get()
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            // Use release signing if configured, otherwise fall back to debug signing
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig?.storeFile != null && releaseSigningConfig.storeFile?.exists() == true) {
                signingConfig = releaseSigningConfig
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            // Shrinking stays opt-in so it can be verified against runtime-only
            // loading paths before becoming the default release behavior.
            isMinifyEnabled = enableReleaseShrinker.get()
            isShrinkResources = enableReleaseShrinker.get()
            proguardFiles(
                // [zhuli 2026-11-23] 用非 optimize 版：optimize 模式生成的
                // CourierIntake$$ExternalSyntheticLambda0 被 ART 校验器拒（VerifyError），
                // release 一启动就崩。保守规则仍有 R8 收缩/混淆，只是少一层激进优化。
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {

            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        create("baseline") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // [zhuli v16] Robolectric 需要真实资源；刻意不开 returnDefaultValues ——
    // 那会把「Android API 没被 mock」变成静默返回 null，反而掩盖真问题。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric 运行时会去拉 android-all 运行时 jar；默认走 repo1.maven.org，
            // 本机直连不通，改走镜像（与 init.gradle 里的依赖源保持一致）。
            all { test ->
                test.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
                )
                test.maxHeapSize = "2g"
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        // 预编译的 libmupdf_java.so 节头不规范，NDK 的 llvm-strip 会直接报错退出
        jniLibs {
            keepDebugSymbols += "**/libmupdf_java.so"
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    applicationVariants.all {
        outputs.all {
            this as com.android.build.gradle.internal.api.ApkVariantOutputImpl

            val variantName = baseName
            val apkName = "LastChat_" + defaultConfig.versionName + "_" + variantName + ".apk"

            outputFileName = apkName
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

tasks.named("preBuild") {
    dependsOn(buildWebUi)
    dependsOn(":speech:downloadSherpaOnnxAar")
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareBundledCatalogAssets)
}

tasks.matching { it.name.contains("Lint", ignoreCase = true) }.configureEach {
    dependsOn(prepareBundledCatalogAssets)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}


dependencies {
    implementation(project(":ui-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 2
    implementation(libs.androidx.navigation2)

    // Firebase (Analytics removed for privacy - only crash reporting and remote config)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Security - EncryptedSharedPreferences for API keys
    implementation(libs.androidx.security.crypto)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur for glassy floating controls)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.dnsoverhttps)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.jmdns)


    // pebble (template engine)
    implementation(libs.pebble)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    
    // Palette (for color extraction from images)
    implementation(libs.androidx.palette.ktx)

    // WebDav
    implementation(libs.dav4jvm)

    // [zhuli] core 迁移：RRULE 解析 + 中文时间解析（minSdk 28，java.time 无需脱糖——但 lib-recur 用了 java.time API，保守起见随主项目开脱糖）
    // [zhuli B8 S2] Shizuku（rikka.dev 生态，Maven Central 有发布）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.dmfs:lib-recur:0.17.1")
    implementation("com.github.xkzhangsan:xk-time:3.2.4")

    // [zhuli 2026-11-23] xpp3 自带的 org.xmlpull 与 Android 框架类冲突，R8 会直接报
    // "Library class android.content.res.XmlResourceParser implements program class ..."。
    // 原来那条 exclude 写在了 implementation(...) 之后单独一段 lambda 里，等于没生效。
    configurations.all {
        exclude(group = "org.ogce", module = "xpp3")
    }

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // modules
    implementation(project(":shared"))
    implementation(project(":ai"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":tts"))
    implementation(project(":speech"))
    // sherpa-onnx AAR must be provided at the app level because :speech uses compileOnly
    // (AGP forbids direct local AAR deps inside library modules)
    implementation(files(rootProject.file("speech/build/sherpa/sherpa-onnx-1.13.4.aar")))
    implementation(project(":common"))
    implementation(project(":workspace"))
    implementation(project(":local-llm"))
    implementation(libs.jsoup)
    implementation(kotlin("reflect"))
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)

    // Glance (Widgets)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.material3)

    // tests
    testImplementation(libs.junit)
    // [zhuli v16] 记忆/Agent 的 SQLite 集成测试必须在真库上跑（InMemoryAgentStore 证明不了持久化）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// [zhuli] lib-recur/xk-time 的传递依赖把 junit 拉低到 4.7（Filter.intersect 缺失致 test runner 崩），强制回 4.13.2
configurations.all {
    resolutionStrategy { force("junit:junit:4.13.2") }
}

// [zhuli 2026-11-22] 防止再把"真实数据快照"打进 APK。
// 事故：app/src/main/assets/zhuli_inbox_snapshot.db 里是旧壳导出的真实收件箱（快递/账本/待办），
// 因为 .gitignore 里有一条 zhuli_inbox_*.db，它没进 git，却进了**每一次本地构建**的 APK ——
// 于是每装一次 App 都带着我的私人数据上线，卸载 + pm clear 都清不掉。
// 这里在构建开始前直接卡住：assets 里出现数据库文件就报错。
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val assetsDir = file("src/main/assets")
        if (assetsDir.isDirectory) {
            val bad = assetsDir.listFiles { f ->
                f.isFile && f.name.endsWith(".db", ignoreCase = true)
            }.orEmpty()
            if (bad.isNotEmpty()) {
                throw GradleException(
                    "assets 里不能放数据库文件（会把真实数据打进 APK）：" +
                        bad.joinToString { it.name } +
                        "；如确需演示数据，请改名并放进 androidTest/assets。"
                )
            }
        }
    }
}

// [zhuli 2026-11-23] 防止真实密钥再入库 / 入包。
// 事故：app/src/androidTest/assets/zhuli_e2e_key.txt 曾以明文真 key（sk_tr_ 开头）提交进 git。
// .gitignore 只拦得住"未跟踪"的新文件，拦不住 git add -f，也拦不住已经进过索引的。
// 这里在构建开始前扫一遍 assets / raw：文件名可疑或内容命中 sk- 形态，直接失败。
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val scanDirs = listOf(
            file("src/main/assets"),
            file("src/androidTest/assets"),
            file("src/main/res/raw"),
        )
        val suspiciousName = Regex("(?i).*(_key|apikey|api_key|secret)\\.(txt|json|properties|xml)$")
        val keyInText = Regex("sk-[A-Za-z0-9_-]{20,}")
        val hits = mutableListOf<String>()
        scanDirs.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(projectDir).path
                if (suspiciousName.matches(f.name)) {
                    hits += "$rel（文件名可疑）"
                } else if (f.length() in 1L..(512L * 1024)) {
                    val text = runCatching { f.readText() }.getOrDefault("")
                    if (keyInText.containsMatchIn(text)) hits += "$rel（内容含 sk- 密钥）"
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "检测到疑似密钥文件，构建中止：\n" + hits.joinToString("\n") +
                    "\n请把密钥放到 ~/.zhuli/ 下，改用 instrumentation 参数 -e zhuli.e2e.key 传入。"
            )
        }
    }
}
