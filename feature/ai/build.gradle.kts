// :feature:ai — tiroir IA et écrans Paramètres → IA (mission IA, ADR
// 0122) : fragments Réglages IA / Détail fournisseur / Consentement
// (lot IA-2), puis Chat / Agent / Historique du tiroir (lot IA-3).
// Vues XML + ViewBinding, Material 3 (ADR 0002) — pas de Compose.
// Ressources FR (values/) et EN (values-en/), préfixe ia_.
//
// EXCEPTION FEATURE EXPLICITE (ADR 0122, enregistrée dans le
// ModuleRulesPlugin) : :feature:ai → :core:ai — la logique métier IA
// vit dans le domaine (règle 5 du plan), les ViewModels n'orchestrent
// que des cas d'usage. Même esprit que :feature:terminal →
// :core:terminal-runtime. L'éditeur (:feature:editor) n'entre JAMAIS
// ici : il passe par PontIaEditeur / PortEditeurActif (core:ui /
// core:domain). Les réglages et le coffre sont consommés par leurs
// ports du domaine (ReglagesIaRepository, CoffreClesIa — core:domain) :
// le module ne dépend NI de :core:ai-storage, NI de :ai:providers.

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Domaine IA : cas d'usage et ports consommés par les ViewModels.
    // Exception explicite de la règle « feature → trio » (ADR 0122).
    implementation(project(":core:ai"))

    // Tests : ViewModels (UDF), layouts (Robolectric), garde-fous de
    // confidentialité (aucune clé dans un état d'UI).
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
