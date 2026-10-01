import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
