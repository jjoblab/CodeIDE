plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
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
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.kotlin.reflect)
{{#if avecJpa}}
    implementation(libs.spring.boot.starter.data.jpa)
    runtimeOnly(libs.h2)
{{/if}}
{{#if avecSecurity}}
    implementation(libs.spring.boot.starter.security)
{{/if}}
{{#if avecActuator}}
    implementation(libs.spring.boot.starter.actuator)
{{/if}}
{{#if avecValidation}}
    implementation(libs.spring.boot.starter.validation)
{{/if}}
    {{#if avecTests}}
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
    {{/if}}
}
