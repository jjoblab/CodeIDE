[versions]
agp = "9.4.1"
core-ktx = "1.19.0"
appcompat = "1.8.0"
material = "1.14.0"
{{#if estActiviteTiroir}}
drawerlayout = "1.2.0"
{{/if}}
{{#if avecCoroutines}}
coroutines = "1.11.0"
{{/if}}
{{#if avecRetrofit}}
retrofit = "3.0.0"
{{/if}}
{{#if avecNavigation}}
navigation = "2.10.2"
{{/if}}
{{#if avecKsp}}
ksp = "2.3.12"
{{/if}}
{{#if avecRoom}}
room = "2.8.5"
{{/if}}
{{#if avecHilt}}
hilt = "2.59.2"
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
{{#if avecCoroutines}}
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
{{/if}}
{{#if avecRetrofit}}
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-gson = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
{{/if}}
{{#if avecNavigation}}
androidx-navigation-fragment = { group = "androidx.navigation", name = "navigation-fragment", version.ref = "navigation" }
androidx-navigation-ui = { group = "androidx.navigation", name = "navigation-ui", version.ref = "navigation" }
{{/if}}
{{#if avecRoom}}
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
{{/if}}
{{#if avecHilt}}
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
{{/if}}
{{#if avecTests}}
junit4 = { group = "junit", name = "junit", version.ref = "junit4" }
{{/if}}

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
{{#if avecKsp}}
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
{{/if}}
{{#if avecHilt}}
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
{{/if}}
