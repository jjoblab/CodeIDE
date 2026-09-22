# {{t:catalogue.entete}}
[versions]
kotlin = "2.2.21"
{{#if avecTests}}
junit = "5.14.4"
junit-platform = "1.14.4"
{{/if}}
[libraries]
{{#if avecTests}}
junit-bom = { group = "org.junit", name = "junit-bom", version.ref = "junit" }
junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter" }
junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher" }
{{/if}}
[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
