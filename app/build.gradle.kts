import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式签名：证书信息放在项目根目录的 keystore.properties
// （没这个文件也能构建，只是打出来的包没有正式签名）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.r2r3k.fengmingharness"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.r2r3k.fengmingharness"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "2.0.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 先不做混淆：R8 把某个反射调用的东西删掉，出了问题很难查。
            // 等稳定了再考虑打开（能再小 1~2MB）。
            optimization {
                enable = false
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 单元测试跑在电脑的 JVM 上：Android 自带的 org.json 那时只是空壳，
    // 必须换成 Maven 上的真实现，解析器才跑得起来
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
