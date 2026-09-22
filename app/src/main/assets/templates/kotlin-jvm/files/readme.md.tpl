# {{projectName|md}}
{{#if description != ""}}
{{description|md}}
{{/if}}
## {{t:readme.prerequis}}
{{#if buildSystem != "none"}}
- {{t:readme.jdk}} : {{jdkVersion}}
{{#else}}
- {{t:readme.jdk.auconly}}
{{/if}}{{#if avecWrapper}}- {{t:readme.wrapper.note}}
{{/if}}
## {{t:readme.build}}
{{#if gradle}}
```sh
./gradlew build
```
{{/if}}{{#if maven}}
```sh
mvn -q verify
```
{{/if}}{{#if buildSystem == "none"}}{{#if estApplication}}
{{t:readme.sources.kotlinc1}}

```sh
kotlinc src/main/kotlin -include-runtime -d {{slug}}.jar
java -jar {{slug}}.jar
```
{{#else}}
{{t:readme.sources.kotlinc2}}

```sh
kotlinc src/main/kotlin -d {{slug}}.jar
```
{{/if}}{{/if}}{{#if estApplication}}{{#if buildSystem != "none"}}
## {{t:readme.run}}
{{#if gradle}}
```sh
./gradlew run
```
{{/if}}{{#if maven}}
```sh
mvn -q compile exec:java
```
{{/if}}{{/if}}{{/if}}{{#if estBibliotheque}}{{#if buildSystem != "none"}}
## {{t:readme.publish}}
{{#if gradle}}
```sh
./gradlew publishToMavenLocal
```
{{/if}}{{#if maven}}
```sh
mvn -q install
```
{{/if}}{{/if}}{{/if}}
## {{t:readme.structure}}

```text
.
{{#if gradle}}
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
{{#if avecWrapper}}├── gradlew
├── gradlew.bat
├── gradle/wrapper/
{{/if}}{{/if}}{{#if maven}}
├── pom.xml
{{/if}}{{#if avecTests}}
├── src/main/kotlin/{{packageName}}/
│   {{#if estApplication}}├──{{#else}}└──{{/if}} Greeter.kt
{{#if estApplication}}│   └── Main.kt
{{/if}}
└── src/test/kotlin/{{packageName}}/
    └── GreeterTest.kt
{{#else}}
└── src/main/kotlin/{{packageName}}/
    {{#if estApplication}}├──{{#else}}└──{{/if}} Greeter.kt
{{#if estApplication}}    └── Main.kt
{{/if}}{{/if}}
```

## {{t:readme.licence}}
{{#if license != "none"}}
{{t:readme.licence.fichier}}
{{#else}}
{{t:readme.licence.aucune}}
{{/if}}
