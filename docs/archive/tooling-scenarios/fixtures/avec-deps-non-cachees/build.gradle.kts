plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1") }
kotlin { jvmToolchain(21) }
