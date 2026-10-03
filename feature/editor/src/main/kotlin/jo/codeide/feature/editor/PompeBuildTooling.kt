package jo.codeide.feature.editor

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EvenementSyncFlux
import jo.codeide.core.domain.GradleToolingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pompe **process-wide** des canaux de build vers l'état du tooling
 * (v0.37.3, seconde moitié du correctif « connexion avec l'orchestrateur
 * perdue » — voir ADR 0057 pour le détenteur process-wide).
 *
 * Retour d'appareil réel : la vidange des canaux de sortie vivait dans
 * le `viewModelScope` de l'espace de travail — fermer l'éditeur EN PLEIN
 * BUILD annulait les collecteurs, le canal borné du client (4096) se
 * remplissait, la pompe interne bloquait, le socket n'était plus lu :
 * les pongs du daemon ne remontaient plus, le bilan de santé tuait la
 * connexion, et la console affichait « connexion avec l'orchestrateur
 * perdue » au milieu d'un build pourtant vivant.
 *
 * Le correctif serveur (pong écrit hors bus) a réglé la contre-pression
 * côté orchestrateur ; celui-ci règle la moitié cliente : la vidange
 * vit dans une portée interne du **singleton**, elle survit à la mort
 * de l'espace — un build quitté continue d'alimenter l'état process-wide
 * (console rejouée au ré-attachement, notification honnête), et le canal
 * du client se vide TOUJOURS.
 *
 * Une vidange par build exactement (appels rejoués sans effet) : les
 * canaux du client se ferment à la fin du build (ADR 0041/0065), la
 * vidange se conclut alors d'elle-même et sort du registre.
 *
 * v0.45.1 (affichage immédiat, parité Android Studio) :
 * - les TÉLÉCHARGEMENTS du build ont désormais leur vidange — le canal
 *   `observeTelechargementsBuild` n'avait AUCUN consommateur : les
 *   événements s'accumulaient dans un canal que personne ne lisait (et
 *   au-delà de 4096, la pompe du client — coroutine UNIQUE — se serait
 *   bloquée sur `send`, gelant sorties ET pongs). La progression des
 *   artefacts alimente la rangée en place de la vue Build.
 * - la PROGRESSION SYNC a sa vidange process-wide ([pomperSync]) —
 *   elle vivait dans le `viewModelScope` de l'espace : écran fermé en
 *   pleine sync, les 256 places du canal se remplissaient et la même
 *   pompe se bloquait (famille exacte du bug corrigé ici pour les
 *   builds). L'espace ne fait plus que DEMANDER la vidange ; l'état
 *   des étapes reste porté par le service process-wide.
 *
 * v0.46.0 (console flux brut, ADR 0078) : le canal des TÂCHES reste
 * VIDÉ mais ne publie plus rien — Gradle écrit lui-même ses lignes
 * « > Task :app:xxx » sur le flux stdout, la console les montre telles
 * quelles (la leçon v0.45.1 reste : une pompe unique ne doit JAMAIS
 * bloquer sur un canal sans consommateur). L'option « afficher les
 * tâches » disparaît avec les rangées structurées.
 *
 * v0.48.0 (ADR 0079) : la vidange SYNC draine le FLUX ORDONNÉ COMPLET
 * ([EvenementSyncFlux] — départ, lignes stdout/stderr, étapes, TERMINAL)
 * et PUBLIE ELLE-MÊME le résultat : la coroutine lancante ne concluait
 * que son PROPRE lancement — la revalidation silencieuse (v0.40.1) ne
 * publiait jamais le résultat alors que son SyncStarted avait armé
 * l'état « en cours » : l'en-tête restait « étape n/N » et le chrono
 * couraient POUR TOUJOURS (retour terrain v0.47.0 : « à la fin du sync
 * l'UI n'est toujours pas à jour — la console et l'en-tête »). La
 * vidange survit aux écrans, conclut TOUTE sync sur le FAIT du serveur,
 * et l'ordre du canal garantit « console vidée → lignes et étapes →
 * conclusion ».
 *
 * v0.49.0 (ADR 0080) : une vidange qui MEURT d'une pan non prévue
 * laissait le canal aval se remplir POUR TOUJOURS (SupervisorJob sans
 * témoin : la mort était silencieuse) — en amont, la pompe unique du
 * client finissait suspendue sur un `send` plein, le socket n'était
 * plus lu, plus aucun pong : « orchestrateur muet » (retour terrain
 * v0.48). Chaque événement traverse désormais SANS PAN (journalisée,
 * la vidange continue au suivant) et la vidange elle-même est RELANCÉE
 * bornée si elle meurt quand même.
 *
 * @param tooling port du dépôt tooling (canaux de sortie, état, tâches,
 *        téléchargements, progression sync).
 * @param serviceGradle détenteur process-wide de l'état affichable.
 * @param journal traces des pans de vidange (v0.49.0 : une mort
 *        silencieuse est une panne invisible — elle se journalise).
 * @param dispatchers répartition des fils (règle 5 : injectés).
 */
@Singleton
class PompeBuildTooling
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val serviceGradle: GradleService,
        private val journal: AppLogger,
        dispatchers: DispatcherProvider,
    ) {
        /** Portée interne : survit aux écrans, meurt avec le processus. */
        private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)

        /** Vidanges vivantes, par identifiant de build. */
        private val vidanges = HashMap<String, Job>()

        /** Vidange sync déjà lancée (UNE par process — idempotente). */
        private val vidangeSyncLancee = AtomicBoolean(false)

        /**
         * Publie le build suivi (reset console + chrono, même sémantique
         * que l'ancien `suivreBuild` de la couture) et garantit qu'UNE
         * vidange des canaux alimente l'état process-wide — le lancement
         * de build, le ré-attachement d'un espace et le service de
         * notification peuvent tous l'appeler : seul le premier branche,
         * les relances ne doublonnent jamais.
         */
        fun pomper(
            buildId: String,
            taches: List<String> = emptyList(),
        ) {
            serviceGradle.suivreBuild(buildId, taches)
            synchronized(vidanges) {
                if (vidanges.containsKey(buildId)) return
                val travail = portee.launch { vider(buildId) }
                vidanges[buildId] = travail
                travail.invokeOnCompletion {
                    synchronized(vidanges) { vidanges.remove(buildId) }
                }
            }
        }

        /**
         * Les quatre collecteurs en fratrie supervisée : l'échec imprévu
         * de l'un n'arrête pas les autres (la fin de build est portée par
         * la COMPLÉTION des canaux, pas par une exception). v0.49.0 : un
         * événement fautif est journalisé et PASSÉ — le collecteur continue
         * de vider (une vidange morte, c'est un canal qui ne se vide plus,
         * et l'amont qui finit par geler — cf. ADR 0080).
         */
        private suspend fun vider(buildId: String) =
            supervisorScope {
                launch {
                    tooling.observeBuildOutput(buildId).collect { ligne ->
                        traiterSansPanne("sortie du build $buildId") { serviceGradle.ajouterLigne(ligne) }
                    }
                }
                launch {
                    tooling.observeBuildState(buildId).collect { etat ->
                        traiterSansPanne("état du build $buildId") { serviceGradle.publierEtatBuild(etat) }
                    }
                }
                launch {
                    // v0.46.0 : les événements de tâches ne produisent PLUS
                    // de lignes (Gradle écrit les siennes sur stdout — les
                    // afficher deux fois était le problème « deux endroits »)
                    // mais le canal reste VIDÉ : la pompe du client ne doit
                    // jamais bloquer sur un canal sans consommateur
                    // (leçon v0.45.1). v0.49.0 : la vidange ne meurt plus
                    // d'une pan non prévue non plus.
                    tooling.observeTachesBuild(buildId).collect { }
                }
                // v0.45.1 : la progression des artefacts du build alimente
                // la rangée en place de la vue Build — SANS cette vidange,
                // le canal ne se drainait jamais (aucun consommateur) et
                // la pompe du client finirait par s'y bloquer.
                launch {
                    tooling.observeTelechargementsBuild(buildId).collect { telechargement ->
                        traiterSansPanne("téléchargement du build $buildId") {
                            serviceGradle.ajouterTelechargement(telechargement)
                        }
                    }
                }
            }

        /**
         * Vidange process-wide du FLUX DE SYNC ORDONNÉ (v0.45.1 pour les
         * étapes ; v0.48.0, ADR 0079 pour les lignes et le TERMINAL) : le
         * départ annoncé PAR le serveur arme l'état (vidage de console +
         * chrono), les lignes stdout/stderr et les étapes s'écrivent au fil
         * de l'eau, le TERMINAL publie le RÉSULTAT — sur le fait du serveur,
         * même si la coroutine lancante est morte, même pour une sync
         * lancée par un autre écran. L'ordre du canal EST l'ordre du câble :
         * la conclusion arrive après tout ce qu'elle conclut.
         *
         * v0.49.0 (ADR 0080) : événements traversés SANS PAN + relance
         * bornée de la vidange entière — sa mort silencieuse laissait le
         * canal 256 se remplir à jamais puis gelait la pompe du client
         * (c'était la boucle « muet → kill → relance » de la v0.48).
         *
         * Idempotente : appels rejoués sans effet, UNE vidange par process.
         */
        fun pomperSync() {
            if (!vidangeSyncLancee.compareAndSet(false, true)) return
            portee.launch {
                var relances = 0
                while (true) {
                    try {
                        tooling.observeFluxSync().collect { evenement ->
                            traiterSansPanne("flux sync") { publier(evenement) }
                        }
                        // Complétion normale : le canal ne se ferme qu'à la
                        // fin du process — rien à relancer.
                        return@launch
                    } catch (annulation: CancellationException) {
                        throw annulation
                    } catch (t: Throwable) {
                        relances++
                        journal.e(
                            TAG,
                            t,
                        ) { "la vidange sync est morte (${t.message}) — relance $relances/$MAX_RELANCES" }
                        if (relances >= MAX_RELANCES) return@launch
                        delay(DELAI_RELANC_E_MS)
                    }
                }
            }
        }

        /** Publication d'un événement du flux sync (v0.48.0, ADR 0079). */
        private fun publier(evenement: EvenementSyncFlux) {
            when (evenement) {
                is EvenementSyncFlux.Debut -> serviceGradle.marquerSyncEnCours()
                is EvenementSyncFlux.Ligne -> serviceGradle.ajouterLigneSync(evenement.sortie)
                is EvenementSyncFlux.Etape -> serviceGradle.ajouterEtapeSync(evenement.etape)
                is EvenementSyncFlux.Terminal -> serviceGradle.publierResultatSync(evenement.resultat)
            }
        }

        /**
         * Exécute un traitement de vidange SANS le laisser tuer son
         * collecteur : la pan est journalisée, l'événement suivant traverse
         * — une vidange morte ne vide plus son canal, et c'est TOUTE la
         * chaîne qui finit gelée (retour terrain v0.48, ADR 0080).
         */
        private suspend fun traiterSansPanne(
            quoi: String,
            traitement: suspend () -> Unit,
        ) {
            try {
                traitement()
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (t: Throwable) {
                journal.e(TAG, t) { "pan de vidange ($quoi) : ${t.message} — la vidange continue" }
            }
        }

        private companion object {
            /** Tag du journal des vidanges (v0.49.0). */
            const val TAG = "PompeBuilds"

            /** Bornage des relances d'une vidange morte (v0.49.0). */
            const val MAX_RELANCES = 3

            /** Attente avant de relancer une vidange morte (v0.49.0). */
            const val DELAI_RELANC_E_MS = 1_000L
        }
    }
