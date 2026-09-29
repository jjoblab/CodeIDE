package jo.codeide.feature.editor

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtapeSyncTooling
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.EtatTacheBuild
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canal d'une information tooling (v0.32.5, ADR 0056 décision 5 ; étape 32 :
 * canal Taches) : chaque information affichée dans l'en-tête du panneau ou
 * dans la console vit sur SON canal — Sync, Build et Taches ne se mélangent
 * jamais, l'icône et la couleur du canal identifient la provenance au
 * premier regard (patron CodeAssist : la console y sépare
 * problèmes/log/étapes).
 *
 * Le Journal et les Problèmes sont déjà des onglets distincts du panneau
 * (v0.32.4) : ce sont leurs propres canaux, hors de cette énumération.
 */
enum class CanalTooling {
    /** Synchronisation Gradle du projet. */
    SYNC,

    /** Build / exécution de tâches Gradle. */
    BUILD,

    /** Listage des tâches du projet (sélecteur « Exécuter »). */
    TACHES,
    ;

    /** Libellé court du canal (en-tête du panneau, étiquette de ligne). */
    val libelle: Int
        get() =
            when (this) {
                SYNC -> R.string.editor_tooling_canal_sync
                BUILD -> R.string.editor_tooling_canal_build
                TACHES -> R.string.editor_tooling_canal_taches
            }

    /** Couleur signature du canal (en-tête et étiquettes de console). */
    val couleur: Int
        get() =
            when (this) {
                SYNC -> jo.codeide.core.ui.R.color.codeide_canal_sync
                BUILD -> jo.codeide.core.ui.R.color.codeide_canal_build
                TACHES -> jo.codeide.core.ui.R.color.codeide_canal_taches
            }

    /** Icône du canal (en-tête du panneau et statut de la console). */
    val icone: Int
        get() =
            when (this) {
                SYNC -> R.drawable.ic_synchroniser
                BUILD -> R.drawable.ic_executer
                TACHES -> R.drawable.ic_liste_taches
            }
}

/**
 * Une ligne de la console du panneau Sortie (G5 ; v0.32.5 : ligne
 * CANALISÉE ; v3 : ligne TYPIÉE) — la provenance (canal) identifie
 * l'information au premier regard, le genre distingue la sortie brute
 * (stdout/stderr) de la ligne de TÂCHE (mise à jour en place à sa fin,
 * comme la vue Build d'Android Studio) et de l'ÉTAPE de sync (fin de la
 * boîte noire « en cours / terminée »).
 *
 * Les libellés des tâches et étapes sont LOCALISÉS par le rendu : l'état
 * reste pur, aucune chaîne n'y est construite.
 */
sealed interface LigneConsole {
    /** Identité stable de la ligne (v3) : la mise à jour EN PLACE conserve
     *  l'id de la ligne remplacée — le DiffUtil rebinde la rangée, sans
     *  scintillement ni défilement. */
    val id: Long

    /** Canal de provenance (étiquette de la ligne). */
    val canal: CanalTooling

    /**
     * Sortie brute du build (stdout/stderr).
     *
     * @property flux provenance du flux technique.
     * @property texte contenu brut de la ligne.
     * @property apaisee `true` pour un avertissement CONNU et bénin (v3 —
     *           correctif C5 : le diagnostic natif du daemon Gradle) rendu
     *           en style informatif au lieu du rouge d'erreur.
     */
    data class Sortie(
        override val id: Long,
        override val canal: CanalTooling,
        val flux: FluxSortieBuild,
        val texte: String,
        val apaisee: Boolean = false,
    ) : LigneConsole

    /**
     * Une tâche du build (v3) : affichée à son départ, mise à jour EN PLACE
     * à sa fin (statut + durée) — une ligne par tâche, comme la console
     * d'Android Studio.
     */
    data class Tache(
        override val id: Long,
        override val canal: CanalTooling,
        val etat: EtatTacheAffichee,
    ) : LigneConsole

    /** Une étape de synchronisation (v3) : annoncée à son départ, conclue
     *  en place avec sa durée. */
    data class Etape(
        override val id: Long,
        override val canal: CanalTooling,
        val etat: EtapeSyncAffichee,
    ) : LigneConsole
}

/**
 * Tâche affichée dans la console (v3) — l'état domaine sans l'identifiant
 * de build (la console est déjà rattachée au build suivi).
 *
 * @property chemin chemin Gradle complet (ex. `:app:compileDebugKotlin`).
 * @property statut statut courant.
 * @property dureeMs durée MESURÉE par l'orchestrateur à la fin, sinon `null`.
 */
data class EtatTacheAffichee(
    val chemin: String,
    val statut: StatutTache,
    val dureeMs: Long? = null,
)

/**
 * Étape de sync affichée dans la console (v3 ; v4 : phases réelles +
 * DÉTAILS de progression — octets reçus, élément courant, compteur n/N).
 *
 * @property etape phase annoncée.
 * @property terminee `true` à la fin (durée à la clé).
 * @property dureeMs durée de la phase à sa fin.
 * @property octetsRecus octets reçus cumulés (téléchargements) — 0 si sans objet.
 * @property octetsTotal octets totaux si connus — `null` sinon.
 * @property element élément courant (artefact, projet) SANS donnée personnelle.
 * @property compteur éléments terminés de la phase (n) — `null` si sans objet.
 * @property total éléments totaux de la phase (N) si connu.
 */
data class EtapeSyncAffichee(
    val etape: EtapeSync,
    val terminee: Boolean = false,
    val dureeMs: Long = 0,
    val octetsRecus: Long = 0,
    val octetsTotal: Long? = null,
    val element: String? = null,
    val compteur: Int? = null,
    val total: Int? = null,
)

/**
 * Statut d'affichage d'une étape de sync (v4, §3.2) : l'arbre de la
 * console marque ✓ (terminée), spinner (en cours), ○ (en attente) —
 * l'état reste pur, les symboles appartiennent au rendu.
 */
enum class StatutEtapeSync {
    EN_ATTENTE,
    EN_COURS,
    TERMINEE,
    ECHOUEE,
}

/**
 * Problèmes d'un même fichier (G5) — l'onglet Problèmes groupe les
 * diagnostics par fichier, chaque groupe est replié sur son en-tête
 * (fichier + compte), l'appui sur un problème saute à sa ligne.
 *
 * @property fichier chemin absolu porté par le diagnostic.
 * @property nomFichier dernier segment (libellé du groupe).
 * @property problems diagnostics du fichier, triés par ligne.
 */
data class GroupeProblemes(
    val fichier: String,
    val nomFichier: String,
    val problems: List<DiagnosticBuild>,
)

/**
 * État observable du tooling Gradle pour l'espace de travail (G5 ;
 * v0.32.5 : canaux, tâches et instants de départ — ADR 0056 décision 5 ;
 * étape 32 : canal Taches, ADR 0057).
 *
 * @property connexion état du lien avec l'orchestrateur (daemon G4).
 * @property buildId identifiant du build suivi (le dernier lancé).
 * @property taches tâches demandées au build suivi (libellé d'activité).
 * @property statutBuild statut courant du build suivi.
 * @property debutBuildMs instant de départ du build suivi (chrono de
 *           l'en-tête — millisecondes de l'horloge injectée).
 * @property dureeBuildMs durée du build terminé.
 * @property messageEchecBuild message d'échec du build (si échoué).
 * @property lignes fenêtre de sortie TYPIÉE du tooling (bornée,
 *           [NB_LIGNES_MAX]) — sorties, tâches (v3) et étapes de sync,
 *           balisées chacune de leur canal.
 * @property problemesTotal nombre total de diagnostics (badge).
 * @property synchronisationEnCours une synchronisation est en vol.
 * @property debutSyncMs instant de départ de la synchronisation (chrono).
 * @property synchronisationReussie dernier résultat de synchronisation.
 * @property messageEchecSync message de l'échec de synchronisation.
 * @property tachesEnCours le listage des tâches est en vol (étape 32).
 * @property debutTachesMs instant de départ du listage (chrono).
 */
data class EtatGradle(
    val connexion: EtatConnexion = EtatConnexion.DECONNECTEE,
    val buildId: String? = null,
    val taches: List<String> = emptyList(),
    val statutBuild: StatutBuild? = null,
    val debutBuildMs: Long? = null,
    val dureeBuildMs: Long? = null,
    val messageEchecBuild: String? = null,
    val lignes: List<LigneConsole> = emptyList(),
    val groupesProblemes: List<GroupeProblemes> = emptyList(),
    val synchronisationEnCours: Boolean = false,
    val debutSyncMs: Long? = null,
    val synchronisationReussie: ResultatSynchronisation? = null,
    val messageEchecSync: String? = null,
    val tachesEnCours: Boolean = false,
    val debutTachesMs: Long? = null,
    /** Tâches du projet connues sans aller-retour (v4, §3.2 — remplies à
     *  la fin d'une sync : le sélecteur s'ouvre sans latence). */
    val tachesDisponibles: List<InfoTache>? = null,
) {
    /** Nombre total de diagnostics (badge de l'onglet Problèmes). */
    val problemesTotal: Int
        get() = groupesProblemes.sumOf { groupe -> groupe.problems.size }

    /** Canal de l'activité COURANTE (v0.32.5 ; étape 32 : Taches en
     *  dernier) : la synchronisation prioritaire (elle se produit
     *  d'abord, l'IDE ensuite), puis le build, puis le listage —
     *  `null` quand rien ne tourne. */
    val canalActif: CanalTooling?
        get() =
            when {
                synchronisationEnCours -> CanalTooling.SYNC
                statutBuild == StatutBuild.EN_COURS -> CanalTooling.BUILD
                tachesEnCours -> CanalTooling.TACHES
                else -> null
            }

    /** Une activité tooling tourne-t-elle (progression indéterminée). */
    val activiteEnCours: Boolean
        get() = canalActif != null

    /** Dernière activité terminée visible (libellé de résultat dans
     *  l'en-tête) : le canal du dernier résultat connu, Sync ou Build —
     *  le listage des tâches se conclut par son sélecteur, pas par une
     *  ligne de résultat (le canal Taches est un indicateur de vol). */
    val canalDernierResultat: CanalTooling?
        get() =
            when {
                synchronisationReussie != null || messageEchecSync != null -> CanalTooling.SYNC
                statutBuild != null -> CanalTooling.BUILD
                else -> null
            }

    /**
     * Étapes de sync ordonnées, dérivées des lignes (v4, §3.2) : l'arbre
     * de la console EST l'état — la liste porte chaque phase annoncée
     * avec ses détails de progression, sans duplication de source de
     * vérité.
     */
    val etapesAffichees: List<EtapeSyncAffichee>
        get() = lignes.filterIsInstance<LigneConsole.Etape>().map { it.etat }

    /** Étape COURANTE de sync (v4 : compteur de l'en-tête « étape n/N »). */
    val etapeCourante: EtapeSyncAffichee?
        get() = etapesAffichees.lastOrNull { !it.terminee } ?: etapesAffichees.lastOrNull()

    /** Position de l'étape courante dans le déroulé fixe (1-based, v4). */
    val numeroEtape: Int
        get() = etapeCourante?.etape?.let { EtapeSync.entries.indexOf(it) + 1 } ?: 0

    /** Total du déroulé (les 8 phases réelles v4). */
    val totalEtapes: Int
        get() = EtapeSync.entries.size
}

/**
 * Détenteur d'état du tooling pour l'espace de travail (G5 ; étape 32,
 * ADR 0057 : process-wide). Classe pure (même précédent que
 * `FiltrageProjets` de l'accueil) : le ViewModel y publie, l'activité
 * observe, aucun couplage aux sources de données.
 *
 * **Singleton depuis l'étape 32** : la mort de l'espace de travail ne
 * tue plus l'état tooling — un build lancé puis quitté reste suivi par
 * le service de notification, et l'espace qui ré-ouvre s'y rattache
 * ([attacher]). L'horloge est INJECTÉE ([horloge], millisecondes) : les
 * chronos de l'en-tête se testent sans cadre Android.
 *
 * **Service Android piloté par transitions** : quand une activité
 * démarre (canal actif `null` → non nul), le [demarreur] lancement le
 * service foreground — c'est lui qui tient la notification honnête
 * (même contrat que le terminal, prompt Terminal-1 §4.2) en observant
 * cet état ; l'arrêt lui appartient (plus d'activité → stopSelf).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — les pubs
 * sont le contrat de la vue (connexion, sync, taches, build, lignes,
 * diagnostics, attache), chacune testée séparément ; même justification
 * que `GradleApiImpl` côté client.
 *
 * La fenêtre de sortie est BORNÉE ([NB_LIGNES_MAX], tête tronquée) : la
 * sortie complète vit dans le canal du client (rejouable après fin, ADR
 * 0041), la console n'est qu'une vue — un build bavard ne doit pas
 * manger la mémoire de l'appareil.
 *
 * @param horloge lecture de l'instant courant (ms) — epochs ou monotone,
 *        seules les DIFFÉRENCES comptent.
 * @param demarreur lance le service Android de notification au premier
 *        départ d'activité (port interne, implémentation Android).
 */
@Suppress("TooManyFunctions")
@Singleton
class GradleService
    @Inject
    constructor(
        private val horloge: TimeProvider,
        private val demarreur: DemarreurServiceTooling,
    ) {
        private val etatInterne = MutableStateFlow(EtatGradle())

        /** Identités séquentielles des lignes de console (v3). */
        private val sequenceLignes = AtomicLong()

        /** État observable du tooling. */
        val etat: StateFlow<EtatGradle> = etatInterne.asStateFlow()

        /**
         * Rattache un espace de travail à l'état process-wide (étape 32) :
         * la console et les problèmes repartent vierges (ce sont des vues
         * de CET espace), les activités en vol (build, sync, listage) et
         * la connexion sont conservées — le tooling ne meurt pas avec un
         * écran.
         */
        fun attacher() {
            etatInterne.update { courant ->
                courant.copy(lignes = emptyList(), groupesProblemes = emptyList())
            }
        }

        /** Publie l'état de connexion (daemon G4). */
        fun publierConnexion(connexion: EtatConnexion) {
            maj { it.copy(connexion = connexion) }
        }

        /** Marque le début d'une synchronisation : instant de départ du
         *  chrono (le libellé d'activité est LOCALISÉ par le rendu — l'état
         *  reste pur, aucune chaîne codée en dur). Idempotent en vol : le
         *  marquage local (geste) et l'annonce du serveur (SyncStarted)
         *  ne remettent pas le chrono à zéro. */
        fun marquerSyncEnCours() {
            maj { courant ->
                if (courant.synchronisationEnCours) {
                    courant
                } else {
                    courant.copy(
                        synchronisationEnCours = true,
                        debutSyncMs = horloge.nowMillis(),
                        messageEchecSync = null,
                        // v4 (§3.2) : une nouvelle sync invalide les tâches
                        // connues — elles seront remplies à la fin.
                        tachesDisponibles = null,
                    )
                }
            }
        }

        /** Publie le résultat d'une synchronisation — l'état porte le canal
         *  Sync (résultat, message) ; le rendu localise et balise. */
        fun publierResultatSync(resultat: AppResult<ResultatSynchronisation>) {
            when (resultat) {
                is AppResult.Success -> {
                    maj {
                        it.copy(
                            synchronisationEnCours = false,
                            synchronisationReussie = resultat.value,
                            messageEchecSync = resultat.value.messageEchec,
                        )
                    }
                }

                is AppResult.Failure -> {
                    maj { it.copy(synchronisationEnCours = false, messageEchecSync = messageDEchec(resultat)) }
                }
            }
        }

        /** Marque le début du listage des tâches (canal Taches, étape 32) :
         *  indicateur de vol du sélecteur « Exécuter » — son résultat est
         *  le sélecteur lui-même, pas une ligne de console. Idempotent. */
        fun marquerTachesEnCours() {
            maj { courant ->
                if (courant.tachesEnCours) {
                    courant
                } else {
                    courant.copy(tachesEnCours = true, debutTachesMs = horloge.nowMillis())
                }
            }
        }

        /** Conclut le listage des tâches (le sélecteur prend le relais). */
        fun tachesTerminees() {
            maj { it.copy(tachesEnCours = false) }
        }

        /**
         * Publie les tâches du projet connues SANS aller-retour (v4, §3.2) :
         * remplies à la fin d'une sync utile (le cache serveur rend le
         * listage instantané) — le sélecteur s'ouvre sans latence et le
         * bouton Tâches s'active sur un FAIT, pas sur une présomption.
         */
        fun publierTachesDisponibles(taches: List<InfoTache>) {
            maj { it.copy(tachesDisponibles = taches) }
        }

        /** Réinitialise la console et publie le build suivi — les tâches
         *  demandées voyagent avec (libellé d'activité de l'en-tête). */
        fun suivreBuild(
            buildId: String,
            taches: List<String> = emptyList(),
        ) {
            maj {
                it.copy(
                    buildId = buildId,
                    taches = taches,
                    statutBuild = StatutBuild.EN_COURS,
                    debutBuildMs = horloge.nowMillis(),
                    dureeBuildMs = null,
                    messageEchecBuild = null,
                    lignes = emptyList(),
                )
            }
        }

        /** Publie l'état du build suivi (les autres builds sont ignorés). */
        fun publierEtatBuild(etat: EtatBuild) {
            maj { courant ->
                if (etat.buildId !=
                    courant.buildId
                ) {
                    courant
                } else {
                    courant.copy(
                        statutBuild = etat.statut,
                        dureeBuildMs = etat.dureeMs,
                        messageEchecBuild = etat.messageEchec,
                    )
                }
            }
        }

        /** Ajoute une ligne du build suivi (fenêtre bornée, CANAL Build) —
         *  un avertissement CONNU et bénin (C5 : le diagnostic natif du
         *  daemon Gradle, documenté dans docs/TOOLING.md) voyage apaisé :
         *  le rendu l'affiche en style informatif, pas en rouge d'erreur. */
        fun ajouterLigne(ligne: LigneSortieBuild) {
            maj { courant ->
                if (ligne.buildId != courant.buildId || courant.statutBuild == StatutBuild.ANNULE) {
                    courant
                } else {
                    ajouterALaFenetre(
                        LigneConsole.Sortie(
                            id = nouvelleIdentiteLigne(),
                            canal = CanalTooling.BUILD,
                            flux = ligne.flux,
                            texte = ligne.ligne,
                            apaisee = ligne.apaisee(),
                        ),
                        courant,
                    )
                }
            }
        }

        /**
         * Publie une tâche du build suivi (v3 — affichage à la console
         * d'Android Studio) : au départ une ligne apparaît, à la fin elle
         * est REMPLACÉE EN PLACE (statut + durée, même identité) — une ligne
         * par tâche, jamais de défilé bavard.
         */
        fun ajouterTache(tache: EtatTacheBuild) {
            maj { courant ->
                if (tache.buildId != courant.buildId || courant.statutBuild == StatutBuild.ANNULE) {
                    courant
                } else {
                    majEnPlace(
                        courant = courant,
                        correspond = { ligne ->
                            ligne is LigneConsole.Tache &&
                                ligne.etat.chemin == tache.chemin &&
                                ligne.etat.statut == StatutTache.EN_COURS
                        },
                        remplacement = { identite ->
                            LigneConsole.Tache(
                                id = identite,
                                canal = CanalTooling.BUILD,
                                etat = EtatTacheAffichee(tache.chemin, tache.statut, tache.dureeMs),
                            )
                        },
                    )
                }
            }
        }

        /**
         * Publie une étape de synchronisation (v3 — fin de la boîte noire)
         * : au départ une ligne apparaît, à la fin elle est conclue EN
         * PLACE avec sa durée — canal Sync.
         */
        fun ajouterEtapeSync(etape: EtapeSyncTooling) {
            maj { courant ->
                majEnPlace(
                    courant = courant,
                    correspond = { ligne ->
                        ligne is LigneConsole.Etape &&
                            ligne.etat.etape == etape.etape &&
                            !ligne.etat.terminee
                    },
                    remplacement = { identite ->
                        LigneConsole.Etape(
                            id = identite,
                            canal = CanalTooling.SYNC,
                            etat =
                                EtapeSyncAffichee(
                                    etape = etape.etape,
                                    terminee = etape.terminee,
                                    dureeMs = etape.dureeMs,
                                    octetsRecus = etape.octetsRecus,
                                    octetsTotal = etape.octetsTotal,
                                    element = etape.element,
                                    compteur = etape.compteur,
                                    total = etape.total,
                                ),
                        )
                    },
                )
            }
        }

        /** Ajoute une ligne en fin de fenêtre bornée (tête tronquée). */
        private fun ajouterALaFenetre(
            ligne: LigneConsole,
            courant: EtatGradle,
        ): EtatGradle = courant.copy(lignes = (courant.lignes + ligne).takeLast(NB_LIGNES_MAX))

        /**
         * Remplace la DERNIÈRE ligne qui correspond (en conservant SON
         * identité — rebind en place), ou l'ajoute si aucune ne correspond
         * (v3 : la fenêtre ne défile qu'à l'apparition d'une NOUVELLE
         * tâche/étape, comme la vue Build d'Android Studio).
         */
        private fun majEnPlace(
            courant: EtatGradle,
            correspond: (LigneConsole) -> Boolean,
            remplacement: (Long) -> LigneConsole,
        ): EtatGradle {
            val index = courant.lignes.indexOfLast(correspond)
            return if (index >= 0) {
                val identite = courant.lignes[index].id
                courant.copy(
                    lignes = courant.lignes.toMutableList().also { it[index] = remplacement(identite) },
                )
            } else {
                ajouterALaFenetre(remplacement(nouvelleIdentiteLigne()), courant)
            }
        }

        /** Identité séquentielle d'une nouvelle ligne de console (v3). */
        private fun nouvelleIdentiteLigne(): Long = sequenceLignes.incrementAndGet()

        /**
         * Avertissement Gradle CONNU et bénin sur cette ligne stderr (C5) :
         * la bibliothèque `native-platform` n'a pas de binding pour
         * Android/bionic — le build continue avec l'environnement du daemon
         * (déjà complet depuis le premier lancement, voir docs/TOOLING.md).
         */
        private fun LigneSortieBuild.apaisee(): Boolean =
            flux == FluxSortieBuild.STDERR && ligne.startsWith(AVERTISSEMENT_DAEMON_BENIN)

        /** Publie les diagnostics courants, groupés par fichier. */
        fun publierDiagnostics(diagnostics: List<DiagnosticBuild>) {
            val groupes =
                diagnostics
                    .groupBy { diagnostic -> diagnostic.fichier }
                    .map { (fichier, liste) ->
                        GroupeProblemes(
                            fichier = fichier,
                            nomFichier = fichier.substringAfterLast('/'),
                            problems = liste.sortedBy { diagnostic -> diagnostic.ligne },
                        )
                    }.sortedBy { groupe -> groupe.nomFichier }
            maj { it.copy(groupesProblemes = groupes) }
        }

        /**
         * Mutation unique de l'état (étape 32) : le départ d'activité
         * (`null` → canal actif) y est détecté UNE fois — c'est ici et
         * seulement ici que le service Android de notification se lance.
         */
        private fun maj(transformation: (EtatGradle) -> EtatGradle) {
            val avant = etatInterne.value
            val apres = transformation(avant)
            if (apres == avant) return
            etatInterne.value = apres
            if (avant.canalActif == null && apres.canalActif != null) {
                demarreur.demarrer()
            }
        }

        /** Message lisible d'un échec AppResult (repli : raison générique). */
        private fun messageDEchec(echec: AppResult.Failure): String =
            (echec.error as? jo.codeide.core.model.AppError.Tooling)?.message
                ?: echec.error::class.simpleName
                ?: "échec du tooling"

        private companion object {
            /** Fenêtre de sortie affichée (tête tronquée au-delà). */
            const val NB_LIGNES_MAX = 2_000

            /** Début de l'avertissement bénin du daemon Gradle (C5) — la
             *  forme longue continue (« ...to match the client because:
             *  There is no native integration... »), le préfixe suffit. */
            const val AVERTISSEMENT_DAEMON_BENIN = "Unable to set daemon's environment variables"
        }
    }

/**
 * Port de lancement du service Android de notification du tooling
 * (étape 32, ADR 0057) — même contrat que `DemarreurService` du terminal
 * (prompt Terminal-1 §4.2) : le détenteur d'état purge y signale le
 * départ d'une activité, l'implémentation démarre le service foreground
 * qui tient la notification ; l'arrêt appartient au service (plus
 * d'activité observée → stopSelf).
 */
interface DemarreurServiceTooling {
    /** Démarre le service de notification (idempotent côté Android). */
    fun demarrer()
}

/** Implémentation Android : démarre [ToolingService]. */
@Singleton
class DemarreurServiceToolingAndroid
    @Inject
    constructor(
        // Annotation sans `private val` (leçon T1) : évite le warning K2
        // « appliquée au paramètre seulement » — champ dérivé ci-dessous.
        @ApplicationContext contexte: Context,
    ) : DemarreurServiceTooling {
        private val contexteApplication: Context = contexte.applicationContext

        override fun demarrer() {
            contexteApplication.startForegroundService(Intent(contexteApplication, ToolingService::class.java))
        }
    }
