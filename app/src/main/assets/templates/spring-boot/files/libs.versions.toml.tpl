[versions]
kotlin = "2.0.21"
spring-boot = "3.4.1"

[libraries]
spring-boot-starter-web = { group = "org.springframework.boot", name = "spring-boot-starter-web" }
spring-boot-starter-test = { group = "org.springframework.boot", name = "spring-boot-starter-test" }
kotlin-reflect = { group = "org.jetbrains.kotlin", name = "kotlin-reflect" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency.management", version.ref = "spring-boot" }
