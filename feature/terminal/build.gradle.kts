// feature:terminal — écran plein écran du terminal intégré (prompt
// compagnon Terminal-1, section 5) : toolbar, onglets de sessions,
// TerminalView unique rebranché, clavier étendu **interne** (termux-shared
// refusé pour licence, ADR 0035/0036), thèmes clair/sombre.

plugins {
    id("codeide.android.feature")
}

// Exception lint ciblée et commentée (règle 9 du prompt maître) :
// Aligned16KB signale libtermux.so (terminal-emulator, transitive) non
// alignée 16 KB — vérifié le 2026-09-23 sur toutes les versions publiées
// (v0.118.3 comme v0.119.0-beta.3 : p_align 4096) ; le binaire est
// compilé en amont, rien n'est actionnable côté app. Même exception
// documentée dans core:terminal-runtime (ADR 0035) ; à retirer dès
// qu'une release Termux alignée paraît.
android {
    lint {
        disable.add("Aligned16KB")
    }
}

dependencies {
    // Vraies sessions Termux (API de rendu réservée, section 4.4 du
    // prompt) — seule feature autorisée à dépendre du runtime.
    implementation(project(":core:terminal-runtime"))

    // Rendu du terminal (Apache-2.0, THIRD_PARTY_NOTICES.md). Le POM
    // JitPack de terminal-view ne publie pas sa dépendance à
    // terminal-emulator (portée projet) : les deux artefacts sont
    // déclarés explicitement.
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)

    // Tests du ViewModel : fakes du domaine (aucune session réelle).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
