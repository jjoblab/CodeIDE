// core:terminal-runtime — sessions shell réelles (prompt compagnon
// Terminal-1, section 4) : registre global (TerminalSessionRepository),
// TerminalService foreground, API de rendu réservée à feature:terminal.
// Dépend de terminal-emulator (Termux, Apache-2.0) — jamais terminal-view.

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

// Exception lint ciblée et commentée (règle 9 du prompt maître) :
// Aligned16KB signale libtermux.so (terminal-emulator) non alignée
// 16 KB — vérifié le 2026-09-23 sur TOUTES les versions publiées
// (v0.118.3 comme v0.119.0-beta.3 : p_align 4096) ; le binaire est
// compilé en amont, rien n'est actionnable côté app. Signalé pour
// suivi amont ; à retirer dès qu'une release Termux alignée paraît.
android {
    lint {
        disable.add("Aligned16KB")
    }
}

dependencies {
    // Ports du domaine (ToolchainLocator, ProcessEnvironmentProvider,
    // TerminalSessionRepository) et traduction des sessions réelles.
    api(project(":core:domain"))

    // Environnement canonique des sous-processus (ADR 0032/0033).
    implementation(project(":core:bootstrap"))

    // Sessions shell interactives via pseudo-terminal (prompt Terminal-1,
    // section 1.5 — mécanisme dédié, PAS NativeProcessLauncher).
    // Termux terminal-emulator, Apache-2.0 (THIRD_PARTY_NOTICES.md).
    implementation(libs.termux.terminal.emulator)

    // Notification honnête du service foreground (NotificationCompat).
    implementation(libs.androidx.core.ktx)

    // Dispatchers.Main réel (v0.31.2, ADR 0046) : les sessions Termux
    // DOIVENT naître sur le thread principal — TerminalSession crée un
    // Handler dans son constructeur (crash d'appareil réel 511e1c7f :
    // « Can't create handler inside thread that has not called
    // Looper.prepare() »). Artefact canonique du dispatcher principal
    // Android, chargé via ServiceLoader à l'exécution.
    implementation(libs.kotlinx.coroutines.android)

    // Tests du registre (fausses coquilles de session, temps virtuel) et
    // du service foreground (cycle de vie réel sous Robolectric).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// Objectif de couverture ≥ 80 % sur ce module (section 8 du prompt) —
// la colle Termux/JNI (CoquilleTermux instancie une vraie session pty,
// ClientTermux adapte le contrat tiers, FabriqueCoquillesTermux la
// construit) est exclue : elle ne peut physiquement pas s'exécuter sur
// JVM (bibliothèque native libtermux.so) — c'est précisément pour cela
// que l'indirection CoquilleSession existe : la logique, elle, est
// couverte à 100 % (registre + décision + service sous Robolectric).
kover {
    reports {
        filters {
            excludes {
                // Colle Termux/JNI : exécution impossible en JVM.
                classes(
                    "jo.codeide.core.terminalruntime.ClientTermux",
                    "jo.codeide.core.terminalruntime.CoquilleTermux",
                    "jo.codeide.core.terminalruntime.FabriqueCoquillesTermux",
                )
                // Code GÉNÉRÉ par Hilt/Dagger (fabriques, injecteurs,
                // composants, agrégation) : le câblage réel est validé par
                // hiltJavaCompileDebug à l'échelle de l'application.
                classes(
                    "*.Hilt_*",
                    "*_Factory",
                    "*_MembersInjector",
                    "*_GeneratedComponentManager*",
                    "hilt_aggregated_deps.*",
                )
            }
        }
        verify {
            rule("couverture-minimale-terminal-runtime") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
