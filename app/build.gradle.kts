plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bharatgps.drishti"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bharatgps.drishti"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-test"
    }

    signingConfigs {
        create("test") {
            storeFile = rootProject.file("keystore/drishti-test.jks")
            storePassword = "drishti123"
            keyAlias = "drishti"
            keyPassword = "drishti123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("test")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // On-device object detection (COCO 80 classes) and face embeddings
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // On-device face detection (bundled model, works offline)
    implementation("com.google.mlkit:face-detection:16.1.7")
}
