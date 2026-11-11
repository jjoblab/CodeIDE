// tooling:daemon — daemon du tooling Gradle (G4, prompt compagnon Tooling,
// section 5.4).
//
// Bibliothèque Android + Hilt : déploie le JAR orchestrateur depuis les
// assets (marqueur de version), ouvre l'écoute du socket AVANT le
// lancement (délégué à tooling:client), lance le process JVM via le port
// NativeProcessLauncher (jamais redéfini — impl. core:bootstrap), valide
// le handshake, surveille la santé ping/pong et redémarre borné
// (MAX_RECONNECT_ATTEMPTS). Le stderr du process rejoint le journal
// applicatif (tag `gradle-server`).

plugins {
    id("codeide.android.library")
    id("codeide.android.hilt")
}

dependencies {
    // Façade du client (sessions, santé, états de connexion) et, en
    // transitif par elle : le protocole et le domaine.
    api(project(":tooling:client"))
    api(project(":core:domain"))

    // Test du lancement réel §7.4 : le VRAI orchestrateur tourne dans un
    // sous-processus java lancé depuis la JVM de test — dépendance de TEST
    // uniquement (ADR 0042). Le serveur vit dans le dépôt séparé
    // jjoblab/codeide-tooling depuis la bascule du 2026-10-17 (plan §9) :
    // composite en dev (substitution settings.gradle.kts racine), binaire
    // sinon. NB : en composite, la substitution fournit le JAR MINCE du
    // projet — les tests §7.4 sont écrits pour (classpath, pas shadowJar).
    testImplementation(libs.tooling.server)
    testImplementation(libs.tooling.testing)
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

kover {
    reports {
        filters {
            excludes {
                // Colle Android de l'hôte de socket (délégation pure à
                // GradleSocketServer de tooling:client — LocalSocket n'a
                // aucune shadow Robolectric 4.17, filtré là-bas pour la même
                // raison) et source d'assets AssetManager ; code généré
                // Hilt/Dagger (fabriques des @Provides, agrégateur Hilt).
                classes(
                    "jo.codeide.tooling.daemon.HoteSocketAndroid",
                    "jo.codeide.tooling.daemon.SourceJarAssets",
                    "jo.codeide.tooling.daemon.ModuleDaemon",
                    "jo.codeide.tooling.daemon.ModuleDaemon_*",
                    "jo.codeide.tooling.daemon.HiltWrapper_ModuleDaemon",
                    "*jo_codeide_tooling_daemon_HiltWrapper_ModuleDaemon",
                )
            }
        }
        verify {
            rule("couverture-minimale-daemon-tooling") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}
