plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "{{packageName|kotlinString}}"
version = "{{version|kotlinString}}"

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("{{artifactId|kotlinString}}") {
            id = "{{pluginId|kotlinString}}"
            implementationClass = "{{packageName|kotlinString}}.{{projectName|resourceName}}Plugin"
        }
    }
}

publishing {
    // Le nom du projet peut contenir des espaces (invalide en Maven) :
    // l'artifact de la publication porte l'artifact ID, pas le nom.
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "{{artifactId|kotlinString}}"
        }
    }
}

{{#if avecTests}}
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
{{/if}}
