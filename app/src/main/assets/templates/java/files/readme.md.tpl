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
{{t:readme.sources.javac1}}

```sh
javac -encoding UTF-8 -d out $(find src/main/java -name '*.java')
java -cp out {{packageName}}.Main
```
{{#else}}
{{t:readme.sources.javac2}}

```sh
javac -encoding UTF-8 -d out $(find src/main/java -name '*.java')
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
├── src/main/java/{{packageName}}/
│   {{#if estApplication}}├──{{#else}}└──{{/if}} Greeter.java
{{#if estApplication}}│   └── Main.java
{{/if}}
└── src/test/java/{{packageName}}/
    └── GreeterTest.java
{{#else}}
└── src/main/java/{{packageName}}/
    {{#if estApplication}}├──{{#else}}└──{{/if}} Greeter.java
{{#if estApplication}}    └── Main.java
{{/if}}{{/if}}
```

## {{t:readme.licence}}
{{#if license != "none"}}
{{t:readme.licence.fichier}}
{{#else}}
{{t:readme.licence.aucune}}
{{/if}}
