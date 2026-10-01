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

## {{t:readme.structure}}

```
{{projectName}}/
├── src/
│   ├── commonMain/kotlin/{{packageName|packagePath}}/
│   │   ├── Greeter.kt
│   │   └── Platform.kt
│   ├── commonTest/kotlin/{{packageName|packagePath}}/
│   │   └── GreeterTest.kt
│   └── jvmMain/kotlin/{{packageName|packagePath}}/
│       ├── Greeter.jvm.kt
│       ├── Main.kt
│       └── Platform.jvm.kt
├── build.gradle.kts
└── settings.gradle.kts
```
