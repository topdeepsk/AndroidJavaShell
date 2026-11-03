plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.github.topdeepsk.androidjavashell.test"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = namespace
        minSdk = 21
    }

    buildTypes {
        // 用于验证开启 R8 混淆后，核心库与目标类是否仍可被 app_process 正确执行
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":service"))
}
