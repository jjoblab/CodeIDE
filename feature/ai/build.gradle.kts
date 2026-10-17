// :feature:ai — tiroir IA (mission IA, ADR 0122) : fragments
// Chat / Agent / Historique, écrans Paramètres → IA (fournisseurs,
// confidentialité, limites), consentement de premier usage, rendu
// Markdown. Vues XML + ViewBinding, Material 3 (ADR 0002) — pas de
// Compose. Ressources FR (values/) et EN (values-en/).
//
// EXCEPTION FEATURE EXPLICITE (ADR 0122, enregistrée dans le
// ModuleRulesPlugin) : :feature:ai → :core:ai — la logique métier IA
// vit dans le domaine (règle 5 du plan), les ViewModels n'orchestrent
// que des cas d'usage. Même esprit que :feature:terminal →
// :core:terminal-runtime. L'éditeur (:feature:editor) n'entre JAMAIS
// ici : il passe par PontIaEditeur / PortEditeurActif (core:ui /
// core:domain).
//
// IA-0 : échafaudage — module vide compilable ; le fragment IA du
// tiroir (6ᵉ destination du rail) arrive au lot IA-3.

plugins {
    id("codeide.android.feature")
}

dependencies {
    // Domaine IA : cas d'usage et ports consommés par les ViewModels.
    // Exception explicite de la règle « feature → trio » (ADR 0122).
    implementation(project(":core:ai"))

    // Dépendances de test (robolectric, turbine, core:testing) ajoutées
    // au lot IA-3 avec les premiers tests réels de ViewModels — un
    // module échafaudé sans test ne déclare pas de classpath de test
    // (échec failOnNoDiscoveredTests, AGP 9).
}

// AGP 9 (« failOnNoDiscoveredTests ») : un module échafaudé sans test
// fait échouer testDebugUnitTest — les premiers tests réels (ViewModels
// du tiroir) arrivent au lot IA-3. Le drapeau est borné à CE module
// échafaudé ; aucune autre vérification n'est touchée.
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}
