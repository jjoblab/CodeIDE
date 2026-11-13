# {{t:catalogue.entete}}
[versions]
kotlin = "2.2.21"
spring-boot = "4.1.1"

[libraries]
spring-boot-bom = { group = "org.springframework.boot", name = "spring-boot-dependencies", version.ref = "spring-boot" }
spring-boot-starter-web = { group = "org.springframework.boot", name = "spring-boot-starter-web" }
{{#if avecTests}}
spring-boot-starter-test = { group = "org.springframework.boot", name = "spring-boot-starter-test" }
# Boot 4 : starter-test n'embarque plus JUnit — dépendance explicite (BOM).
junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter" }
junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher" }
{{/if}}
{{#if langageKotlin}}
kotlin-reflect = { group = "org.jetbrains.kotlin", name = "kotlin-reflect" }
{{/if}}
{{#if avecJpa}}
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
h2 = { module = "com.h2database:h2" }
{{/if}}
{{#if avecSecurity}}
spring-boot-starter-security = { module = "org.springframework.boot:spring-boot-starter-security" }
{{#if avecTests}}
spring-boot-webmvc-test = { module = "org.springframework.boot:spring-boot-webmvc-test" }
{{/if}}
{{/if}}
{{#if avecActuator}}
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
{{/if}}
{{#if avecValidation}}
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
{{/if}}

[plugins]
{{#if langageKotlin}}
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
{{/if}}
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
