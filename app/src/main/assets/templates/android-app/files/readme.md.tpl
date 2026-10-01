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

{{#if avecCoroutines || avecRetrofit || avecNavigation || avecRoom || avecHilt}}
## {{t:readme.dependances}}

{{#if avecCoroutines}}
- {{t:readme.dep.coroutines}}
{{/if}}
{{#if avecRetrofit}}
- {{t:readme.dep.retrofit}}
{{/if}}
{{#if avecNavigation}}
- {{t:readme.dep.navigation}}
{{/if}}
{{#if avecRoom}}
- {{t:readme.dep.room}}
{{/if}}
{{#if avecHilt}}
- {{t:readme.dep.hilt}}
{{/if}}

{{/if}}
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
{{#if avecHilt}}
{{#if langageKotlin}}
│       │       ├── {{appName|resourceName}}Application.kt
{{#else}}
│       │       ├── {{appName|resourceName}}Application.java
{{/if}}
{{/if}}
{{#if avecRoom}}
{{#if langageKotlin}}
│       │       ├── BddLocale.kt
{{#else}}
│       │       ├── BddLocale.java
{{/if}}
{{/if}}
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
