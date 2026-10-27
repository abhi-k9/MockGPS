plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.burixon.mockgps"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.abhik.mockgps"
        minSdk = 29
        targetSdk = 36
        versionCode = 15
        versionName = "0.8.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.material)

    implementation(libs.maplibre)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
