package jo.codeide.core.terminalruntime

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ConfigurationEnvTerminal
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.ToolchainLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de référence de [ConfigurationEnvTerminal] (v0.52.0,
 * ADR 0083) : la configuration automatique de l'environnement **vit dans
 * une session de terminal** — l'application crée la session, y « tape »
 * la commande `codeide-env` (voie clavier du pseudo-terminal) et y
 * revient ; le journal live défile dans le TerminalView, le rendu du
 * terminal EST l'écran de progression.
 *
 * Découpage des responsabilités (ADR 0083) : cette classe décide et
 * orchestre ; le script `codeide-env` (posé dans `$PREFIX/bin` par
 * `core:bootstrap`, versionné) fait — mise à jour des paquets, OpenJDK,
 * délégation à `android-sdk installer`, pont `ide-environment.properties`,
 * vérifications, marqueur d'idempotence.
 *
 * Une seule session de configuration à la fois : un verrou interne garde
 * l'identifiant de la dernière créée — si son shell vit encore,
 * [lancer][ConfigurationEnvTerminal.lancer] retourne CETTE session
 * (l'appelant y revient) au lieu d'en ouvrir une seconde ; si elle est
 * morte ou fermée et que l'environnement reste incomplet, une nouvelle
 * session est créée (reprise — chaque étape du script est idempotente).
 *
 * La complétude se lit sur le disque ([ToolchainLocator]) : une
 * installation faite à la main dans le terminal compte autant qu'une
 * installation pilotée.
 */
@Singleton
internal class ConfigurationEnvTermux
    @Inject
    constructor(
        private val localisateur: ToolchainLocator,
        private val environnement: ProcessEnvironmentProvider,
        private val registre: RegistreSessionsTermux,
        private val journal: AppLogger,
    ) : ConfigurationEnvTerminal {
        /** Identifiant de la dernière session de configuration créée. */
        private var sessionConfiguration: String? = null

        /** Série : jamais deux créations concurrentes de session. */
        private val verrou = Mutex()

        override fun estComplet(): Boolean = localisateur.isJdkInstalled() && localisateur.isAndroidSdkInstalled()

        override suspend fun lancer(): String? {
            // Rien à faire sans bootstrap (la base précède la
            // configuration) ni environnement complet (JDK et SDK en
            // place) : `null`, jamais d'exception.
            if (!localisateur.isBootstrapInstalled() || estComplet()) return null
            return verrou.withLock {
                sessionConfiguration?.let { id ->
                    // Les métadonnées du registre (et non l'API de rendu
                    // Termux) portent la vie du shell : une session morte
                    // naturellement ou fermée explicitement ne compte plus.
                    if (registre.observeSessions().value.any { it.id == id && it.isAlive }) {
                        // La session de configuration vit déjà : y revenir,
                        // pas en ouvrir une seconde.
                        return@withLock id
                    }
                }

                val id = registre.createSession(repertoireTravail(), LIBELLE_SESSION)
                // Laisser le shell démarrer avant la frappe : l'entrée du
                // pseudo-terminal tamponne (aucune frappe perdue), le délai
                // n'est qu'esthétique — la bannière de bienvenue du profil
                // se pose AVANT la commande, comme une frappe humaine.
                delay(DELAI_DEMARRAGE_MS)
                registre.envoyerTexte(id, COMMANDE_ENV)
                sessionConfiguration = id
                journal.i(TAG) { "configuration de l'environnement lancée (codeide-env)" }
                id
            }
        }

        /**
         * Répertoire de travail de la session : le `HOME` du shell —
         * c'est là que vit le SDK (`android-sdk/`), même convention que
         * l'écran du terminal pour une session sans suggestion.
         */
        private fun repertoireTravail(): File {
            val home = environnement.baseEnvironment()["HOME"]
            return if (home.isNullOrBlank()) File(HOME_PAR_DEFAUT) else File(home)
        }

        private companion object {
            const val TAG = "ConfigEnvTerminal"

            /** Libellé de la session dédiée (renommable par l'utilisateur). */
            const val LIBELLE_SESSION = "Configuration"

            /** Commande « tapée » dans la session (`\r` vaut Entrée). */
            const val COMMANDE_ENV = "codeide-env\r"

            /** Pose du shell avant la frappe (esthétique, voir [lancer]). */
            const val DELAI_DEMARRAGE_MS = 1_200L

            /** Repli du répertoire de travail (HOME illisible). */
            const val HOME_PAR_DEFAUT = "/"
        }
    }
