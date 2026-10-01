# {{projectName|md}}
{{#if description != ""}}
{{description|md}}
{{#else}}
{{t:readme.description}}
{{/if}}
## {{t:readme.prerequis}}

- {{t:readme.jdk}} 17+

## {{t:readme.executer}}

```
./gradlew run
```

## {{t:readme.build}}

```
./gradlew build
```

{{#if avecSerialization || avecCoroutines || avecDatetime}}
## {{t:readme.dependances}}

{{#if avecSerialization}}
- {{t:readme.dep.serialization}}
{{/if}}
{{#if avecCoroutines}}
- {{t:readme.dep.coroutines}}
{{/if}}
{{#if avecDatetime}}
- {{t:readme.dep.datetime}}
{{/if}}

{{/if}}
## {{t:readme.structure}}

```
{{projectName}}/
├── src/
│   ├── commonMain/kotlin/{{packageName|packagePath}}/
│   │   ├── Greeter.kt
│   │   ├── Platform.kt
{{#if avecSerialization}}
│   │   ├── ConfigurationSalutation.kt
{{/if}}
{{#if avecCoroutines}}
│   │   ├── DelaisSalutation.kt
{{/if}}
{{#if avecDatetime}}
│   │   ├── HorodatageSalutation.kt
{{/if}}
│   ├── commonTest/kotlin/{{packageName|packagePath}}/
│   │   └── GreeterTest.kt
│   └── jvmMain/kotlin/{{packageName|packagePath}}/
│       ├── Greeter.jvm.kt
│       ├── Main.kt
│       └── Platform.jvm.kt
├── build.gradle.kts
└── settings.gradle.kts
```
