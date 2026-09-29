plugins { kotlin("jvm") }
dependencies { implementation(project(":lib")) }
kotlin { jvmToolchain(21) }
