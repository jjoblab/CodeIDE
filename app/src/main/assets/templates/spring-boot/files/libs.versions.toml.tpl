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

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
