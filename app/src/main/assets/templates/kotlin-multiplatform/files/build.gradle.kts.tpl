import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
{{#if avecSerialization}}
    alias(libs.plugins.kotlin.serialization)
{{/if}}
}

group = "{{groupId}}"
version = "{{version}}"

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(kotlin("stdlib"))
{{#if avecSerialization}}
                implementation(libs.kotlinx.serialization.json)
{{/if}}
{{#if avecCoroutines}}
                implementation(libs.kotlinx.coroutines.core)
{{/if}}
{{#if avecDatetime}}
                implementation(libs.kotlinx.datetime)
{{/if}}
            }
        }
        {{#if avecTests}}
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        {{/if}}
    }
}

val classpathJvm =
    extensions.getByType<KotlinMultiplatformExtension>()
        .targets.getByName("jvm").compilations.getByName("main")
        .let { files(it.output.allOutputs, it.runtimeDependencyFiles) }

tasks.register<JavaExec>("run") {
    group = "application"
    mainClass.set("{{packageName}}.MainKt")
    classpath = classpathJvm
}
