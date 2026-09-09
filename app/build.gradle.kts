plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.muzermat.harnessremote"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.muzermat.harnessremote"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-preview.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("distribution") {
            val path = System.getenv("HARNESS_SIGNING_STORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("HARNESS_SIGNING_PASSWORD")
                keyAlias = "harness-remote"
                keyPassword = System.getenv("HARNESS_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("distribution") }
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
