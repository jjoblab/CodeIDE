{{#if langageKotlin}}
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}
{{#else}}
plugins {
    java
    alias(libs.plugins.spring.boot)
}
{{/if}}

group = "{{groupId}}"
version = "{{version}}"

repositories {
    mavenCentral()
}

{{#if langageKotlin}}
kotlin {
    jvmToolchain({{jdkVersion}})
}
{{#else}}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of({{jdkVersion}})
    }
}
{{/if}}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
{{#if langageKotlin}}
    implementation(libs.kotlin.reflect)
{{/if}}
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
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    {{#if avecSecurity}}
    testImplementation(libs.spring.boot.webmvc.test)
    {{/if}}
    {{/if}}
}

{{#if avecTests}}
tasks.test {
    useJUnitPlatform()
}
{{/if}}
