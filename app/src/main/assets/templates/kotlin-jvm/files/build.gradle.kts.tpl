plugins {
    alias(libs.plugins.kotlin.jvm)
{{#if estApplication}}
    application
{{#else}}
    `maven-publish`
{{/if}}
}

group = "{{groupId|kotlinString}}"
version = "{{version|kotlinString}}"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain({{jdkVersion}})
{{#if estBibliotheque}}
    explicitApi()
{{/if}}
}

{{#if estApplication}}
application {
    mainClass.set("{{packageName|kotlinString}}.MainKt")
}
{{#else}}
java {
    withSourcesJar()
}

publishing {
    publications {
        create<org.gradle.api.publish.maven.MavenPublication>("maven") {
            from(components["java"])
            groupId = "{{groupId|kotlinString}}"
            artifactId = "{{artifactId|kotlinString}}"
            version = "{{version|kotlinString}}"
{{#if license != "none"}}
            pom {
                licenses {
                    license {
{{#if license == "mit"}}
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
{{/if}}
{{#if license == "apache-2.0"}}
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
{{/if}}
{{#if license == "gpl-3.0"}}
                        name = "GPL-3.0-only"
                        url = "https://www.gnu.org/licenses/gpl-3.0.html"
{{/if}}
{{#if license == "bsd-3-clause"}}
                        name = "BSD-3-Clause"
                        url = "https://opensource.org/licenses/BSD-3-Clause"
{{/if}}
                    }
                }
            }
{{/if}}
        }
    }
}
{{/if}}
{{#if avecTests}}
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
{{/if}}
