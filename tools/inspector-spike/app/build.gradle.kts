plugins { id("com.android.application") }
android {
    namespace = "org.example.spike"
    compileSdk = 36
    defaultConfig { applicationId = "org.example.spike"; minSdk = 26; targetSdk = 36 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.media3:media3-inspector:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
}
