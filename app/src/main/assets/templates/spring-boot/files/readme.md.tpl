# {{artifactId}}

Application Spring Boot en Kotlin générée par CodeIDE.

## Prérequis

- JDK 17+

## Exécuter

```
./gradlew run
```

L'application démarre sur http://localhost:8080.

## Endpoints

- `GET /greet?name=Ada` → `{"message": "Hello, Ada!"}`

## Compiler et tester

```
./gradlew build
```

## Structure du projet

```
{{artifactId}}/
├── src/main/kotlin/jo/codeide/template/
│   ├── Application.kt
│   ├── GreeterController.kt
│   └── GreeterService.kt
├── src/test/kotlin/jo/codeide/template/
│   └── GreeterServiceTest.kt
├── build.gradle.kts
└── settings.gradle.kts
```
