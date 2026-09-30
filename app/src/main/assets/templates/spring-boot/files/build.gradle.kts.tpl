plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "{{groupId}}"
version = "{{version}}"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.kotlin.reflect)
    {{#if avecTests}}
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
    {{/if}}
}
