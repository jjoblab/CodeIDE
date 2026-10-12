// :preview:xml — moteur de preview XML (mission « Previews », étape PE1 —
// docs/PREVIEW.md §3, décisions P1–P5).
//
// MOTEUR DE RENDU LayoutLib (jjoblab/LayoutLib, projet personnel du
// propriétaire — P1 : LayoutLib est LE moteur, pas de réécriture).
// Intégration en SOURCE par build composite (P2 révisée 2026-10-13,
// décision propriétaire : dépôt GitHub-only, AUCUNE publication JitPack) :
// les coordonnées com.github.jjoblab.LayoutLib:module-inflater sont
// substituées par le checkout frère (settings.gradle.kts, tag épinglé
// v2.2.0) — l'évolution des deux projets reste découplée (bump de version
// = ref du clonage + catalogue), le build composite documenté (README
// LayoutLib) est l'option d'intégration générique.
//
// ISOLATION (P3/P4) : PE1 rend IN-PROCESS sur un FIL DÉDIÉ (jamais le
// thread UI de l'IDE — un crash ou un blocage de vue framework ne doit
// pas toucher l'éditeur) ; PE2 (PREVIEW.md §3.4) déplacera ce moteur dans
// le processus `:preview` (AIDL + DeathRecipient + repli in-process) —
// le contrat public de CE module (moteur + vue) est conçu pour ce
// déménagement sans changement de surface.
//
// Le pipeline reproduit le RenderService de LayoutLib (« RenderService
// adapté », PREVIEW.md §3.2) : validation XML préalable (XmlPullParser —
// XML malformé = frappe en cours → dernier rendu valide conservé,
// comportement Android Studio), inflation par vraies classes Android
// (réflexion, ViewTagRegistry), mesure/layout (LayoutEngineImpl) puis
// dessin bitmap OFF-SCREEN (P4 : aucune vue n'est attachée à la
// hiérarchie de l'IDE). Le RenderService d'origine exécute sur le thread
// PRINCIPAL — cette divergence est le cœur de P3.

plugins {
    id("codeide.android.library")
}

dependencies {
    // DispatcherProvider + AppLogger (contrats du domaine, injectés).
    api(project(":core:domain"))

    // Flows d'état du moteur.
    implementation(libs.kotlinx.coroutines.core)

    // Moteur de rendu (P1/P2 révisée) — v2.2.0 : coordonnées substituées
    // par le build composite (checkout frère, tag v2.2.0 aligné toolchain
    // CodeIDE — Gradle 9.7.1/AGP 9.4.1/JDK 21, un seul environnement pour
    // les deux dépôts). Les 5 modules frères (resources/drawables/themes/
    // attributes/layout) arrivent en transitif — api(project(...)) du
    // build inclus.
    implementation(libs.layoutlib.inflater)

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
            rule("couverture-minimale-preview-xml") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
