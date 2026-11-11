// build-logic — included build qui fournit les convention plugins CodeIDE.

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Miroir de sortie du bac a sable (429 gradle/central) — LOCAL NON COMMITE, lot L5.
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
