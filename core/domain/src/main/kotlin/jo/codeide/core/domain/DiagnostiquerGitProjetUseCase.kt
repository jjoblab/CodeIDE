package jo.codeide.core.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Cas d'usage « Diagnostic Git » (v0.90.1, mission « section Git figée »
 * étape A) : assemble le [RapportDiagnosticGit] du **projet courant** pour
 * l'écran Diagnostic.
 *
 * « Projet courant » = le projet le plus récemment **ouvert** dans
 * l'éditeur (`lastOpenedAtMillis` maximal, marqué avant l'ouverture de
 * `EditorActivity`). L'écran Diagnostic est un écran global du graphe de
 * `MainActivity` — il ne reçoit pas d'identifiant de projet par
 * navigation ; le plus récent est le projet sur lequel le défaut se
 * produit (l'utilisateur vient de le cloner et d'ouvrir l'éditeur). En
 * l'absence de tout projet, le diagnostic reste utile : les sondes
 * globales (binaire, version, uid, environnement) s'exécutent quand
 * même — c'est le cas « git est-il installé ? ».
 *
 * Le rapport ne présuppose rien : un chemin FUSE irrésolvable devient
 * `cheminFuse = null` dans le rapport (donnée de diagnostic à part
 * entière, piste « ResolveurCheminFuse »), jamais une exception.
 *
 * Contexte d'exécution : suspendante — exécutions de sous-processus git
 * et lectures système (`/proc`), confinées au dispatcher IO injecté
 * (règle 5 : I/O hors du thread principal).
 */
public class DiagnostiquerGitProjetUseCase
    @Inject
    constructor(
        private val depotProjets: ProjectRepository,
        private val resoudreChemin: ResolveurCheminFuse,
        private val moteurGit: MoteurGit,
        private val repartiteurs: DispatcherProvider,
    ) {
        /**
         * Exécute le diagnostic Git pour le projet le plus récemment ouvert.
         *
         * @return le rapport brut — toujours présent, même sans projet
         * (les sondes globales restent exécutées).
         */
        public suspend operator fun invoke(): RapportDiagnosticGit =
            withContext(repartiteurs.io) {
                val projets = depotProjets.observeProjects().first()
                val projet = projets.maxByOrNull { it.lastOpenedAtMillis ?: ABSENT }
                val cheminFuse = projet?.let { resoudreChemin(it.location.documentUri) }
                moteurGit.diagnostiquer(
                    nomProjet = projet?.name,
                    cheminFuse = cheminFuse,
                )
            }

        private companion object {
            /** Horodatage conventionnel d'un projet jamais ouvert (jamais confondu avec un vrai, toujours positif). */
            private const val ABSENT = -1L
        }
    }
