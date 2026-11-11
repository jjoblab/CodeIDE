# {{t:yml.entete}}
server:
  port: 8080

spring:
  application:
    name: {{artifactId}}
  profiles:
    active: dev
{{#if avecJpa}}
  datasource:
    url: jdbc:h2:mem:salutations
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
{{/if}}
{{#if avecActuator}}
management:
  endpoints:
    web:
      exposure:
        include: health
{{/if}}
