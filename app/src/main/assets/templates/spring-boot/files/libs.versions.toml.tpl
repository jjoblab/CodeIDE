# {{t:catalogue.entete}}
[versions]
kotlin = "2.2.21"
spring-boot = "4.1.1"

[libraries]
spring-boot-bom = { group = "org.springframework.boot", name = "spring-boot-dependencies", version.ref = "spring-boot" }
spring-boot-starter-web = { group = "org.springframework.boot", name = "spring-boot-starter-web" }
{{#if avecTests}}
spring-boot-starter-test = { group = "org.springframework.boot", name = "spring-boot-starter-test" }
{{/if}}
kotlin-reflect = { group = "org.jetbrains.kotlin", name = "kotlin-reflect" }
{{#if avecJpa}}
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
h2 = { module = "com.h2database:h2" }
{{/if}}
{{#if avecSecurity}}
spring-boot-starter-security = { module = "org.springframework.boot:spring-boot-starter-security" }
{{/if}}
{{#if avecActuator}}
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
{{/if}}
{{#if avecValidation}}
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
{{/if}}

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
