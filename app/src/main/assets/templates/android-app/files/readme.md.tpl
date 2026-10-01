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

{{#if estActiviteTiroir}}
{{t:readme.variante.tiroir}}
{{#else}}
{{#if estSansActivite}}
{{t:readme.variante.sansactivite}}
{{#else}}
{{t:readme.variante.vide}}
{{/if}}
{{/if}}

```
{{projectName}}/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/
│       │   │   ├── values/
│       │   │   │   ├── colors.xml
│       │   │   │   ├── strings.xml
│       │   │   │   └── themes.xml
{{#if estActiviteVide}}
│       │   │   └── layout/activity_main.xml
{{/if}}
{{#if estActiviteTiroir}}
│       │   │   ├── layout/activity_main_tiroir.xml
│       │   │   ├── layout/fragment_accueil.xml
│       │   │   └── menu/tiroir.xml
{{/if}}
{{#if estSansActivite}}
│       │   └── java/{{packageName|packagePath}}/
{{#if langageKotlin}}
│       │       └── Greeter.kt
{{#else}}
│       │       └── Greeter.java
{{/if}}
{{#else}}
│       │   └── java/{{packageName|packagePath}}/
{{#if langageKotlin}}
{{#if estActiviteTiroir}}
│       │       ├── FragmentAccueil.kt
│       │       ├── Greeter.kt
{{#else}}
│       │       ├── Greeter.kt
{{/if}}
│       │       └── MainActivity.kt
{{#else}}
{{#if estActiviteTiroir}}
│       │       ├── FragmentAccueil.java
│       │       ├── Greeter.java
{{#else}}
│       │       ├── Greeter.java
{{/if}}
│       │       └── MainActivity.java
{{/if}}
{{/if}}
{{#if avecTests}}
│       └── test/java/{{packageName|packagePath}}/
{{#if langageKotlin}}
│           ├── ExampleUnitTest.kt
│           └── GreeterTest.kt
{{#else}}
│           ├── ExampleUnitTest.java
│           └── GreeterTest.java
{{/if}}
{{/if}}
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```
