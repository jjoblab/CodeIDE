# {{projectName|md}}
{{#if description != ""}}
{{description|md}}
{{#else}}
{{t:readme.description}}
{{/if}}
## {{t:readme.prerequis}}

- {{t:readme.jdk}} {{jdkVersion}}+
{{#if avecJpa}}
- {{t:readme.dep.jpa}}
{{/if}}
{{#if estMaven}}
- {{t:readme.maven.note}}
{{/if}}

## {{t:readme.executer}}

```
{{#if estGradle}}./gradlew bootRun{{#else}}mvn spring-boot:run{{/if}}
```

{{t:readme.demarrage}} http://localhost:8080

{{#if avecJpa || avecSecurity || avecActuator || avecValidation}}
## {{t:readme.dependances}}

{{#if avecJpa}}
- {{t:readme.dep.jpa.detail}}
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
{{#if avecActuator}}
{{t:readme.securite.health}}
{{/if}}

{{/if}}
{{#if estMaven}}
{{t:readme.profils.titre}} `dev` {{t:readme.profils.defaut}} `prod` : `mvn spring-boot:run -Dspring-boot.run.profiles=prod`

{{/if}}
## {{t:readme.endpoints}}

- `GET /greet?name=Ada` → `{"message": "{{t:app.greeting}}, Ada!"}`
{{#if avecValidation}}
- `POST /salutations {"nom": "Ada"}` → `{"id": 1, "message": "{{t:app.greeting}}, Ada!"}` (400 si `nom` est vide)
{{#else}}
- `POST /salutations?name=Ada` → `{"id": 1, "message": "{{t:app.greeting}}, Ada!"}`
{{/if}}
- `GET /salutations` → `[{"id": 1, "message": "{{t:app.greeting}}, Ada!"}]`
{{#if avecActuator}}
- `GET /actuator/health` → `{"status": "UP"}`
{{/if}}

## {{t:readme.build}}

```
{{#if estGradle}}./gradlew build{{#else}}mvn verify{{/if}}
```

## {{t:readme.structure}}

```
{{projectName}}/
├── src/
│   ├── main/
│   │   ├── {{langage}}/{{packageName|packagePath}}/
│   │   │   ├── Application{{#if langageKotlin}}.kt{{#else}}.java{{/if}}
│   │   │   ├── GreeterController{{#if langageKotlin}}.kt{{#else}}.java{{/if}}
│   │   │   ├── GreeterService{{#if langageKotlin}}.kt{{#else}}.java{{/if}}
│   │   │   ├── GreeterRepository{{#if langageKotlin}}.kt{{#else}}.java{{/if}}
│   │   │   └── Salutation{{#if langageKotlin}}.kt{{#else}}.java{{/if}}
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       └── application-prod.yml
{{#if avecTests}}
│   └── test/{{langage}}/{{packageName|packagePath}}/
│       ├── ApplicationTests{{#if langageKotlin}}.kt{{#else}}.java{{/if}}
│       └── GreeterServiceTest{{#if langageKotlin}}.kt{{#else}}.java{{/if}}
{{/if}}
{{#if estGradle}}
├── build.gradle.kts
├── gradle/libs.versions.toml
└── settings.gradle.kts
{{#else}}
└── pom.xml
{{/if}}
```
