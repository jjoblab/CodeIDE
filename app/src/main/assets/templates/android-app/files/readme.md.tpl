# {{appName}}

{{t:readme.description}}

## {{t:readme.prerequis}}

- JDK 17+
- Android SDK (compileSdk 37)

## {{t:readme.build}}

```
./gradlew assembleDebug
```

## {{t:readme.structure}}

```
{{appName}}/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── res/layout/activity_main.xml
│   │   └── java/jo/codeide/template/
│   │       ├── MainActivity.kt
│   │       └── Greeter.kt
│   └── src/test/
│       └── java/jo/codeide/template/
│           └── GreeterTest.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```
