plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "{{groupId}}"
version = "{{version}}"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        withJava()
    }

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
