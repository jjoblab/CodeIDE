package jo.codeide.feature.editor

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.GradleToolingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
 * @param tooling port du dépôt tooling (canaux de sortie, état, tâches).
 * @param serviceGradle détenteur process-wide de l'état affichable.
 * @param optionsTooling réglages tooling vivants (affichage des tâches
 *        relu à CHAQUE événement — une bascule en plein build prend
 *        effet immédiatement, même contrat qu'avant).
 * @param dispatchers répartition des fils (règle 5 : injectés).
 */
@Singleton
class PompeBuildTooling
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val serviceGradle: GradleService,
        private val optionsTooling: OptionsTooling,
        dispatchers: DispatcherProvider,
    ) {
        /** Portée interne : survit aux écrans, meurt avec le processus. */
        private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)

        /** Vidanges vivantes, par identifiant de build. */
        private val vidanges = HashMap<String, Job>()

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
         * Les trois collecteurs en fratrie supervisée : l'échec imprévu
         * de l'un n'arrête pas les autres (la fin de build est portée par
         * la COMPLÉTION des canaux, pas par une exception).
         */
        private suspend fun vider(buildId: String) =
            supervisorScope {
                launch {
                    tooling.observeBuildOutput(buildId).collect { ligne ->
                        serviceGradle.ajouterLigne(ligne)
                    }
                }
                launch {
                    tooling.observeBuildState(buildId).collect { etat ->
                        serviceGradle.publierEtatBuild(etat)
                    }
                }
                launch {
                    tooling.observeTachesBuild(buildId).collect { tache ->
                        // Réglage relu à CHAQUE événement (v3) : une bascule
                        // « afficher les tâches » en plein build prend effet
                        // immédiatement.
                        if (optionsTooling.afficherTaches) {
                            serviceGradle.ajouterTache(tache)
                        }
                    }
                }
            }
    }
