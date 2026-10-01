plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "{{packageName}}"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = {{minSdk}}
        consumerProguardFiles("consumer-rules.pro")
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

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    {{#if avecTests}}
    testImplementation(libs.junit4)
    {{/if}}
}
