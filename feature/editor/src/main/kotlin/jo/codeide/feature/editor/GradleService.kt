package jo.codeide.feature.editor

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.AccumulateurLatence
import jo.codeide.core.domain.AppLogger
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
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
 * Événement de la ZONE TEXTE de la console (v0.42.0, phase 1 du roadmap —
 * performance console) : les lignes stdout/stderr brutes de Gradle quittent
 * [EtatGradle.lignes] (émis par LIGNE d'état, DiffUtil O(N) par ligne — un
 * build de 725 ms mettait 2 minutes à s'afficher) pour un flux DÉDIÉ que la
 * vue applique par `append()` direct, O(1) par ligne.
 *
 * Le tampon borné ([GradleService.NB_LIGNES_MAX], tête tronquée) EST le
 * cache de rejeu du `SharedFlow` : chaque ligne publiée y est conservée
 * (les plus anciennes tombent de la tête) — une vue qui se (re)abonne
 * rejoue l'historique puis suit le direct, sans instantané séparé ni course
 * entre les deux. La reconstitution est CORRECTE par construction : le
 * rejeu est une fenêtre TÊTE-tronquée, or un `Vider` tombé de la fenêtre
 * emporte avec lui tout ce qui le précédait — une ligne d'avant le dernier
 * `Vider` conservé ne peut donc jamais rester seule en scène.
 *
 * @property Ligne une ligne brute (voir [EvenementConsoleTexte.Ligne]).
 * @property Vider la console texte repart vierge : nouveau build suivi ou
 *           rattachement d'un espace (même cycle de vie que
 *           [EtatGradle.lignes]).
 */
sealed interface EvenementConsoleTexte {
    /**
     * Ligne stdout/stderr brute du build suivi.
     *
     * @property flux provenance du flux technique.
     * @property texte contenu brut de la ligne.
     * @property apaisee `true` pour un avertissement CONNU et bénin (v3 —
     *           correctif C5 : le diagnostic natif du daemon Gradle) rendu
     *           en style informatif au lieu du rouge d'erreur.
     * @property horodatageMs instant d'émission côté orchestrateur
     *           (`BuildOutput.timestampMs`, v0.43.0 — mesure de latence : la
     *           vue peut comparer à l'instant du rendu pour situer un
     *           éventuel goulot d'affichage).
     */
    data class Ligne(
        val flux: FluxSortieBuild,
        val texte: String,
        val apaisee: Boolean = false,
        val horodatageMs: Long = 0,
    ) : EvenementConsoleTexte

    /** La zone texte repart vierge (nouveau build, nouvel espace). */
    data object Vider : EvenementConsoleTexte
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
 * DÉTAILS de progression — octets reçus, élément courant, compteur n/N ;
 * v6 — prompt de suivi §2 : suppression du champ `sautee`, une phase qui
 * n'a pas lieu n'est plus émise du tout par le serveur).
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
 * Statut d'affichage d'une étape de sync (v4, §3.2 ; v6 — prompt de suivi
 * §2 : suppression du concept « sautée / En cache ») : l'arbre de la
 * console marque ✓ (terminée), anneau tournant (en cours), ○ (en attente) —
 * l'état reste pur, les symboles appartiennent au rendu. Une étape qui n'a
 * pas lieu n'est plus affichée du tout (plus de `SAUTEE`).
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
 * Téléchargements du build SUIVI (v0.45.1 — affichage immédiat, parité
 * Android Studio) : état CONFLATÉ de la progression des artefacts — le
 * compteur n et le volume cumulé avancent au fil des événements, la rangée
 * de la vue Build se met à jour EN PLACE (barre + « n Mo · élément »),
 * comme la barre de progression de la fenêtre Build d'Android Studio.
 * Disparaît au terme du build (la synthèse prend la place).
 *
 * @property octetsRecus cumul des octets des artefacts TERMINÉS.
 * @property compteur nombre d'artefacts terminés (n).
 * @property element dernier artefact reçu (dernier segment d'URI).
 */
data class EtatTelechargementBuild(
    val octetsRecus: Long = 0,
    val compteur: Int? = null,
    val element: String? = null,
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
 * @property tachesActionnablesBuild total des tâches actionnables extrait
 *           de la synthèse de fin de Gradle (v0.39.1, correctif n°4 —
 *           « N actionable tasks ») ; `null` quand la synthèse n'a pas
 *           été observée.
 * @property tachesExecuteesBuild tâches réellement exécutées (v0.39.1).
 * @property tachesAJourBuild tâches à jour (incrémental, v0.39.1).
 * @property lignes fenêtre TYPIÉE du tooling — tâches (v3) et étapes de sync
 *           uniquement, balisées chacune de leur canal, mises à jour EN
 *           PLACE (peu de rangées, DiffUtil O(1) par mise à jour). Les
 *           sorties stdout/stderr brutes vivent sur [lignesBrutes] depuis
 *           la v0.42.0 (phase 1 du roadmap : plus d'émission d'état par
 *           ligne — la cause du O(N²) historique).
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
    val tachesActionnablesBuild: Int? = null,
    val tachesExecuteesBuild: Int? = null,
    val tachesAJourBuild: Int? = null,
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
    /** Stats classpath par module (v0.40.1, prompt de suivi §4) —
     *  remplies à la fin d'une sync réussie, restituées au retour du
     *  projet si l'empreinte n'a pas changé. `null` si non résolu. */
    val statsClasspath: List<jo.codeide.core.domain.ModuleClasspath>? = null,
    /** Téléchargements du build suivi (v0.45.1) — conflation légitime
     *  d'un état de progression ; `null` hors build en cours. */
    val telechargementsBuild: EtatTelechargementBuild? = null,
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

    /** Étape d'AFFICHAGE courante (v5 — le plan de l'aperçu fusionne
     *  « Dépendances et modèle IDE » : le compteur de l'en-tête suit le
     *  plan AFFICHÉ, pas les phases du câble — interne, concept UI de la
     *  feature). */
    internal val etapeConsoleCourante: EtapeConsoleSync?
        get() = etapeCourante?.let { EtapeConsoleSync.dePhase(it.etape) }

    /** Position de l'étape courante dans le plan d'affichage (1-based, v5). */
    val numeroEtape: Int
        get() = etapeConsoleCourante?.let { EtapeConsoleSync.entries.indexOf(it) + 1 } ?: 0

    /** Total du plan d'affichage (les 7 étapes de l'aperçu, v5). */
    val totalEtapes: Int
        get() = EtapeConsoleSync.entries.size
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
 * **v0.42.0 (phase 1 du roadmap — performance console)** : les lignes
 * stdout/stderr brutes ne traversent PLUS l'état — chaque émission d'état
 * déclenchait la reconstruction complète des rangées et un DiffUtil O(N)
 * par ligne (O(N²) cumulé : un build de 725 ms mettait 2 minutes à
 * s'afficher). Elles vivent désormais sur [lignesBrutes], un flux dédié
 * au tampon borné : la vue les applique par `append()` direct, O(1) par
 * ligne. [etat] ne s'émet plus qu'aux transitions structurées (tâches,
 * étapes, statuts) — peu de rangées, DiffUtil O(1) par mise à jour.
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
        private val journal: AppLogger,
    ) {
        private val etatInterne = MutableStateFlow(EtatGradle())

        /** Identités séquentielles des lignes de console (v3). */
        private val sequenceLignes = AtomicLong()

        /**
         * Latence de PUBLICATION des lignes, par build (v0.43.0 — mesure
         * de la console lente) : écart entre l'émission côté orchestrateur
         * (`LigneSortieBuild.horodatageMs`) et l’arrivée dans la zone
         * texte. Miroir CLIENT du résumé de TRANSPORT de GradleApiImpl —
         * la DIFFÉRENCE des maxima localise la moitié cliente. Nettoyé au
         * terme du build suivi.
         */
        private val latencesPublication = ConcurrentHashMap<String, AccumulateurLatence>()

        /** État observable du tooling. */
        val etat: StateFlow<EtatGradle> = etatInterne.asStateFlow()

        /**
         * Zone TEXTE de la console (v0.42.0, phase 1) : les lignes
         * stdout/stderr brutes du build suivi et les vidages, sur un flux
         * DÉDIÉ — jamais dans l'état (plus d'émission par ligne, la cause
         * du O(N²) historique).
         *
         * Le cache de rejeu EST le tampon borné : `replay = NB_LIGNES_MAX`
         * conserve les derniers événements (tête tronquée), un (ré)abonné
         * rejoue l'historique puis suit le direct — la reconstitution est
         * correcte par construction (cf. [EvenementConsoleTexte]).
         * `DROP_OLDEST` : un abonné lent de plus de la capacité ne bloque
         * JAMAIS la pompe (le thread de vidange du client doit pouvoir
         * vider les canaux sous pression — ADR 0057) ; les lignes tombées
         * de sa fenêtre seront restituées au prochain réabonnement (le
         * rejeu est la vérité).
         */
        private val zoneTexteInterne =
            MutableSharedFlow<EvenementConsoleTexte>(
                replay = NB_LIGNES_MAX,
                extraBufferCapacity = CAPACITE_TAMPON_DIRECT,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** Zone texte de la console, observable (rejeu = historique borné). */
        val lignesBrutes: SharedFlow<EvenementConsoleTexte> = zoneTexteInterne

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
            viderZoneTexte()
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

        /**
         * Publie les statistiques classpath par module (v0.40.1, prompt de
         * suivi §4) — remplies à la fin d'une sync réussie via
         * `PreparerClasspathLspUseCase`. Le pied de conclusion de la sync
         * les restitue en récapitulatif (total modules / jars / sources /
         * avertissements).
         */
        fun publierStatsClasspath(modules: List<jo.codeide.core.domain.ModuleClasspath>) {
            maj { it.copy(statsClasspath = modules) }
        }

        /** Réinitialise la console et publie le build suivi — les tâches
         *  demandées voyagent avec (libellé d'activité de l'en-tête).
         *  v0.42.0 : la zone TEXTE se vide AUSSI (même cycle de vie que
         *  `lignes`) — un nouveau build ne montre pas la sortie du
         *  précédent. */
        fun suivreBuild(
            buildId: String,
            taches: List<String> = emptyList(),
        ) {
            // v0.43.0 : l'espace ne construit qu'UN build à la fois — un
            // accumulateur de latence restant est celui d'un build orphelin
            // (état terminal jamais reçu, session perdue) : purgé ici.
            latencesPublication.clear()
            maj {
                it.copy(
                    buildId = buildId,
                    taches = taches,
                    statutBuild = StatutBuild.EN_COURS,
                    debutBuildMs = horloge.nowMillis(),
                    dureeBuildMs = null,
                    messageEchecBuild = null,
                    // v0.39.1 : reset de la synthèse précédente (un
                    // nouveau build ne montre PAS les « N actionable
                    // tasks » du précédent pendant qu'il tourne).
                    tachesActionnablesBuild = null,
                    tachesExecuteesBuild = null,
                    tachesAJourBuild = null,
                    lignes = emptyList(),
                    // v0.45.1 : les téléchargements repartent vierges
                    // (rangée de progression de la vue Build).
                    telechargementsBuild = null,
                )
            }
            viderZoneTexte()
        }

        /** Publie l'état du build suivi (les autres builds sont ignorés). */
        fun publierEtatBuild(etat: EtatBuild) {
            // v0.43.0 (mesure console lente) : au terme du build SUIVI, le
            // résumé de latence de PUBLICATION (émission serveur → zone
            // texte) rejoint le journal — à lire avec le résumé de TRANSPORT
            // de GradleApiImpl : différence des maxima = moitié cliente
            // (canaux, pompe, zone texte), maximum de transport = moitié
            // serveur+socket (file de l'EventBus, contre-pression).
            if (etat.statut != StatutBuild.EN_COURS && etat.buildId == etatInterne.value.buildId) {
                latencesPublication.remove(etat.buildId)?.let { latence ->
                    journal.i(TAG) {
                        "zone texte du build ${etat.buildId} alimentée : ${latence.description()} " +
                            "(émission orchestrateur → publication console)"
                    }
                }
            }
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
                        // v0.39.1 (correctif n°4) : la synthèse
                        // « N actionable tasks: M executed[, K up-to-date] »
                        // extraite côté serveur voyage à l'état — la
                        // console la restituera sous le verdict.
                        tachesActionnablesBuild = etat.tachesActionnables,
                        tachesExecuteesBuild = etat.tachesExecutees,
                        tachesAJourBuild = etat.tachesAJour,
                        // v0.45.1 : la rangée des téléchargements disparaît
                        // au terme du build — la synthèse prend la place,
                        // comme la barre de progression d'Android Studio
                        // s'efface quand la fenêtre Build conclut.
                        telechargementsBuild = null,
                    )
                }
            }
        }

        /** Publie une ligne du build suivi SUR LE FLUX DÉDIÉ de la zone
         *  texte (v0.42.0, phase 1 : fenêtre bornée = rejeu, PLUS D'ÉMISSION
         *  D'ÉTAT — la vue applique par `append()` direct, O(1) par ligne).
         *  Les lignes d'un autre build et celles après annulation restent
         *  ignorées (garde historique). Un avertissement CONNU et bénin (C5 :
         *  le diagnostic natif du daemon Gradle, documenté dans
         *  docs/TOOLING.md) voyage apaisé : le rendu l'affiche en style
         *  informatif, pas en rouge d'erreur. */
        fun ajouterLigne(ligne: LigneSortieBuild) {
            val courant = etatInterne.value
            if (ligne.buildId != courant.buildId || courant.statutBuild == StatutBuild.ANNULE) return
            // v0.43.0 (mesure console lente) : écart émission serveur →
            // publication zone texte (transport + moitié cliente inclus).
            latencesPublication
                .computeIfAbsent(ligne.buildId) { AccumulateurLatence() }
                .enregistrer(horloge.nowMillis() - ligne.horodatageMs)
            zoneTexteInterne.tryEmit(
                EvenementConsoleTexte.Ligne(
                    flux = ligne.flux,
                    texte = ligne.ligne,
                    apaisee = ligne.apaisee(),
                    horodatageMs = ligne.horodatageMs,
                ),
            )
        }

        /**
         * Publie un téléchargement du build suivi (v0.45.1 — affichage
         * immédiat, parité Android Studio) : la progression des artefacts
         * du build devient une rangée EN PLACE de la vue Build (barre +
         * volume cumulé + artefact courant) — le canal téléchargements du
         * client avait bien un consommateur nulle part jusqu'ici : ces
         * événements existaient, la console les ignorait. Les lignes d'un
         * autre build sont ignorées (garde historique) et l'état vit
         * SEULEMENT pendant le build (conflation légitime d'un état de
         * progression, effacé au statut terminal).
         */
        fun ajouterTelechargement(telechargement: jo.codeide.core.domain.TelechargementBuild) {
            maj { courant ->
                if (telechargement.buildId != courant.buildId ||
                    courant.statutBuild != StatutBuild.EN_COURS
                ) {
                    courant
                } else {
                    courant.copy(
                        telechargementsBuild =
                            EtatTelechargementBuild(
                                octetsRecus =
                                    (courant.telechargementsBuild?.octetsRecus ?: 0) +
                                        telechargement.octetsRecus,
                                compteur = telechargement.compteur,
                                element = telechargement.element,
                            ),
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

        /** Ajoute une ligne en fin de fenêtre bornée (tête tronquée) —
         *  v0.42.0 : seules les lignes TYPIÉES (tâches, étapes) y vivent,
         *  peu nombreuses par construction. */
        private fun ajouterALaFenetre(
            ligne: LigneConsole,
            courant: EtatGradle,
        ): EtatGradle = courant.copy(lignes = (courant.lignes + ligne).takeLast(NB_LIGNES_MAX))

        /** Publie le VIDAGE de la zone texte sur le flux dédié (v0.42.0) —
         *  même cycle de vie que la fenêtre `lignes` : nouveau build suivi
         *  ou rattachement d'un espace. L'événement survit dans le rejeu :
         *  une vue qui se ré-abonne plus tard ne reconstruit QUE ce qui
         *  suit le dernier vidage. */
        private fun viderZoneTexte() {
            zoneTexteInterne.tryEmit(EvenementConsoleTexte.Vider)
        }

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
            /** Fenêtre de sortie affichée (tête tronquée au-delà) — v0.42.0 :
             *  taille du REJEU de la zone texte (une place est prise par
             *  chaque `Vider`, la fenêtre reste de l'ordre de la constante). */
            const val NB_LIGNES_MAX = 2_000

            /** Tampon DIRECT au-delà du rejeu (v0.42.0) : absorbateur de
             *  rafales pour un abonné vivant — la vue lotit ses ajouts par
             *  trame, un build bavard ne la rattrape jamais. */
            const val CAPACITE_TAMPON_DIRECT = 2_048

            /** Début de l'avertissement bénin du daemon Gradle (C5) — la
             *  forme longue continue (« ...to match the client because:
             *  There is no native integration... »), le préfixe suffit. */
            const val AVERTISSEMENT_DAEMON_BENIN = "Unable to set daemon's environment variables"

            /** Étiquette des résumés de latence (mesure console, v0.43.0). */
            const val TAG = "ConsoleLatence"
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
