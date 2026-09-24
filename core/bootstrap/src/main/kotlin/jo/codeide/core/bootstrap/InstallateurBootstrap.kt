package jo.codeide.core.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.bootstrap.SupervisionProcessus.Sortie
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.EtatInstallationBootstrap
import jo.codeide.core.model.EtatInstallationBootstrap.Annulee
import jo.codeide.core.model.EtatInstallationBootstrap.Echouee
import jo.codeide.core.model.EtatInstallationBootstrap.EnCours
import jo.codeide.core.model.EtatInstallationBootstrap.NonDemarree
import jo.codeide.core.model.EtatInstallationBootstrap.Terminee
import jo.codeide.core.model.OutilResume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Installateur du bootstrap natif (prompt compagnon Terminal-1,
 * section 3.4) — implémentation de [BootstrapInstaller].
 *
 * Pipeline coroutine, rejoué de zéro à chaque démarrage (le staging est
 * nettoyé) :
 *
 * 1. vérification de l'espace disque et de l'architecture (seul
 *    `aarch64` est publié à ce jour) ;
 * 2. téléchargement de l'archive avec progression et **vérification de
 *    l'empreinte SHA-256** ;
 * 3. extraction vers `usr-staging` (fichiers réguliers + permissions
 *    d'exécution), puis liens symboliques du manifeste `SYMLINKS.txt` ;
 * 4. bascule atomique vers le préfixe (tout préfixe existant est
 *    détruit : une reprise rejoue l'installation complète, y compris le
 *    second stage — son verrou vit sous le préfixe et disparaît avec
 *    lui) ;
 * 5. exécution du script de second stage via [NativeProcessLauncher]
 *    (postinst des paquets, verrou anti double exécution) ;
 * 6. écriture/correction du `sources.list` du dépôt CodeIDE ;
 * 7. `apt update` puis installation des paquets d'outils **un par un**
 *    — un paquet absent du dépôt est rapporté non installé, seul
 *    l'échec total lève une erreur.
 *
 * L'état est partagé ([etat]) : l'onboarding et le déclenchement à la
 * demande observent la même installation, jamais doublée (`demarrer()`
 * est sans effet pendant `EnCours` et après `Terminee`).
 *
 * Le pipeline vit dans une portée interne : il survit à la rotation et
 * aux changements d'écran — l'appelant n'a pas de coroutine à conserver.
 *
 * Exemption ciblée (LongParameterList) : constructeur d'injection Hilt,
 * collaborateurs imposés par le périmètre exact du prompt Terminal-1
 * (section 3.4) — précédent ToolchainBootstrap à l'étape T1.
 */
@Suppress("LongParameterList")
@Singleton
internal class InstallateurBootstrap
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val dispatchers: DispatcherProvider,
        private val lanceur: NativeProcessLauncher,
        private val espaceDisque: EspaceDisqueSonde,
        private val architecture: CapaciteArchitecture,
        private val configuration: ConfigurationBootstrap,
        private val journalApp: AppLogger,
        operations: OperationsSysteme,
    ) : BootstrapInstaller {
        private val racine: File = contexte.filesDir
        private val telechargeur = TelechargeurBootstrap(configuration, dispatchers)
        private val extracteur = ExtracteurBootstrap(operations, dispatchers)
        private val configurateur = ConfigurateurApt(lanceur, dispatchers)

        private val _etat = MutableStateFlow<EtatInstallationBootstrap>(NonDemarree)
        override val etat: StateFlow<EtatInstallationBootstrap> = _etat.asStateFlow()

        private val _journal = MutableStateFlow<List<String>>(emptyList())
        override val journal: StateFlow<List<String>> = _journal.asStateFlow()

        private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)
        private val verrou = Any()
        private var travail: Job? = null

        /** Dernière étape déjà journalisée (anti-rejeu des tics de progression). */
        private var derniereEtapeJournalisee: KClass<out EtapeInstallation>? = null

        override fun demarrer() {
            synchronized(verrou) {
                when (_etat.value) {
                    is EnCours, is Terminee -> return
                    NonDemarree, is Echouee, Annulee -> Unit
                }
                travail = portee.launch { executer() }
            }
        }

        override fun annuler() {
            synchronized(verrou) {
                travail?.cancel()
            }
        }

        /** Exécute le pipeline et traduit l'issue en état partagé. */
        private suspend fun executer() {
            // Nouvelle tentative : le journal repart à plat (celui de la
            // tentative échouée n'a plus de valeur une fois relancée).
            _journal.value = emptyList()
            derniereEtapeJournalisee = null
            val outils = mutableListOf<OutilResume>()
            try {
                majEtape(EtapeInstallation.VerificationEspaceDisque)
                verifierPrealables()

                preparerStaging()
                telechargeur.telecharger(DispositionsBootstrap.archiveStaging(racine)).collect(::majEtape)
                extracteur
                    .extraire(DispositionsBootstrap.archiveStaging(racine), DispositionsBootstrap.staging(racine))
                    .collect(::majEtape)

                majEtape(EtapeInstallation.BasculeVersPrefixe)
                extracteur.basculer(DispositionsBootstrap.staging(racine), DispositionsBootstrap.prefix(racine))

                majEtape(EtapeInstallation.SecondStage)
                executerSecondStage()

                val prefixe = DispositionsBootstrap.prefix(racine)
                majEtape(EtapeInstallation.ConfigurationApt)
                configurateur.ecrireSourcesList(prefixe, configuration.ligneDepotApt)

                majEtape(EtapeInstallation.MiseAJourApt)
                configurateur.miseAJour(prefixe, ::consignerAuJournal)

                val paquets = configuration.paquets
                for ((index, paquet) in paquets.withIndex()) {
                    majEtape(EtapeInstallation.InstallationPaquets(paquet, index + 1, paquets.size))
                    outils +=
                        OutilResume(
                            paquet = paquet,
                            installe = configurateur.installerPaquet(prefixe, paquet, ::consignerAuJournal) == null,
                        )
                }
                if (outils.isNotEmpty() && outils.none { it.installe }) {
                    throw EchecBootstrap(BootstrapReason.EchecApt, "aucun paquet d'outil n'a pu être installé")
                }

                nettoyerStaging()
                deposerMarqueurInstallation(racine)
                consignerAuJournal("installation terminée (${outils.count { it.installe }} outil(s) installé(s))")
                _etat.value = Terminee(outils.toList())
            } catch (e: CancellationException) {
                // Annulation demandée : état dédié, staging nettoyé, puis
                // relance systématique de l'annulation (règle 6 du prompt
                // maître — jamais avalée).
                //
                // Le nettoyage est **synchrone** : tout appel suspendu
                // (`withContext`, même `NonCancellable`) depuis une
                // coroutine déjà annulée ne revient pas — l'état `Annulee`
                // ne serait jamais publié (vérifié empiriquement).
                nettoyerStaging()
                _etat.value = Annulee
                throw e
            } catch (e: EchecBootstrap) {
                nettoyerStaging()
                consignerAuJournal("échec : ${e.raison}" + if (e.details.isNotBlank()) " — ${e.details}" else "")
                journalApp.w(TAG) { "installation échouée (${e.raison}) — ${e.details}" }
                _etat.value = Echouee(AppError.Bootstrap(e.raison, e.details))
            } catch (e: Exception) {
                // Erreur inattendue : modélisée en Unknown, jamais
                // remontée en exception jusqu'à l'UI (règle 6).
                nettoyerStaging()
                consignerAuJournal("erreur inattendue : ${e.message}")
                journalApp.w(TAG) { "installation échouée (erreur inattendue) : ${e.message}" }
                _etat.value = Echouee(AppError.Unknown("installation du bootstrap : ${e.message}"))
            }
        }

        /** Publie une étape en cours dans l'état partagé. */
        private fun majEtape(etape: EtapeInstallation) {
            _etat.value = EnCours(etape)
            // Journal d'écran : une ligne par ÉTAPE (pas par tic — le
            // téléchargement émet toutes les 512 Kio, l'extraction par
            // fichier : le compteur noierait la sortie d'apt qui suit).
            if (etape::class != derniereEtapeJournalisee) {
                derniereEtapeJournalisee = etape::class
                val libelle =
                    when (etape) {
                        is EtapeInstallation.Telechargement -> "téléchargement de l'archive…"
                        is EtapeInstallation.Extraction -> "extraction des fichiers…"
                        else -> LIBELLES_ETAPES[etape::class] ?: "étape en cours"
                    }
                consignerAuJournal(libelle)
                journalApp.i(TAG) { "étape : $libelle" }
            }
        }

        /**
         * Ajoute une ligne au journal d'écran (borné — les plus anciennes
         * lignes disparaissent, seule la fin du pipeline intéresse
         * l'écran). Thread-safe : appelée depuis le pipeline ET les
         * drainages de sous-processus.
         */
        private fun consignerAuJournal(ligne: String) {
            _journal.update { courant -> (courant + ligne).takeLast(LIMITE_JOURNAL_ECRAN) }
        }

        /**
         * Vérifications préalables au téléchargement : espace disque
         * suffisant (archive + extraction + paquets) et architecture
         * `aarch64` (seule publiée à ce jour) — un échec type lève
         * [EchecBootstrap] AVANT tout trafic réseau.
         */
        private fun verifierPrealables() {
            val libres = espaceDisque.octetsLibres(racine)
            if (libres < configuration.seuilEspaceDisque) {
                throw EchecBootstrap(
                    BootstrapReason.EspaceDisqueInsuffisant,
                    "libres : $libres octets, requis : ${configuration.seuilEspaceDisque}",
                )
            }
            if (!architecture.supporteAarch64()) {
                throw EchecBootstrap(BootstrapReason.ArchitectureNonSupportee, "l'appareil n'exécute pas arm64-v8a")
            }
        }

        /** Nettoie tout résidu d'une installation précédente. */
        private suspend fun preparerStaging() {
            withContext(dispatchers.io) {
                supprimerRecursivement(DispositionsBootstrap.staging(racine))
                DispositionsBootstrap.archiveStaging(racine).delete()
            }
        }

        /**
         * Supprime l'archive et le staging (fin, échec ou interruption).
         *
         * Délibérément **non suspendue** : appelée depuis les gestionnaires
         * d'échec **et d'annulation** — un `withContext`, même avec
         * `NonCancellable`, ne revient pas depuis une coroutine déjà
         * annulée (constat empirique, kotlinx-coroutines 1.11) et
         * l'état terminal ne serait jamais publié. La suppression est
         * bornée (staging et archive uniquement) et reste sur le fil
         * courant, rarement occupé à cet instant.
         */
        private fun nettoyerStaging() {
            supprimerRecursivement(DispositionsBootstrap.staging(racine))
            DispositionsBootstrap.archiveStaging(racine).delete()
        }

        /**
         * Lance le second stage via le lanceur canonique et en vérifie le code.
         *
         * Exemption ciblée (ThrowsCount) : un throw par échec TYPÉ (archive
         * incomplète, lancement refusé, code de sortie non nul), tous
         * rattrapés par le pipeline de [executer] — même convention que
         * `CreateProjectUseCase.ecrirePlan`.
         */
        @Suppress("ThrowsCount")
        private suspend fun executerSecondStage() {
            val prefixe = DispositionsBootstrap.prefix(racine)
            val script = File(prefixe, CHEMIN_SECOND_STAGE)
            val bash = File(prefixe, "bin/bash")
            if (!script.isFile || !bash.isFile) {
                throw EchecBootstrap(BootstrapReason.ArchiveCorrompue, "second stage ou bash absent du préfixe")
            }
            val processus =
                try {
                    lanceur.launch(listOf(bash.absolutePath, script.absolutePath), workingDir = prefixe)
                } catch (e: IOException) {
                    // Lancement refusé par le noyau — typiquement la
                    // restriction W^X (app targetSdk >= 29, Android 10+ :
                    // un binaire écrit dans les données de l'app ne s'exécute
                    // pas, EACCES). Rapport d'appareil réel 7842f130 :
                    // l'IOException non traduite tombait dans le fourre-tout
                    // « erreur inattendue » sans indice. Traduite comme le
                    // fait ConfigurateurApt pour apt — la raison permission
                    // porte un message actionnable.
                    throw EchecBootstrap(
                        BootstrapReason.PermissionRefusee,
                        "second stage non exécutable : ${e.message}",
                        cause = e,
                    )
                }
            val sortie: Sortie = SupervisionProcessus.attendre(processus, ::consignerAuJournal)
            if (sortie.code != 0) {
                throw EchecBootstrap(
                    BootstrapReason.EchecSecondStage,
                    "code ${sortie.code} — ${sortie.erreurs.joinToString(" / ")}",
                )
            }
        }

        private fun supprimerRecursivement(racine: File) {
            if (racine.isDirectory) {
                racine.listFiles()?.forEach { enfant -> supprimerRecursivement(enfant) }
            }
            racine.delete()
        }

        private companion object {
            private const val TAG = "Installateur"

            /** Chemin du second stage, relatif au préfixe (constaté dans l'archive réelle). */
            private const val CHEMIN_SECOND_STAGE =
                "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"

            /** Lignes du journal d'écran conservées (la fin du pipeline suffit). */
            private const val LIMITE_JOURNAL_ECRAN = 200

            /** Libellés d'étape du journal d'écran (sans compteur). */
            private val LIBELLES_ETAPES: Map<KClass<out EtapeInstallation>, String> =
                mapOf(
                    EtapeInstallation.VerificationEspaceDisque::class to "vérification de l'espace disque…",
                    EtapeInstallation.LiensSymboliques::class to "création des liens symboliques…",
                    EtapeInstallation.BasculeVersPrefixe::class to "finalisation de l'environnement…",
                    EtapeInstallation.SecondStage::class to "configuration des paquets de base…",
                    EtapeInstallation.ConfigurationApt::class to "configuration du dépôt de paquets…",
                    EtapeInstallation.MiseAJourApt::class to "mise à jour du dépôt…",
                    EtapeInstallation.InstallationPaquets::class to "installation des outils…",
                )
        }
    }

/**
 * Dépose le marqueur d'installation **terminée** sous le préfixe.
 *
 * Raison d'être (rapport d'appareil réel 7842f130, v0.29.0) : la bascule
 * atomique pose le préfixe AVANT le second stage — un échec ultérieur
 * laissait donc un préfixe complet aux yeux du marqueur d'extraction
 * (« shell présent »), et l'assistant affichait « bootstrap déjà
 * installé » après un échec. Le marqueur d'installation ne survit qu'à
 * un pipeline ALLÉ AU BOUT : [LocalisationOutils.bootstrapInstalle]
 * exige désormais les deux. Il vit sous le préfixe : une reprise
 * détruit celui-ci et repart de zéro, le marqueur disparaît avec lui.
 *
 * Fonction du fichier (hors classe) : l'installateur tient déjà le seuil
 * detekt de fonctions par classe — le dépôt est une opération de
 * pipeline, pas un comportement d'objet.
 */
private fun deposerMarqueurInstallation(racine: File) {
    runCatching {
        DispositionsBootstrap.marqueurInstallation(racine).writeText("")
    }
}
