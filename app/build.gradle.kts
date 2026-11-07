plugins {
    id("com.android.application")
}

android {
    namespace = "com.ppt.k90monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ppt.k90tools"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "5.9-tools"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
