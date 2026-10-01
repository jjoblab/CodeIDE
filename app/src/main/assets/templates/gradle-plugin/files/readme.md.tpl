# {{projectName|md}}
{{#if description != ""}}
{{description|md}}
{{#else}}
{{t:readme.description}}
{{/if}}
## {{t:readme.build}}

```
./gradlew build
```

## {{t:readme.usage}}

```kotlin
plugins {
    id("{{pluginId|kotlinString}}") version "{{version|kotlinString}}"
}

greeting {
    message = "{{t:app.greeting}}"
}
```

{{t:readme.tache}}

## {{t:readme.structure}}

```
{{projectName}}/
├── src/
│   ├── main/kotlin/{{packageName|packagePath}}/
│   │   ├── {{projectName|resourceName}}Plugin.kt
│   │   ├── GreetingExtension.kt
│   │   └── GreetTask.kt
{{#if avecTests}}
│   └── test/kotlin/{{packageName|packagePath}}/
│       └── PluginTest.kt
{{/if}}
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```
