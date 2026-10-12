// :preview:xml — moteur de preview XML (mission « Previews », étape PE1 —
// docs/PREVIEW.md §3, décisions P1–P5).
//
// MOTEUR DE RENDU LayoutLib (jjoblab/LayoutLib, projet du propriétaire —
// P1 : LayoutLib est LE moteur, pas de réécriture). Dépendance Maven
// versionnée v2.1.1 via JitPack (P2) : le dépôt est celui de l'auteur de
// CodeIDE, l'évolution des deux projets reste découplée (bump de version
// = mise à jour ici), le composite build reste l'option de développement
// local (README LayoutLib).
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

    // Moteur de rendu (P1/P2) — v2.1.1 : premier tag JitPack publiable du
    // dépôt (v2.1.0 y échouait : JDK 11 + pas de maven-publish ; recette
    // code-editor appliquée, commit 6c56ab8). Les 5 modules frères
    // (resources/drawables/themes/attributes/layout) arrivent en
    // transitif — JitPack réécrit les dépendances inter-modules.
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
