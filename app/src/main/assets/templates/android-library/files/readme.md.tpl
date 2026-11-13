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
./gradlew :library:assembleRelease
```

{{t:readme.aar}}

## {{t:readme.usage}}

{{t:readme.usage.corps}}

## {{t:readme.structure}}

```
{{projectName}}/
├── library/
│   ├── build.gradle.kts
│   ├── consumer-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/values/strings.xml
│       │   └── java/{{packageName|packagePath}}/
│       │       └── Greeter.kt
{{#if avecTests}}
│       └── test/java/{{packageName|packagePath}}/
│           ├── ExampleUnitTest.kt
│           └── GreeterTest.kt
{{/if}}
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```
