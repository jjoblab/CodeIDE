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

{{#if avecJpa || avecSecurity || avecActuator || avecValidation}}
## {{t:readme.dependances}}

{{#if avecJpa}}
- {{t:readme.dep.jpa}}
{{/if}}
{{#if avecSecurity}}
- {{t:readme.dep.security}}
{{/if}}
{{#if avecActuator}}
- {{t:readme.dep.actuator}}
{{/if}}
{{#if avecValidation}}
- {{t:readme.dep.validation}}
{{/if}}

{{/if}}
{{#if avecSecurity}}
{{t:readme.securite}}

{{/if}}
## {{t:readme.endpoints}}

- `GET /greet?name=Ada` → `{"message": "{{t:app.greeting}}, Ada!"}`
- `POST /salutations?name=Ada` → `{"id": 1, "message": "{{t:app.greeting}}, Ada!"}`
- `GET /salutations` → `[{"id": 1, "message": "{{t:app.greeting}}, Ada!"}]`
{{#if avecActuator}}
- `GET /actuator/health` → `{"status": "UP"}`
{{/if}}

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
