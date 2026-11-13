<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>{{groupId|xml}}</groupId>
    <artifactId>{{artifactId|xml}}</artifactId>
    <version>{{version|xml}}</version>
    <packaging>jar</packaging>

    <name>{{projectName|xml}}</name>
{{#if description != ""}}
    <description>{{description|xml}}</description>
{{/if}}
{{#if license != "none"}}
    <licenses>
        <license>
{{#if license == "mit"}}
            <name>MIT License</name>
            <url>https://opensource.org/licenses/MIT</url>
{{/if}}
{{#if license == "apache-2.0"}}
            <name>Apache-2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0</url>
{{/if}}
{{#if license == "gpl-3.0"}}
            <name>GPL-3.0-only</name>
            <url>https://www.gnu.org/licenses/gpl-3.0.html</url>
{{/if}}
{{#if license == "bsd-3-clause"}}
            <name>BSD-3-Clause</name>
            <url>https://opensource.org/licenses/BSD-3-Clause</url>
{{/if}}
        </license>
    </licenses>
{{/if}}

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.release>{{jdkVersion}}</maven.compiler.release>
{{#if avecTests}}
        <junit.version>5.14.4</junit.version>
{{/if}}
        <maven-compiler.version>3.16.0</maven-compiler.version>
        <maven-surefire.version>3.6.0</maven-surefire.version>
        <maven-jar.version>3.5.1</maven-jar.version>
        <exec-maven.version>3.6.4</exec-maven.version>
        <maven-source.version>3.4.0</maven-source.version>
        <maven-javadoc.version>3.12.0</maven-javadoc.version>
{{#if estApplication}}
        <main.class>{{packageName|xml}}.Main</main.class>
{{/if}}
    </properties>
{{#if avecTests}}

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>${junit.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
{{/if}}

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${maven-compiler.version}</version>
                <configuration>
                    <compilerArgs>
                        <arg>-Xlint:all</arg>
                        <arg>-Werror</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
{{#if estApplication}}
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>${maven-jar.version}</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>${main.class}</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>${exec-maven.version}</version>
                <configuration>
                    <mainClass>${main.class}</mainClass>
                </configuration>
            </plugin>
{{/if}}
{{#if estBibliotheque}}
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-source-plugin</artifactId>
                <version>${maven-source.version}</version>
                <executions>
                    <execution>
                        <id>attach-sources</id>
                        <goals>
                            <goal>jar-no-fork</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-javadoc-plugin</artifactId>
                <version>${maven-javadoc.version}</version>
                <executions>
                    <execution>
                        <id>attach-javadocs</id>
                        <goals>
                            <goal>jar</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <failOnWarnings>true</failOnWarnings>
                </configuration>
            </plugin>
{{/if}}
{{#if avecTests}}
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${maven-surefire.version}</version>
            </plugin>
{{/if}}
        </plugins>
    </build>
</project>
