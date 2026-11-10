package jo.codeide.feature.sorties

import jo.codeide.core.domain.ArchiveSessionsJournal
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.FinSessionJournal
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.LignesPile
import jo.codeide.core.domain.PontJournauxApplications
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.domain.SessionJournalArchivee
import jo.codeide.core.domain.versLigneSortie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La sélection de session Logcat (R3) — soit une session du REGISTRE du
 * pont (vivante ou terminée), soit une session ARCHIVÉE (« session
 * précédente », consultable entre deux démarrages de CodeIDE).
 */
public sealed interface SelectionLogcat {
    /** Une session du registre du pont (par identifiant). */
    public data class Registre(val id: String) : SelectionLogcat

    /** Une session archivée (par identifiant de session). */
    public data class Archivee(val id: String) : SelectionLogcat
}

/**
 * L'état des SESSIONS publié au chrome de l'onglet Logcat (parité exacte
 * des champs d'état de l'ancien ViewModel — R3/R4) : la liste du
 * sélecteur, la sélection courante, les archives, les pertes annoncées
 * et la fin de la session affichée (bandeaux).
 *
 * @property sessions sessions du registre (vivantes puis terminées).
 * @property selectionRegistre identifiant de la session du registre
 *           affichée (`null` : archive affichée, ou rien).
 * @property selectionArchivee l'archivée affichée (bandeau « précédente »).
 * @property archives sessions précédentes (le sélecteur les liste après
 *           les sessions du registre).
 * @property lignesPerdues pertes annoncées de la session affichée.
 * @property fin raison de fin de la session affichée (`null` : vivante).
 */
public data class EtatSessionsLogcat(
    val sessions: List<SessionJournal> = emptyList(),
    val selectionRegistre: String? = null,
    val selectionArchivee: SessionJournalArchivee? = null,
    val archives: List<SessionJournalArchivee> = emptyList(),
    val lignesPerdues: Long = 0L,
    val fin: FinSessionJournal? = null,
)

/**
 * Un plantage de l'application exécutée (R4) : signalé par le pont
 * (étiquette « Plantage », [LignesPile.estPlantage]), publié UNE fois
 * par session — le snackbar « L'application a planté » s'appuie sur
 * l'identifiant (jamais répété pour la même session).
 */
public data class PlantageLogcat(val idSession: String, val raison: String)

/**
 * Le collecteur LOGCAT du Centre de sorties (lot L4, famille
 * [FamilleSorties.LOGCAT]) — la logique de sessions de l'ancien
 * `LogcatViewModel` (R3, mission « Exécuter ») portée À L'IDENTIQUE
 * derrière le port du lecteur :
 *
 * 1. **Sélection** : une session par (paquet, pid, processus) ; une
 *    session VIVANTE nouvellement née prend la main si rien n'est
 *    affiché ou si l'affichage courant est une ARCHIVE (l'app vient
 *    d'être relancée) — JAMAIS si l'utilisateur consulte une session du
 *    registre (son choix prime, même terminée) ;
 * 2. **Archivage des fins** : chaque session terminée part à
 *    [ArchiveSessionsJournal] (dernière ligne + raison, borné) — UNE
 *    seule fois par session (anti-doublon) ;
 * 3. **Rattrapage « zéro course »** (leçon R3) : le delta incrémental
 *    ([PontJournauxApplications.lignesDepuis]) est lu à CHAQUE
 *    réémission de l'état des sessions (le signal du pont) — un
 *    afficheur ne perd JAMAIS une ligne, contrairement à une
 *    souscription de flux dont l'enregistrement et la lecture
 *    d'instantané ne sont pas atomiques ;
 * 4. **Plantage R4** : la ligne d'annonce du pont (étiquette
 *    « Plantage ») est publiée une fois par session.
 *
 * La PAUSE du défilement vit dans le LECTEUR (le tampon ingère, la vue
 * reste figée, la reprise livre le delta en une fois) — la source, elle,
 * continue toujours de collecter (parité « la collecte continue côté
 * registre ») : aucun état de pause ici, donc rien ne peut être perdu
 * pendant une pause. L'effacement du tampon affiché est aussi un geste
 * du lecteur — les sessions gardées ne sont jamais touchées.
 */
@Suppress("TooManyFunctions")
@Singleton
public class CollecteurSortiesLogcat
@Inject
constructor(
    private val pont: PontJournauxApplications,
    private val archive: ArchiveSessionsJournal,
    dispatchers: DispatcherProvider,
) {
    private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** Les lots de NOUVELLES lignes adaptées (delta de la sélection). */
    private val _lots = MutableSharedFlow<List<LigneSortie>>(extraBufferCapacity = 64)

    /** Les lots vivants de la session sélectionnée (adaptées au modèle
     *  unique — famille LOGCAT, clé de session `paquet/pid/processus`). */
    public val lots: Flow<List<LigneSortie>> = _lots.asSharedFlow()

    /** L'état des sessions (chrome de l'onglet — sélecteur, bandeaux). */
    private val _etatSessions = MutableStateFlow(EtatSessionsLogcat())
    public val etatSessions: StateFlow<EtatSessionsLogcat> = _etatSessions.asStateFlow()

    /** Le plantage courant (R4 — snackbar de l'activité). */
    private val _plantage = MutableStateFlow<PlantageLogcat?>(null)
    public val plantage: StateFlow<PlantageLogcat?> = _plantage.asStateFlow()

    /** La sélection courante (le flux du lecteur la suit). */
    private val _selection = MutableStateFlow<SelectionLogcat?>(null)
    public val selection: StateFlow<SelectionLogcat?> = _selection.asStateFlow()

    /** Le numéro de génération — chaque changement de sélection est un
     *  changement de source derrière le port (reset honnête du lecteur). */
    private val _generation = MutableStateFlow(0)
    public val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Position de rattrapage (compteur de lignes du registre). */
    private var positionRendue = 0

    /** Identifiants déjà archivés (anti-doublon — UNE archive par fin). */
    private val dejaArchivees = mutableSetOf<String>()

    /** Identifiants dont le plantage est DÉJÀ annoncé (une fois/session). */
    private val plantagesAnnonces = mutableSetOf<String>()

    /** Identifiants des vivantes connues — seule une vivante NOUVELLEMENT
     *  née déloge une archive consultée. */
    private val vivantesConnues = mutableSetOf<String>()

    /** Archives chargées (miroir interne, pour la re-sélection). */
    private var archivesChargees: List<SessionJournalArchivee> = emptyList()

    init {
        // Sessions du pont : publication, archivage des fins,
        // autosélection, rattrapage du delta (le signal de course).
        portee.launch {
            pont.sessions.collect { sessions -> surSessions(sessions) }
        }
        // Archives : au démarrage, la plus récente s'affiche si rien de
        // vivant n'occupe l'onglet (jamais du vide muet).
        portee.launch {
            archivesChargees = archive.charger()
            siRienAAfficher { afficherArchivee(archivesChargees.firstOrNull()) }
            publierEtat()
        }
    }

    /**
     * Sélectionne une session du REGISTRE (sélecteur de processus) — le
     * lecteur repart du cliché (tout ce que le registre retient), les
     * lots suivants s'y ajoutent. Le choix de l'utilisateur prime sur
     * l'autosélection, même sur une session terminée.
     */
    public fun selectionnerSession(id: String) {
        val session = _etatSessions.value.sessions.firstOrNull { it.id == id } ?: return
        changerSelection(SelectionLogcat.Registre(id))
        positionRendue = 0
        publierEtat()
        rattraper()
    }

    /** Sélectionne une session ARCHIVÉE (« session précédente »). */
    public fun selectionnerArchivee(id: String) {
        val archivee = archivesChargees.firstOrNull { it.session.id == id } ?: return
        afficherArchivee(archivee)
    }

    /** Le cliché de la sélection : tout ce que la source retient. */
    public suspend fun cliche(selection: SelectionLogcat?): List<LigneSortie> = when (selection) {
        is SelectionLogcat.Registre ->
            pont.instantane(selection.id).map { it.versLigneSortie(selection.id) }

        is SelectionLogcat.Archivee ->
            archivesChargees
                .firstOrNull { it.session.id == selection.id }
                ?.derniereLigne
                ?.let { listOf(it.versLigneSortie(selection.id)) }
                ?: emptyList()

        null -> emptyList()
    }

    // ------------------------------------------------------------------
    // Internes — la logique de l'ancien ViewModel, à l'identique.
    // ------------------------------------------------------------------

    /** Réaction à chaque liste de sessions du registre (le signal). */
    private fun surSessions(sessions: List<SessionJournal>) {
        // 1. Publie la liste (sélecteur, bandeaux de fin et de pertes)
        //    AVANT toute autosélection : celle-ci doit voir les
        //    NOUVELLES sessions (selectionnerSession lit l'état).
        publierEtat(sessions)

        // 2. Archive les sessions VENUES de se terminer (la dernière
        //    ligne est lue dans l'instantané, avant tout nouveau lot) —
        //    UNE fois par session (anti-doublon).
        sessions
            .filter { it.etat == EtatSessionJournal.TERMINEE && it.id !in dejaArchivees }
            .forEach { session ->
                dejaArchivees += session.id
                portee.launch {
                    archive.archiver(session, pont.instantane(session.id).lastOrNull())
                    archivesChargees = archive.charger()
                    publierEtat()
                }
            }

        // 3. Autosélection : une vivante NOUVELLEMENT née prend la main
        //    si rien n'est affiché ou si l'affichage courant est une
        //    ARCHIVE — jamais si l'utilisateur consulte une session du
        //    registre (son choix prime, même terminée).
        val vivantes = sessions.filter { it.etat == EtatSessionJournal.VIVANTE }
        val naissance = vivantes.any { it.id !in vivantesConnues }
        vivantesConnues.clear()
        vivantesConnues.addAll(vivantes.map { it.id })
        val etat = _etatSessions.value
        when {
            vivantes.isNotEmpty() && etat.selectionRegistre == null &&
                etat.selectionArchivee == null -> {
                selectionnerSession(vivantes.last().id)
            }

            naissance && etat.selectionArchivee != null -> {
                selectionnerSession(vivantes.last().id)
            }

            vivantes.isEmpty() && etat.selectionRegistre == null &&
                etat.selectionArchivee == null -> {
                // Rien de vivant : la plus récente archive s'affiche.
                afficherArchivee(archivesChargees.firstOrNull())
            }
        }

        // 4. Rattrapage du delta (chaque réémission y repasse).
        rattraper()
    }

    /** Affiche une session archivée : bandeau gris + dernière ligne. */
    private fun afficherArchivee(archivee: SessionJournalArchivee?) {
        if (archivee == null) return
        changerSelection(SelectionLogcat.Archivee(archivee.session.id))
        positionRendue = 0
        publierEtat()
    }

    /** Change la sélection et signale le RESET au lecteur (génération). */
    private fun changerSelection(selection: SelectionLogcat) {
        if (_selection.value == selection) return
        _selection.value = selection
        _generation.value += 1
    }

    /**
     * Rattrapage incrémental « zéro course » : le delta des lignes
     * STRICTEMENT après [positionRendue] est adapté et publié ; le
     * plantage (R4) est annoncé UNE seule fois par session.
     */
    private fun rattraper() {
        val selection = _selection.value as? SelectionLogcat.Registre ?: return
        val id = selection.id
        val delta = pont.lignesDepuis(id, positionRendue)
        if (delta.lignes.isEmpty()) return
        positionRendue = delta.apres
        portee.launch {
            _lots.emit(delta.lignes.map { it.versLigneSortie(id) })
        }
        // R4 : le pont annonce l'exception non interceptée de CETTE
        // session — publiée UNE fois (le snackbar s'appuie sur
        // l'identifiant, jamais répété).
        val annonce = delta.lignes.firstOrNull { LignesPile.estPlantage(it) }
        if (annonce != null && id !in plantagesAnnonces) {
            plantagesAnnonces += id
            _plantage.value = PlantageLogcat(id, annonce.message)
        }
    }

    /** Publie l'état dérivé de la sélection courante (bandeaux). */
    private fun publierEtat() {
        val etatCourant = _etatSessions.value
        val selection = _selection.value
        val sessionAffichee = (selection as? SelectionLogcat.Registre)?.id
            ?.let { id -> etatCourant.sessions.firstOrNull { it.id == id } }
        val archiveeAffichee = (selection as? SelectionLogcat.Archivee)?.id
            ?.let { id -> archivesChargees.firstOrNull { it.session.id == id } }
        _etatSessions.value = etatCourant.copy(
            selectionRegistre = (selection as? SelectionLogcat.Registre)?.id,
            selectionArchivee = archiveeAffichee,
            archives = archivesChargees,
            lignesPerdues = sessionAffichee?.lignesPerdues
                ?: archiveeAffichee?.session?.lignesPerdues
                ?: 0L,
            fin = sessionAffichee?.fin ?: archiveeAffichee?.session?.fin,
        )
    }

    /** Publie la liste des sessions du pont (sélecteur) puis les dérivés. */
    private fun publierEtat(sessions: List<SessionJournal>) {
        _etatSessions.value = _etatSessions.value.copy(sessions = sessions)
        publierEtat()
    }

    /** Exécute l'action seulement si rien n'est affiché (autosélection). */
    private inline fun siRienAAfficher(action: () -> Unit) {
        val etat = _etatSessions.value
        if (etat.selectionRegistre == null && etat.selectionArchivee == null) action()
    }
}

/**
 * Le flux LOGCAT du Centre de sorties (lot L4) — l'implémentation du
 * port [FluxSorties] pour la famille [FamilleSorties.LOGCAT] : le cliché
 * de la session sélectionnée (tout ce que la source retient), puis les
 * lots vivants du [CollecteurSortiesLogcat] ; chaque changement de
 * sélection est un changement de source (génération incrémentée — le
 * lecteur repart du cliché de la nouvelle session, jamais un mélange).
 *
 * La capacité de fenêtre est celle du Logcat ([FENETRE_LOGCAT] — parité
 * du miroir d'affichage R3) ; le banc et l'état d'exécution Gradle
 * n'existent pas derrière ce flux (`null` constants).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
public class FluxSortiesLogcat
@Inject
constructor(
    private val collecteur: CollecteurSortiesLogcat,
) : FluxSorties {
    override fun capaciteFenetre(): Int = FENETRE_LOGCAT

    override fun lots(): Flow<List<LigneSortie>> = collecteur.selection.flatMapLatest { selection ->
        flow {
            emit(collecteur.cliche(selection))
            emitAll(collecteur.lots)
        }
    }

    override val sequenceArmee: StateFlow<SequenceBanc?> =
        MutableStateFlow(null)

    /** Aucune exécution Gradle derrière le Logcat. */
    override val etatExecution: StateFlow<EtatExecutionGradle?> =
        MutableStateFlow(null)

    /** La génération du collecteur (changements de session). */
    override val generation: StateFlow<Int> = collecteur.generation
}
