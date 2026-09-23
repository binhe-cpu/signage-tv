plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本号只在这里改一处，下面 defaultConfig 和 APK 文件名都从它取。
 * 每次发新版本：versionCode 加 1（整数，只能涨不能降），versionName 改成你想要的名字。
 */
val appVersionCode = 11
val appVersionName = "0.3.8"

android {
    namespace = "com.laodao.signage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.laodao.signage"
        // 21 = Android 5.0。店里那台电视是 5.1.1，所以下限必须压到这里。
        // 用到的三方库（Media3 / androidx.core / zxing）都支持 21，不需要降级依赖。
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        getByName("debug") {
            // Android 7.0 以下只认 v1(JAR) 签名。默认只签 v2 的话，
            // 在老电视上装会报「解析软件包时出现问题」，所以两个都开。
            enableV1Signing = true
            enableV2Signing = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

/**
 * 产物 APK 带上版本号，免得一堆 app-debug.apk 分不清哪版。
 * 产出：app/build/outputs/apk/debug/signage-0.3.3-6-debug.apk
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val named = output as? com.android.build.api.variant.impl.VariantOutputImpl
            named?.outputFileName?.set("signage-$appVersionName-$appVersionCode-${variant.name}.apk")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
}
