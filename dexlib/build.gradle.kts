plugins {
    id("com.android.library")
}

android {
    namespace = "com.android.dx"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("net.lingala.zip4j:zip4j:2.10.0")
    implementation("org.ow2.asm:asm:9.8")
}
