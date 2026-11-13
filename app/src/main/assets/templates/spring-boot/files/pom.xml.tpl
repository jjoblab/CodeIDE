<?xml version="1.0" encoding="UTF-8"?>
<!-- {{t:pom.entete}} -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>{{groupId}}</groupId>
  <artifactId>{{artifactId}}</artifactId>
  <version>{{version}}</version>
  <name>{{projectName}}</name>
{{#if langageKotlin}}
  <properties>
    <java.version>{{jdkVersion}}</java.version>
    <kotlin.version>2.2.21</kotlin.version>
  </properties>
{{#else}}
  <properties>
    <java.version>{{jdkVersion}}</java.version>
  </properties>
{{/if}}

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
{{#if langageKotlin}}
    <dependency>
      <groupId>org.jetbrains.kotlin</groupId>
      <artifactId>kotlin-reflect</artifactId>
    </dependency>
    <dependency>
      <groupId>org.jetbrains.kotlin</groupId>
      <artifactId>kotlin-stdlib</artifactId>
    </dependency>
{{/if}}
{{#if avecJpa}}
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>runtime</scope>
    </dependency>
{{/if}}
{{#if avecSecurity}}
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
{{/if}}
{{#if avecActuator}}
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
{{/if}}
{{#if avecValidation}}
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
{{/if}}
{{#if avecTests}}
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <!-- Boot 4 : starter-test n'embarque plus JUnit — dépendance explicite. -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
{{#if avecSecurity}}
    <!-- Boot 4 : l'auto-configuration MockMvc vit dans son module dédié. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-webmvc-test</artifactId>
      <scope>test</scope>
    </dependency>
{{/if}}
{{/if}}
  </dependencies>

  <build>
{{#if langageKotlin}}
    <sourceDirectory>${project.basedir}/src/main/kotlin</sourceDirectory>
    <testSourceDirectory>${project.basedir}/src/test/kotlin</testSourceDirectory>
{{/if}}
    <plugins>
{{#if langageKotlin}}
      <plugin>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-maven-plugin</artifactId>
        <configuration>
          <jvmTarget>${java.version}</jvmTarget>
          <args>
            <arg>-Xjsr305=strict</arg>
          </args>
          <compilerPlugins>
            <plugin>spring</plugin>
{{#if avecJpa}}
            <plugin>jpa</plugin>
{{/if}}
          </compilerPlugins>
        </configuration>
        <executions>
          <execution>
            <id>compile</id>
            <goals><goal>compile</goal></goals>
          </execution>
          <execution>
            <id>test-compile</id>
            <goals><goal>test-compile</goal></goals>
          </execution>
        </executions>
        <dependencies>
          <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-allopen</artifactId>
            <version>${kotlin.version}</version>
          </dependency>
{{#if avecJpa}}
          <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-noarg</artifactId>
            <version>${kotlin.version}</version>
          </dependency>
{{/if}}
        </dependencies>
      </plugin>
{{/if}}
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
