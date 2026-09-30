plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "{{packageName}}"
    compileSdk = 35
    defaultConfig {
        applicationId = "{{packageName}}"
        minSdk = {{minSdk}}
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.constraintlayout)
{{#if avecTests}}
    testImplementation(kotlin("test"))
{{/if}}
}
