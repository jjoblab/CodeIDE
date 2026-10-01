# {{t:catalogue.entete}}
[versions]
kotlin = "2.2.21"
{{#if avecSerialization}}
serialization = "1.11.0"
{{/if}}
{{#if avecCoroutines}}
coroutines = "1.11.0"
{{/if}}
{{#if avecDatetime}}
datetime = "0.8.0"
{{/if}}

{{#if avecSerialization || avecCoroutines || avecDatetime}}
[libraries]
{{#if avecSerialization}}
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
{{/if}}
{{#if avecCoroutines}}
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
{{/if}}
{{#if avecDatetime}}
kotlinx-datetime = { group = "org.jetbrains.kotlinx", name = "kotlinx-datetime", version.ref = "datetime" }
{{/if}}
{{/if}}

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
{{#if avecSerialization}}
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
{{/if}}
