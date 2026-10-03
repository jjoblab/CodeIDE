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
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.LigneSortieSync
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
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
 * Événement de la CONSOLE FLUX BRUT UNIQUE (v0.42.0 : zone texte annexée —
 * performance console ; v0.46.0 — refonte totale, voir ADR 0078 : le flux
 * est devenu l'UNIQUE affichage, la zone structurée en RecyclerView a
 * disparu — Gradle écrit lui-même ses lignes « > Task :app:xxx » et
 * « BUILD SUCCESSFUL in 6s » sur stdout, les dupliquer en rangées était
 * précisément le problème « outputs affichés en deux endroits »).
 *
 * Le tampon borné ([GradleService.NB_LIGNES_MAX], tête tronquée) EST le
 * cache de rejeu du `SharedFlow` : chaque ligne publiée y est conservée
 * (les plus anciennes tombent de la tête) — une vue qui se (re)abonne
 * rejoue l'historique puis suit le direct, sans instantané séparé ni course
 * entre les deux. La reconstitution est CORRECTE PAR CONSTRUCTION : le
 * rejeu est une fenêtre TÊTE-tronquée, or un `Vider` tombé de la fenêtre
 * emporte avec lui tout ce qui le précédait — une ligne d'avant le dernier
 * `Vider` conservé ne peut donc jamais rester seule en scène. Le même
 * raisonnement tient PAR CANAL : les lignes Sync et Build se partagent la
 * fenêtre sans se mélanger, le fragment ne montre que SON canal.
 *
 * @property Ligne une ligne de la console (canal, libellé, style).
 * @property Vider la console du canal repart vierge.
 */
internal sealed interface EvenementConsoleTexte {
    /**
     * Une ligne de la console.
     *
     * @property canal provenance (Sync ou Build) — les DEUX canaux
     *           s'accumulent, le fragment filtre ce qu'il montre.
     * @property libelle contenu localisable (ressource, brut ou composé) —
     *           résolu au rendu, l'événement reste pur.
     * @property style style visuel (couleur) — résolu au rendu.
     * @property horodatageMs instant d'émission côté orchestrateur
     *           (`BuildOutput.timestampMs`, v0.43.0 — mesure de latence : la
     *           vue peut comparer à l'instant du rendu pour situer un
     *           éventuel goulot d'affichage).
     */
    data class Ligne(
        val canal: CanalTooling,
        val libelle: TexteTooling,
        val style: StyleLigne = StyleLigne.SORTIE,
        val horodatageMs: Long = 0,
    ) : EvenementConsoleTexte

    /** La console du canal repart vierge (nouveau build, nouvelle sync,
     *  rattachement d'un espace). */
    data class Vider(
        val canal: CanalTooling,
    ) : EvenementConsoleTexte
}

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
 * @property etapesSync étapes de sync annoncées (v0.46.0 : ne porte plus
 *           de rangée de console — l'EN-TÊTE du panneau y lit son compteur
 *           « étape n/N » et sa progression déterminée, la CONSOLE écrit
 *           une ligne par transition sur [lignesBrutes]).
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
    val etapesSync: List<EtapeSyncAffichee> = emptyList(),
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
     * Étapes de sync ordonnées (v4, §3.2 ; v0.46.0 : champ dédié, les
     * lignes typées ont disparu avec la console à rangées) : chaque phase
     * annoncée avec ses détails de progression, sans duplication de
     * source de vérité.
     */
    val etapesAffichees: List<EtapeSyncAffichee>
        get() = etapesSync

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

        /**
         * Cumul des octets des artefacts du build courant (v0.46.0 —
         * accumulateur privé : la console écrit UNE ligne par artefant
         * terminé, le volume voyage dans la ligne).
         */
        private var telechargementsOctets = 0L

        /** Dernier COMPTE d'artefacts terminés du build courant (v0.46.0). */
        private var telechargementsCompteur = 0

        /**
         * Clé de la dernière conclusion de sync ÉMISE en console (v0.48.0,
         * ADR 0079) : le terminal du flux ordonné et les échecs LOCAUX de
         * l'appelant (garde JDK, dossier, transport) peuvent conclure la
         * même sync — une clé identique ne réécrit pas la ligne. Remise à
         * null à chaque NOUVELLE cycle (départ de sync, attache d'un
         * espace) : la console repartie vierge reçoit toujours SA
         * conclusion.
         */
        private var cleConclusionSync: String? = null

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

        /** Console (flux brut), observable (rejeu = historique borné). */
        internal val lignesBrutes: SharedFlow<EvenementConsoleTexte> = zoneTexteInterne

        /**
         * Rattache un espace de travail à l'état process-wide (étape 32) :
         * la console et les problèmes repartent vierges (ce sont des vues
         * de CET espace), les activités en vol (build, sync, listage) et
         * la connexion sont conservées — le tooling ne meurt pas avec un
         * écran.
         */
        fun attacher() {
            etatInterne.update { courant ->
                courant.copy(etapesSync = emptyList(), groupesProblemes = emptyList())
            }
            viderZoneTexte(CanalTooling.BUILD)
            viderZoneTexte(CanalTooling.SYNC)
            // v0.48.0 : consoles reparties vierges = conclusions reparties
            // à émettre (une attache pendant une sync en vol conclura).
            cleConclusionSync = null
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
            // v0.46.0 : nouvelle sync = console Sync repart vierge (les
            // étapes précédentes ne se mélangent pas au nouveau déroulé) et
            // fenêtre d'étapes vidée. HORS de `maj` : l'émission d'événement
            // est un effet de bord, la transformation d'état peut être
            // rejouée par le CAS de `MutableStateFlow.update`.
            // v0.48.0 (ADR 0079) : nouvelle cycle = nouvelle conclusion —
            // le mémo de déduplication repart à null.
            if (!etatInterne.value.synchronisationEnCours) {
                viderZoneTexte(CanalTooling.SYNC)
                cleConclusionSync = null
            }
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
                        etapesSync = emptyList(),
                    )
                }
            }
        }

        /** Publie le résultat d'une synchronisation — l'état porte le canal
         *  Sync (résultat, message) ; le rendu localise et balise.
         *  v0.46.0 : une sync RÉUSSIE conclut aussi la CONSOLE (canal Sync)
         *  d'une ligne « Synchronisation terminée en Xs — les tâches sont
         *  disponibles. »
         *  v0.48.0 (ADR 0079) : un ÉCHEC conclut AUSSI la console —
         *  « Synchronisation échouée en Xs — <message> » (parité « SYNC
         *  FAILED » d'Android Studio ; retour terrain : « à la fin du sync
         *  l'UI n'est toujours pas à jour — la console et l'en-tête » : une
         *  console muette sur l'échec est une console pas à jour).
         *  Le terminal du flux ordonné (vidange process-wide) et les échecs
         *  locaux (garde JDK, dossier, transport) peuvent conclure la MÊME
         *  sync (rupture + échec local de l'appelant) : la ligne de
         *  conclusion est DÉDUPLIQUÉE par son CONTENU — un résultat
         *  strictement identique ne réécrit rien, un verdict différent
         *  (SyncResult tardif après un délai d'inactivité) s'écrit : la
         *  chronologie reste honnête, le dernier verdict gagne. */
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
            conclureConsoleSync(resultat)
        }

        /**
         * Émet la conclusion de la console Sync (v0.48.0, ADR 0079) —
         * DÉDUPLIQUÉE par son contenu : le terminal du flux ordonné et les
         * échecs LOCAUX de l'appelant (garde JDK, dossier, transport)
         * peuvent conclure la même sync (rupture + échec local) ; une clé
         * identique ne réécrit rien, un verdict différent (SyncResult
         * tardif après un délai d'inactivité) s'écrit — la chronologie
         * reste honnête, le dernier verdict gagne.
         */
        private fun conclureConsoleSync(resultat: AppResult<ResultatSynchronisation>) {
            val cle = cleConclusion(resultat)
            if (cle == cleConclusionSync) return
            cleConclusionSync = cle
            lignesConclusionSync(resultat).forEach { ligne -> zoneTexteInterne.tryEmit(ligne) }
        }

        /** Lignes de conclusion d'une sync : réussie (durée à la clé) OU
         *  échouée (verdict PUIS message du serveur en erreur — parité
         *  « SYNC FAILED » d'Android Studio : une console muette sur
         *  l'échec est une console pas à jour). */
        private fun lignesConclusionSync(
            resultat: AppResult<ResultatSynchronisation>,
        ): List<EvenementConsoleTexte.Ligne> =
            when {
                resultat is AppResult.Success && resultat.value.reussie -> {
                    val etapes = etatInterne.value.etapesSync
                    LignesConsoleTexte.conclusionSync(
                        aJour = etapes.none { etape -> etape.octetsRecus > 0 },
                        dureeMs = resultat.value.dureeMs,
                    )
                }

                resultat is AppResult.Success -> {
                    LignesConsoleTexte.conclusionSyncEchouee(
                        dureeMs = resultat.value.dureeMs,
                        message = resultat.value.messageEchec,
                    )
                }

                else -> {
                    LignesConsoleTexte.conclusionSyncEchouee(
                        dureeMs = null,
                        message = messageDEchec(resultat as AppResult.Failure),
                    )
                }
            }

        /**
         * Clé d'identité d'une conclusion (v0.48.0) : le résultat réussi
         * entier (durée comprise — deux sync de durées différentes sont
         * DEUX conclusions), sinon le message d'échec.
         */
        private fun cleConclusion(resultat: AppResult<ResultatSynchronisation>): String =
            when (resultat) {
                is AppResult.Success -> {
                    if (resultat.value.reussie) {
                        "ok:" + resultat.value.toString()
                    } else {
                        "ko:" + resultat.value.dureeMs + ":" + resultat.value.messageEchec
                    }
                }

                is AppResult.Failure -> {
                    "ko:-:" + messageDEchec(resultat)
                }
            }

        /**
         * Publie une ligne de sortie de la SYNCHRONISATION sur le flux de la
         * console (v0.48.0, ADR 0079) : le VRAI flux de Gradle — ses
         * avertissements de configuration, les `println` de build script,
         * les statuts de la fenêtre daemon (« Starting Gradle Daemon ») —
         * dans le canal Sync, comme la fenêtre Sync d'Android Studio. Pas
         * de garde d'état : l'ordre du FLUX ordonné (début → lignes → étapes
         * → terminal) garantit qu'une ligne n'arrive jamais hors d'une
         * sync, la conclusion jamais AVANT les lignes qu'elle conclut.
         */
        fun ajouterLigneSync(ligne: LigneSortieSync) {
            zoneTexteInterne.tryEmit(
                EvenementConsoleTexte.Ligne(
                    canal = CanalTooling.SYNC,
                    libelle = TexteTooling.Brut(ligne.ligne),
                    style =
                        if (ligne.flux == FluxSortieBuild.STDERR) {
                            StyleLigne.ERREUR
                        } else {
                            StyleLigne.SORTIE
                        },
                    horodatageMs = ligne.horodatageMs,
                ),
            )
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
         *  v0.46.0 : la console BUILD se vide (même cycle de vie que
         *  l'ancienne zone texte) et l'accumulateur des téléchargements
         *  repart à zéro — un nouveau build ne montre pas la sortie du
         *  précédent. */
        fun suivreBuild(
            buildId: String,
            taches: List<String> = emptyList(),
        ) {
            // v0.43.0 : l'espace ne construit qu'UN build à la fois — un
            // accumulateur de latence restant est celui d'un build orphelin
            // (état terminal jamais reçu, session perdue) : purgé ici.
            latencesPublication.clear()
            telechargementsOctets = 0
            telechargementsCompteur = 0
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
                )
            }
            viderZoneTexte(CanalTooling.BUILD)
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
                if (etat.buildId != courant.buildId) {
                    courant
                } else {
                    // v0.46.0 : l'ANNULATION reçoit une ligne de conclusion
                    // — Gradle n'imprime rien de tel sur son flux après un
                    // cancel en pleine configuration. Succès et échec
                    // s'appuient sur les lignes de Gradle (« BUILD
                    // SUCCESSFUL in 6s », rapport d'échec) : aucune
                    // duplication, parité Android Studio.
                    if (etat.statut == StatutBuild.ANNULE &&
                        courant.statutBuild != StatutBuild.ANNULE
                    ) {
                        zoneTexteInterne.tryEmit(LignesConsoleTexte.buildAnnule())
                    }
                    courant.copy(
                        statutBuild = etat.statut,
                        dureeBuildMs = etat.dureeMs,
                        messageEchecBuild = etat.messageEchec,
                        // v0.39.1 (correctif n°4) : la synthèse
                        // « N actionable tasks: M executed[, K up-to-date] »
                        // extraite côté serveur voyage à l'état — l'en-tête
                        // du panneau la restitue.
                        tachesActionnablesBuild = etat.tachesActionnables,
                        tachesExecuteesBuild = etat.tachesExecutees,
                        tachesAJourBuild = etat.tachesAJour,
                    )
                }
            }
        }

        /** Publie une ligne du build suivi SUR LE FLUX DE LA CONSOLE
         *  (v0.42.0 : fenêtre bornée = rejeu, PLUS D'ÉMISSION D'ÉTAT — la
         *  vue applique par `append()` direct, O(1) par ligne ; v0.46.0 :
         *  la ligne porte son CANAL et son STYLE, le flux est l'unique
         *  affichage). Les lignes d'un autre build et celles après
         *  annulation restent ignorées (garde historique). Un
         *  avertissement CONNU et bénin (C5 : le diagnostic natif du daemon
         *  Gradle, documenté dans docs/TOOLING.md) voyage apaisé : le rendu
         *  l'affiche en style informatif, pas en rouge d'erreur. */
        fun ajouterLigne(ligne: LigneSortieBuild) {
            val courant = etatInterne.value
            if (ligne.buildId != courant.buildId || courant.statutBuild == StatutBuild.ANNULE) return
            // v0.43.0 (mesure console lente) : écart émission serveur →
            // publication console (transport + moitié cliente incluse).
            latencesPublication
                .computeIfAbsent(ligne.buildId) { AccumulateurLatence() }
                .enregistrer(horloge.nowMillis() - ligne.horodatageMs)
            val apaisee = ligne.apaisee()
            zoneTexteInterne.tryEmit(
                EvenementConsoleTexte.Ligne(
                    canal = CanalTooling.BUILD,
                    libelle = TexteTooling.Brut(ligne.ligne),
                    style =
                        when {
                            apaisee -> StyleLigne.APAISEE
                            ligne.flux == FluxSortieBuild.STDERR -> StyleLigne.ERREUR
                            else -> StyleLigne.SORTIE
                        },
                    horodatageMs = ligne.horodatageMs,
                ),
            )
        }

        /**
         * Publie un téléchargement du build suivi (v0.45.1 : le canal
         * téléchargements du client avait AUCUN consommateur — ces
         * événements existaient, la console les ignorait ; v0.46.0 : UNE
         * LIGNE PAR ARTEFACT TERMINÉ sur le flux de la console, jamais par
         * tick d'octets — le détail EN VOL vit dans l'en-tête du panneau,
         * la console est l'historique). Les lignes d'un autre build sont
         * ignorées (garde historique).
         */
        fun ajouterTelechargement(telechargement: jo.codeide.core.domain.TelechargementBuild) {
            val courant = etatInterne.value
            if (telechargement.buildId != courant.buildId ||
                courant.statutBuild != StatutBuild.EN_COURS
            ) {
                return
            }
            telechargementsOctets += telechargement.octetsRecus
            val compteur = telechargement.compteur ?: return
            if (compteur > telechargementsCompteur) {
                telechargementsCompteur = compteur
                zoneTexteInterne.tryEmit(
                    LignesConsoleTexte.telechargementBuild(
                        element = telechargement.element,
                        octetsCumules = telechargementsOctets,
                    ),
                )
            }
        }

        /**
         * Publie une étape de synchronisation (v3 — fin de la boîte noire ;
         * v0.46.0 — console flux brut) : la fenêtre [EtatGradle.etapesSync]
         * garde l'état courant (l'en-tête du panneau y lit son compteur
         * « étape n/N » et sa progression), la CONSOLE reçoit une ligne PAR
         * TRANSITION — « Libellé… » à la première annonce, « Libellé ✓
         * durée » à la conclusion. Les ticks de progression intermédiaires
         * (octets en vol) n'écrivent RIEN : ils actualisent l'état, pas
         * l'historique.
         */
        fun ajouterEtapeSync(etape: EtapeSyncTooling) {
            val affichee = etape.versEtatAffiche()
            val precedente = etatInterne.value.etapesSync.firstOrNull { it.etape == etape.etape }
            maj { courant ->
                courant.copy(
                    etapesSync = courant.etapesSync.filterNot { it.etape == etape.etape } + affichee,
                )
            }
            if (precedente == null && !affichee.terminee) {
                zoneTexteInterne.tryEmit(LignesConsoleTexte.etapeSyncDemarree(etape.etape))
            } else if (affichee.terminee && precedente?.terminee != true) {
                zoneTexteInterne.tryEmit(LignesConsoleTexte.etapeSyncTerminee(affichee))
            }
        }

        /** Publie le VIDAGE de la console d'un canal sur le flux (v0.42.0 ;
         *  v0.46.0 : par CANAL — nouveau build, nouvelle sync,
         *  rattachement d'un espace). L'événement survit dans le rejeu :
         *  une vue qui se ré-abonne plus tard ne reconstruit QUE ce qui
         *  suit le dernier vidage. */
        private fun viderZoneTexte(canal: CanalTooling) {
            zoneTexteInterne.tryEmit(EvenementConsoleTexte.Vider(canal))
        }

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
