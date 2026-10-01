plugins {
    alias(libs.plugins.android.application)
{{#if avecKsp}}
    alias(libs.plugins.ksp)
{{/if}}
{{#if avecHilt}}
    alias(libs.plugins.hilt)
{{/if}}
}

android {
    namespace = "{{packageName}}"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "{{applicationId}}"
        minSdk = {{minSdk}}
        targetSdk = {{targetSdk}}
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

{{#if langageKotlin}}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
{{/if}}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
{{#if estActiviteTiroir}}
    implementation(libs.androidx.drawerlayout)
{{/if}}
{{#if avecCoroutines}}
    implementation(libs.kotlinx.coroutines.android)
{{/if}}
{{#if avecRetrofit}}
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
{{/if}}
{{#if avecNavigation}}
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
{{/if}}
{{#if avecRoom}}
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
{{/if}}
{{#if avecHilt}}
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
{{/if}}

    {{#if avecTests}}
    testImplementation(libs.junit4)
    {{/if}}
}
