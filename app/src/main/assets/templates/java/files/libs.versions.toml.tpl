# {{t:catalogue.entete}}
{{#if avecTests}}
[versions]
junit = "5.14.4"
junit-platform = "1.14.4"

[libraries]
junit-bom = { group = "org.junit", name = "junit-bom", version.ref = "junit" }
junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter" }
junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher" }
{{/if}}
