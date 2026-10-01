[versions]
agp = "9.4.1"
{{#if avecTests}}
junit4 = "4.13.2"
{{/if}}

[libraries]
{{#if avecTests}}
junit4 = { group = "junit", name = "junit", version.ref = "junit4" }
{{/if}}

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
