plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tbeco.lightlink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tbeco.lightlink"

        minSdk = 26
        targetSdk = 37

        versionCode = 2
        versionName = "0.5.0-beta.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /*
    * BuildConfig.java 자동 생성
    *
    * AppLog에서 BuilConfig.DEBUG를 사용하기 위해 필요
     */
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}