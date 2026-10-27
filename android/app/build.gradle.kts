// 工程骨架的两个刻意选择：
//   1) 剧本不进 res/raw，而是把仓库根的 script/ 直接挂成 assets——剧本是内容不是资源，
//      改剧本不该重新编译，也不该出现"工程里一份、仓库里一份"两个真相。
//   2) 没有 INTERNET 权限。设定上她只存在于这台手机里，权限清单必须能替我们把这话证明出来。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

import java.io.File
import java.util.Properties

/**
 * 发布签名从 `local.properties` 读（docs/08 第五节）：keystore 与口令不进仓库。
 * 没有那几行时 release 构建照样跑，只是产物未签名、装不上——
 * 未签名的包比一个"用别人的钥匙签过的包"诚实得多。
 *
 * 钥匙在仓库外的 `keystore/beifen-zhong-release.jks`。这个文件的绝对路径 + 口令
 * 写在 `android/local.properties`（已被 .gitignore 排除）。
 */
val releaseProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile: File? = releaseProps.getProperty("beifen.storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let { path -> File(path) }
    ?.takeIf { it.isFile }

android {
    namespace = "cn.linan.backingup"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "cn.linan.backingup"
        minSdk = 26           // 通知渠道是硬依赖；再往下掉就要自己封一层，不值
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 后门面板要读 BuildConfig.DEBUG，AGP 8 默认不生成
        buildConfigField("boolean", "DEV_PANEL_DEFAULT", "false")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // 七章剧本 + flags.json 直接来自仓库根（android/app → ../../script），唯一真相。
            // 于是 assets 里就是 act1.json…act7.json + flags.json，改剧本不用重编。
            assets.srcDir(file("../../script"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 离线首构建的补丁：这些 jar 在 ~/.gradle 缓存里只有 .pom 没有 .jar（版本被上游带高了），
    // 钉到缓存里真的有 jar 的那一版。都是纯注解包，钉了不影响运行时行为。
    // 哪天网络通了，整段可以删掉——它是环境限制，不是工程决定。
    configurations.configureEach {
        resolutionStrategy.force(
            "com.google.errorprone:error_prone_annotations:2.28.0",
        )
    }

    // 顺序有讲究：signingConfigs 必须在 buildTypes 之前声明，
    // 否则下面 getByName("release") 求值时容器还是空的，构建直接炸。
    signingConfigs {
        val jar = releaseStoreFile
        if (jar != null) {
            create("release") {
                storeFile = jar
                storePassword = releaseProps.getProperty("beifen.storePassword")
                keyAlias = releaseProps.getProperty("beifen.keyAlias")
                keyPassword = releaseProps.getProperty("beifen.keyPassword")
                    ?: releaseProps.getProperty("beifen.storePassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // 第一版不混淆：我们要的是能装的包，不是最小的包
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 有钥匙就签；没有就留一个未签名的产物——未签名的 release 包本来就装不上
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(libs.junit)
}
