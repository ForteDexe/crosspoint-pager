plugins {
    id("com.android.application")
}

android {
    namespace = "org.crosspointreader.pagerrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.crosspointreader.pagerrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
