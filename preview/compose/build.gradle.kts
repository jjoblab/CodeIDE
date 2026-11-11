// :preview:compose — moteur de preview Compose (mission « Previews »,
// PREVIEW.md §4 — PC1/PC2, étape C1 : rendu statique persistant,
// live-edit par diff d'identité, garde-fous PC4, catalogue d'erreurs
// typées).
//
// VOIE INTERPRÉTEUR (PC1) : le LSP Kotlin (pack 1.4.0) découvre les
// `@Preview @Composable` et réduit leur corps en ARBRE D'APPELS NEUTRE
// (frontière D6) ; CE module convertit l'arbre en PROGRAMME parcelable
// et l'EXÉCUTE contre le runtime Compose EMBARQUÉ — bibliothèque
// d'interprétation, JAMAIS l'UI CodeIDE (ADR 0002 intact).
//
// ISOLATION (PC2/PC4) : le runtime Compose vivant (composition,
// recomposer) vit dans le PROCESSUS `:preview` (ServicePreviewCompose,
// même processus que ServicePreview — « une seule machinerie, deux
// dialectes ») ; le programme interprété n'exécute QUE les composables
// du REGISTRE — sandbox par construction (réseau/stockage impossibles),
// LocalInspectionMode vrai, budgets d'interprétation doublés côté
// exécution (défense en profondeur). AUCUN repli local : composer du
// code de l'utilisateur dans le processus de l'IDE est EXACTEMENT ce
// que PC4 interdit — la panne du processus est un ÉTAT TYPIQUE
// (Réessayer), jamais un rendu fantôme.

plugins {
    id("codeide.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    // C1 : frontière AIDL du processus :preview (miroir :preview:xml).
    buildFeatures {
        aidl = true
        compose = true
    }
}

dependencies {
    // DispatcherProvider + AppLogger (contrats du domaine, injectés).
    api(project(":core:domain"))

    // Modèle NEUTRE de découverte (PreviewKotlin + arbre d'appels —
    // frontière D6 du LSP Kotlin, pack 1.4.0 requis pour previews(uri)).
    api(libs.lsp.kotlin)

    // C2 (PREVIEW.md §4.4) — RÉUTILISATION des résolveurs PE1 : la
    // composition CONSULTE les ressources/thèmes du projet par les
    // MÊMES classes que le moteur XML du même processus :preview
    // (« une seule machinerie, deux dialectes », étude PE14 §5.3-1).
    // Coordonnées EXTERNES hors audit ProjectDependency — même statut
    // que lsp-kotlin ci-dessus (ADR 0140/0141).
    implementation(libs.layoutlib.resources)
    implementation(libs.layoutlib.themes)

    // Flows d'état du moteur.
    implementation(libs.kotlinx.coroutines.core)

    // MainExecutor du service (ContextCompat.getMainExecutor).
    implementation(libs.androidx.core.ktx)

    // Runtime Compose EMBARQUÉ (PC1/PC2) — BOM 2026.09.00 : runtime +
    // ui (ComposeView, AndroidComposeView) + foundation (Column/Row/
    // Box/Spacer) + material3 (Text/Button) + ui-tooling-preview
    // (LocalInspectionMode) ; lifecycle-runtime-compose pour les
    // propriétaires de cycle de vie hors fenêtre.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Propriétaire d'état sauvegardé factice de l'exécuteur off-screen.
    implementation(libs.androidx.savedstate)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Fakes du domaine (TestDispatcherProvider : horloge virtuelle unique).
    testImplementation(project(":core:testing"))
}

kover {
    reports {
        verify {
            rule("couverture-minimale-preview-compose") {
                bound {
                    // C1 : seuil de mise en service — l'exécuteur off-screen
                    // (RenduCompose) vit surtout derrière ses tests
                    // Robolectric ; le reste du module est pur.
                    minValue.set(74)
                }
            }
        }
    }
}
