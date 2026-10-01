# {{projectName|md}}
{{#if description != ""}}
{{description|md}}
{{#else}}
{{t:readme.description}}
{{/if}}
## {{t:readme.prerequis}}

- {{t:readme.jdk}} 17+
- {{t:readme.sdk}}

## {{t:readme.build}}

```
./gradlew assembleDebug
```

{{t:readme.apk}}

## {{t:readme.structure}}

```
{{projectName}}/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/
│       │   │   ├── layout/activity_main.xml
│       │   │   └── values/
│       │   │       ├── colors.xml
│       │   │       ├── strings.xml
│       │   │       └── themes.xml
│       │   └── java/{{packageName|packagePath}}/
│       │       ├── Greeter.kt
│       │       └── MainActivity.kt
│       └── test/java/{{packageName|packagePath}}/
│           ├── ExampleUnitTest.kt
│           └── GreeterTest.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```
