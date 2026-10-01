[versions]
agp = "9.4.1"
core-ktx = "1.19.0"
appcompat = "1.8.0"
material = "1.14.0"
{{#if estActiviteTiroir}}
drawerlayout = "1.2.0"
{{/if}}
{{#if avecTests}}
junit4 = "4.13.2"
{{/if}}

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "core-ktx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
{{#if estActiviteTiroir}}
androidx-drawerlayout = { group = "androidx.drawerlayout", name = "drawerlayout", version.ref = "drawerlayout" }
{{/if}}
{{#if avecTests}}
junit4 = { group = "junit", name = "junit", version.ref = "junit4" }
{{/if}}

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
