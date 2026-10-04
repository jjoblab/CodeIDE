// feature:install — écran d'installation du bootstrap natif (Terminal T3).
// Autonome et déclenchable à la demande ; l'étape « Terminal » de
// l'onboarding et le bandeau de l'accueil y naviguent (prompt Terminal-1,
// sections 3.4 et 6 : la progression est partagée, jamais rejouée).

plugins {
    id("codeide.android.feature")
}

// Exception lint ciblée et commentée (règle 9 du prompt maître) :
// Aligned16KB signale libtermux.so (terminal-emulator, transitive depuis
// la v0.54.0) non alignée 16 KB — vérifié le 2026-09-23 sur toutes les
// versions publiées (v0.118.3 comme v0.119.0-beta.3 : p_align 4096) ;
// même exception documentée dans feature:terminal (ADR 0035) ; à retirer
// dès qu'une release Termux alignée paraît.
android {
    lint {
        disable.add("Aligned16KB")
    }
}

dependencies {
    // v0.54.0 : le journal live de la configuration est un MINI TerminalView
    // intégré à l'écran d'installation — mêmes artefacts que
    // feature:terminal (le POM JitPack de terminal-view ne publie pas sa
    // dépendance à terminal-emulator : les deux sont déclarés).
    implementation(project(":core:terminal-runtime"))
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)

    // Tests du ViewModel : fake de l'installateur (core:testing).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
