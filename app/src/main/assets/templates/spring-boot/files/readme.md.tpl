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
./gradlew bootRun
```

{{t:readme.demarrage}} http://localhost:8080

## {{t:readme.endpoints}}

- `GET /greet?name=Ada` → `{"message": "{{t:app.greeting}}, Ada!"}`
- `POST /salutations?name=Ada` → `{"id": 1, "message": "{{t:app.greeting}}, Ada!"}`
- `GET /salutations` → `[{"id": 1, "message": "{{t:app.greeting}}, Ada!"}]`

## {{t:readme.build}}

```
./gradlew build
```

## {{t:readme.structure}}

```
{{projectName}}/
├── src/
│   ├── main/
│   │   ├── kotlin/{{packageName|packagePath}}/
│   │   │   ├── Application.kt
│   │   │   ├── GreeterController.kt
│   │   │   ├── GreeterRepository.kt
│   │   │   └── GreeterService.kt
│   │   └── resources/
│   │       └── application.yml
│   └── test/kotlin/{{packageName|packagePath}}/
│       ├── ApplicationTests.kt
│       └── GreeterServiceTest.kt
├── build.gradle.kts
└── settings.gradle.kts
```
