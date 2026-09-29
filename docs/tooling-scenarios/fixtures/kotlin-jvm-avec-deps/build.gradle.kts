plugins {
    kotlin("jvm") version "2.0.21"
}
repositories { mavenCentral() }
dependencies {
    implementation("com.squareup.okio:okio:3.9.0")
}
kotlin { jvmToolchain(21) }
