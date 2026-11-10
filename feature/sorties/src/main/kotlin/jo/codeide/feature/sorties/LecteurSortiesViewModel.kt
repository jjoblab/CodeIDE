package jo.codeide.feature.sorties

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ClassificationLigneGradle
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.EtatRegroupement
import jo.codeide.core.domain.EtatTamponSorties
import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.LotTampon
import jo.codeide.core.domain.PositionSource
import jo.codeide.core.domain.ProjectionRegroupee
import jo.codeide.core.domain.ProjectionRegroupeeLogcat
import jo.codeide.core.domain.ProjectionSorties
import jo.codeide.core.domain.RangeeRegroupeeLogcat
import jo.codeide.core.domain.RegroupementLogcat
import jo.codeide.core.domain.RegroupementSorties
import jo.codeide.core.domain.RequeteRecherche
import jo.codeide.core.domain.SectionFluxGradle
import jo.codeide.core.domain.TamponSorties
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.classifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Un delta d'application de projection — les rangées tombées de la tête
 * et les rangées ajoutées en fin : le lecteur en dérive des notifications
 * BORNÉES (ADR 0159 — append par lot, jamais un re-bind complet en
 * régime).
 *
 * @property retraitTete rangées visibles tombées de la tête (troncature
 *           de fenêtre).
 * @property ajoutFin rangées visibles ajoutées en fin de liste.
 */
internal data class DeltaProjection(val retraitTete: Int, val ajoutFin: Int)

/**
 * La projection PUBLIÉE par le lecteur — la liste affichée (rangées de
 * rendu) et les compteurs d'honnêteté (règle 14). Un `delta` non nul
 * s'applique INCRÉMENTALEMENT ; `null` = re-liaison complète (reprise de
 * pause — le delta arrive EN UNE fois, spec SORTIES § 3.3 — changement
 * de filtre, effacement).
 *
 * @property rangées rangées de rendu (lignes ou en-têtes — lot L3),
 *           alignées sur la projection courante.
 * @property delta delta d'application, ou `null` pour tout relier.
 * @property tailleTampon lignes présentes dans la fenêtre du tampon.
 * @property pertes lignes perdues depuis la dernière vidange (bandeau).
 * @property masquees lignes masquées par le filtre (bandeau — les lignes
 *           restent dans le tampon, spec SORTIES § 3.5).
 * @property origineMs horodatage de la première ligne de la fenêtre
 *           (origine de l'horodatage relatif).
 */
internal data class ProjectionLecteur(
    val rangees: List<RangeeLecteur>,
    val delta: DeltaProjection?,
    val tailleTampon: Int,
    val pertes: Long,
    val masquees: Int,
    val origineMs: Long,
) {
    internal companion object {
        /** L'état initial du lecteur (tampon vide). */
        internal val VIDE = ProjectionLecteur(
            rangees = emptyList(),
            delta = null,
            tailleTampon = 0,
            pertes = 0L,
            masquees = 0,
            origineMs = 0L,
        )
    }
}

/**
 * Le ViewModel du lecteur du Centre de sorties (ADR 0159, lot L2) — le
 * SEUL détenteur du tampon et de l'index de projection (ADR 0162 : la
 * logique vit dans le domaine, le ViewModel orchestre). Toute la
 * mécanique réside dans les classes pures `TamponSorties` /
 * `ProjectionSorties` / `RechercheSorties` / `RechercheLecteur` de
 * `core:domain` et de ce module.
 *
 * Discipline du chemin chaud (ADR 0074) : ingestion PAR LOT, projection
 * incrémentale (seules les lignes nouvelles sont testées), rangées
 * construites UNE fois à l'ingestion (l'analyse SGR n'est jamais
 * refaite), notifications bornées. Le mutex sérialise ingestion (flux de
 * la source) et commandes (recherche, filtre, pause, effacement) — elles
 * partagent le tampon.
 *
 * Une instance par famille de source (lot L4 — [FabriqueFluxSorties] lui
 * livre le flux de SA famille, portée par l'argument du fragment hôte
 * via [SavedStateHandle]) ; deux lecteurs simultanés (onglet Logcat et
 * onglet Journal du même panneau, banc) ne partagent RIEN — deux flux
 * séparés, deux tampons séparés. Au sein d'UNE famille, un changement de
 * source (session Logcat sélectionnée, banc armé) est signalé par la
 * GÉNÉRATION du flux : l'état vivant est vidé — jamais un mélange de
 * deux sources dans un même tampon.
 *
 * @property famille la famille de source de CE lecteur (défaut GRADLE).
 * @property dispatchers dispatchers injectés (règle 5).
 * @property horloge horloge injectée pour l'horodatage à l'ingestion.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
internal class LecteurSortiesViewModel
@Inject
constructor(
    fabrique: FabriqueFluxSorties,
    private val dispatchers: DispatcherProvider,
    horloge: TimeProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** La famille de CE lecteur — l'argument `famille_sorties` du
     *  fragment hôte (survit à la rotation), défaut GRADLE (banc et
     *  futures surfaces Gradle). */
    val famille: FamilleSorties = savedStateHandle.get<String>(CLE_FAMILLE)
        ?.let { nom -> FamilleSorties.entries.firstOrNull { it.name == nom } }
        ?: FamilleSorties.GRADLE

    /** Le flux de la famille — le SEUL câblage que le lecteur connaît. */
    private val flux: FluxSorties = fabrique.flux(famille)

    /** Le mode REGROUPÉ est proposé pour GRADLE (dérivation par tâche,
     *  lot L3) ET pour LOGCAT (répétitions ×N et traces repliées, lot
     *  L5 — spec SORTIES § 5.4 M5) ; l'UI cache la bascule ailleurs
     *  (JOURNAL — pas de dérivation planifiée pour ce lot) : honnêteté,
     *  jamais une bascule qui prétend regrouper sans le faire. */
    val modeDisponible: Boolean
        get() = famille == FamilleSorties.GRADLE || famille == FamilleSorties.LOGCAT

    private val tampon = TamponSorties(flux.capaciteFenetre(), horloge)
    private val verrou = Mutex()

    private var filtreActuel: FiltreSorties = FiltreSorties()
    private var visibles: List<Int> = emptyList()
    private var rangees: List<RangeeLecteur> = emptyList()
    private var marquesErreurs: List<Int> = emptyList()
    private var marquesAvertissements: List<Int> = emptyList()
    private var enPause = false
    private val rechercheLecteur = RechercheLecteur()

    /** Le mode de lecture courant (brut par défaut — ADR 0161.5 pour la
     *  consultation, L2 pour le live). */
    private var modeActuel = ModeLecture.BRUT
    private val _mode = MutableStateFlow(ModeLecture.BRUT)
    val mode: StateFlow<ModeLecture> = _mode.asStateFlow()

    /** Les groupes REPLIÉS (indices dans l'état dérivé) — en mémoire,
     *  réglage persistant au lot L6 (M13). */
    private val replisActuels = HashSet<Int>()

    /** La dérivation regroupée publiée (résumé + groupes), `null` hors
     *  mode regroupé — la carte de résumé la lit. */
    private val _regroupement = MutableStateFlow<EtatRegroupement?>(null)
    val regroupement: StateFlow<EtatRegroupement?> = _regroupement.asStateFlow()

    /** Les classifications (UNE par ligne de tampon, alignées) — tenues à
     *  l'ingestion en mode regroupé, chaque ligne analysée UNE fois. */
    private var classifications: ArrayList<ClassificationLigneGradle?> = ArrayList()

    /** Les rangées de rendu en cache (UNE construction par ligne — le
     *  regroupement ne re-parse jamais le SGR à chaque dérivation). */
    private val cacheRangees = HashMap<LigneSortie, RangeeSortie>()

    /** La consultation d'une exécution persistée (ADR 0161.5) : le live
     *  est retenu (les lots sont ignorés) jusqu'au retour au direct. */
    private var consultationActive = false
    private val _enConsultation = MutableStateFlow(false)
    val enConsultation: StateFlow<Boolean> = _enConsultation.asStateFlow()

    /** L'état de consultation (les compteurs de l'exécution re-ouverte) —
     *  alimente la combinaison [etatExecution], jamais publié seul. */
    private val etatConsultationInterne = MutableStateFlow<EtatExecutionGradle?>(null)

    /**
     * L'état d'exécution courant : les événements du câble, ou l'état de
     * l'exécution consultée (une exécution re-ouverte montre le MÊME
     * résumé que le live — ADR 0161 « Conséquences »).
     */
    val etatExecution: StateFlow<EtatExecutionGradle?> = combine(
        flux.etatExecution,
        etatConsultationInterne,
    ) { enDirect, enConsultation -> enConsultation ?: enDirect }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** Une demande d'ouverture de source (liens cliquables — les
     *  positions du flux vers l'éditeur, spec SORTIES § 4.3) : la
     *  surface la branche au port de l'éditeur actif. */
    private val _demandeOuverture = MutableSharedFlow<PositionSource>(replay = 0, extraBufferCapacity = 8)
    val demandeOuverture: SharedFlow<PositionSource> = _demandeOuverture.asSharedFlow()

    /** Relaye une demande d'ouverture (le geste de l'adaptateur). */
    public fun demanderOuverture(position: PositionSource) {
        _demandeOuverture.tryEmit(position)
    }

    /** La projection publiée (liste affichée + compteurs d'honnêteté). */
    private val _projection = MutableStateFlow(ProjectionLecteur.VIDE)
    val projection: StateFlow<ProjectionLecteur> = _projection.asStateFlow()

    /** L'état de recherche publié (compteur n/N, motif invalide). */
    private val _recherche = MutableStateFlow<EtatRechercheLecteur?>(null)
    val recherche: StateFlow<EtatRechercheLecteur?> = _recherche.asStateFlow()

    /** Les réglages de rendu (numéros, horodatage, retour à la ligne,
     *  taille de police). */
    private val _reglages = MutableStateFlow(OptionsRenduRangee())
    val reglages: StateFlow<OptionsRenduRangee> = _reglages.asStateFlow()

    /** Le direct est-il en pause ? */
    private val _enPauseDirect = MutableStateFlow(false)
    val enPauseDirect: StateFlow<Boolean> = _enPauseDirect.asStateFlow()

    /** Le contrôleur de la sélection multi-lignes (spec SORTIES § 3.6). */
    val selection = SelectionLecteur()

    /** Les repères de la poignée (espaces RANGÉES) : erreurs, avert. */
    private val _marques = MutableStateFlow(MarquesLecteur())
    val marques: StateFlow<MarquesLecteur> = _marques.asStateFlow()

    /** Une demande de déplacement (index de RANGÉE cible). */
    private val _allerA = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 8)
    val allerA: SharedFlow<Int> = _allerA.asSharedFlow()

    /** La position visible à RETENIR (rotation — spec SORTIES § 3.11 :
     *  pivoter ne perd pas la position) ; écrite par le fragment en
     *  sortie de vue, relue à l'attachement. */
    var positionRetenue: Int? = null

    /** La capacité de fenêtre du tampon (lignes) — l'en-tête l'affiche. */
    val capaciteFenetre: Int
        get() = flux.capaciteFenetre()

    /** La séquence banc armée (diagnostic), ou `null` hors banc. */
    val sequenceArmee: StateFlow<SequenceBanc?>
        get() = flux.sequenceArmee

    init {
        viewModelScope.launch(dispatchers.io) {
            flux.lots().collect { lot -> ingerer(lot) }
        }
        // Le signal de RESET de la source (lot L4) : au changement de
        // génération (session Logcat sélectionnée, banc armé/désarmé),
        // l'état vivant est vidé — jamais un mélange de deux sources.
        viewModelScope.launch(dispatchers.io) {
            var generationConnue = flux.generation.value
            flux.generation.collect { generation ->
                if (generation != generationConnue) {
                    generationConnue = generation
                    verrou.withLock {
                        consultationActive = false
                        _enConsultation.value = false
                        etatConsultationInterne.value = null
                        viderEtatVivant()
                    }
                }
            }
        }
    }

    /**
     * LANCE/RELANCE la recherche — hors thread principal, annulée par sa
     * relance (le mutex sérialise avec l'ingestion). Une requête vide
     * efface l'état de recherche.
     */
    public fun definirRecherche(texte: String, regex: Boolean) {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                definirRechercheInterne(RequeteRecherche(texte, regex = regex))
            }
        }
    }

    /**
     * Navigue la recherche ([pas] = +1 suivant, −1 précédent) — les
     * correspondances masquées sont comptées dans N mais leur saut est
     * sauté.
     */
    public fun naviguerCorrespondance(pas: Int) {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                val rangee = rechercheLecteur.naviguer(pas, rangVisible)
                _recherche.value = rechercheLecteur.etat()
                rangee?.let { _allerA.tryEmit(it) }
            }
        }
    }

    /**
     * SAUT « erreur suivante / précédente » (spec SORTIES § 5.4, lot L5)
     * — [pas] = +1 suivant, −1 précédent, depuis la rangée [rangDepart]
     * (la première visible du lecteur) ; le parcours BOUCLE (jamais un
     * geste mort) sur les repères d'erreurs de la projection COURANTE
     * (brut ou regroupé — les en-têtes ne répondent pas, seules les
     * lignes portent la gravité). Sans repère : rien (l'UI cache le
     * geste).
     */
    public fun naviguerErreur(pas: Int, rangDepart: Int) {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                cibleErreur(marquesErreurs, pas, rangDepart)?.let { cible ->
                    _allerA.tryEmit(cible)
                }
            }
        }
    }

    /**
     * Pause/reprise du direct — en pause, le tampon RETIENT le flux (les
     * pertes continuent d'être comptées, la vue reste figée) ; à la
     * reprise, le delta arrive EN UNE fois (re-dérivation complète,
     * `delta = null`).
     */
    public fun basculerPause() {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                enPause = !enPause
                _enPauseDirect.value = enPause
                if (!enPause) rederriverComplete()
            }
        }
    }

    /**
     * Redéfinit le filtre unifié (ADR 0160) — le tampon ne bouge pas, la
     * projection est redérivée ENTIERE et les lignes masquées comptées.
     */
    public fun definirFiltre(filtre: FiltreSorties) {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                filtreActuel = filtre
                rederriverComplete()
            }
        }
    }

    /**
     * Les SUGGESTIONS d'étiquettes avec compteurs (spec SORTIES § 5.4,
     * lot L5) — un passage sur la fenêtre au GESTE de l'utilisateur (le
     * menu s'ouvre — jamais dans le chemin chaud de l'ingestion), tri
     * compte décroissant puis alphabétique, borné ([BORNE_ETIQUETTES]).
     * L'état de chaque puce (neutre/incluse/exclue) vient du filtre
     * courant — le menu dit la vérité du filtre.
     */
    public suspend fun etiquettesComptees(): List<EtiquetteComptee> {
        verrou.withLock {
            return etiquettesCompteesInterne(tampon.instantane(), filtreActuel)
        }
    }

    /**
     * Bascule UNE étiquette dans le filtre (le cycle de la puce, spec
     * § 5.4 : neutre → incluse → exclue → neutre) — le RESTE du filtre
     * est conservé (niveau poussé par le chrome, plage de temps…) :
     * copy du filtre, jamais un filtre neuf. Le tampon ne bouge pas.
     */
    public fun basculerEtiquette(etiquette: String) {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                filtreActuel = cyclerEtiquette(filtreActuel, etiquette)
                rederriverComplete()
            }
        }
    }

    /** Effacement EXPLICITE du tampon (jamais automatique — règle 14). */
    public fun effacer() {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                tampon.vider()
                visibles = emptyList()
                rangees = emptyList()
                marquesErreurs = emptyList()
                marquesAvertissements = emptyList()
                rechercheLecteur.effacer()
                _recherche.value = null
                _marques.value = MarquesLecteur()
                _projection.value = ProjectionLecteur.VIDE
            }
        }
    }

    /**
     * Régle une option de rendu (numéros, horodatage, retour à la ligne,
     * taille de police) — la transformation est pure ; la taille de
     * police est ramenée dans les bornes du zoom (10–20 sp).
     */
    public fun regler(modification: (OptionsRenduRangee) -> OptionsRenduRangee) {
        _reglages.update { modification(it).taillePoliceBornee() }
    }

    /**
     * Ingestion d'un lot — le chemin chaud. Sous le verrou : le tampon
     * ingère (pertes comptées MÊME en pause), puis, HORS pause, la
     * projection est dérivée incrémentalement et seules les rangées
     * nouvelles sont construites (analyse SGR une seule fois). En pause,
     * la vue reste figée — le bandeau de reprise dira tout (honnêteté à
     * la reprise, spec SORTIES § 3.3).
     */
    private suspend fun ingerer(lot: List<LigneSortie>) {
        verrou.withLock {
            // Consultation (ADR 0161.5) : le live est RETENU — la lecture
            // d'une exécution passée ne se fait pas écraser par le câble.
            if (consultationActive) return@withLock
            val tailleAvant = tampon.taille
            val troncature = tampon.ajouterLot(lot)
            if (enPause) return@withLock
            val etat = tampon.instantane()
            val tombes = visibles.count { it < troncature }
            val survivants = visibles.size - tombes
            val apres = ProjectionSorties.apresLot(
                visibles,
                LotTampon(troncature, tailleAvant, etat),
                filtreActuel,
            )
            visibles = apres.visibles
            // Les correspondances de recherche glissent avec la fenêtre.
            rechercheLecteur.decaler(troncature)
            _recherche.value = rechercheLecteur.etat()
            if (modeActuel == ModeLecture.REGROUPE) {
                // Mode regroupé : les classifications nouvelles sont
                // calculées (UNE fois par ligne), la dérivation est un
                // simple parcours (aucune regex à répétition).
                maintenirClassifications(etat, tailleAvant, troncature)
                deriverRegroupe(etat)
            } else {
                // Mode brut (chemin L2) : les survivantes sont RÉUTILISÉES
                // (aucune re-parse), seules les nouvelles sont construites.
                val nouvelles = apres.visibles.drop(survivants)
                val nouvellesRangees = nouvelles.map { indice ->
                    RangeeLecteur.Ligne(indice, RangeeSortie.de(etat.lignes[indice]))
                }
                rangees = rangees.drop(tombes) + nouvellesRangees
                marquesErreurs = decalerListe(marquesErreurs, tombes) +
                    marquesDesLignes(nouvellesRangees) { it.estErreur }.map { survivants + it }
                marquesAvertissements = decalerListe(marquesAvertissements, tombes) +
                    marquesDesLignes(nouvellesRangees) { it.estAvertissement }.map { survivants + it }
                publierMarques()
                publierProjection(etat, DeltaProjection(tombes, nouvellesRangees.size))
            }
        }
    }

    /**
     * Re-dérivation COMPLÈTE (reprise de pause, changement de filtre) —
     * delta `null` : le lecteur relie tout EN UNE fois ; la recherche
     * vivante est rejouée sur le tampon courant.
     */
    private suspend fun rederriverComplete() {
        if (modeActuel == ModeLecture.REGROUPE) {
            val etat = tampon.instantane()
            maintenirClassifications(etat, etat.lignes.size, 0)
            deriverRegroupe(etat)
            val requeteActive = _recherche.value?.requete
            if (requeteActive != null) definirRechercheInterne(requeteActive)
            return
        }
        val etat = tampon.instantane()
        val apres = ProjectionSorties.deriver(
            etat.lignes,
            etat.messagesPlies,
            etat.etiquettesPlies,
            filtreActuel,
        )
        visibles = apres.visibles
        rangees = visibles.map { indice -> RangeeLecteur.Ligne(indice, RangeeSortie.de(etat.lignes[indice])) }
        marquesErreurs = rangeesMarquees(rangees) { it.estErreur }
        marquesAvertissements = rangeesMarquees(rangees) { it.estAvertissement }
        publierMarques()
        val requeteActive = _recherche.value?.requete
        if (requeteActive != null) definirRechercheInterne(requeteActive)
        publierProjection(etat, delta = null)
    }

    /**
     * La dérivation REGROUPÉE (mode conscient, lot L3) — du tampon
     * classifications comprises : le résumé est publié à part (la carte
     * en tête le lit), les rangées regroupées remplacent la liste (UNE
     * seule liste re-projetée, ADR 0078). La recherche est remappée sur
     * les rangées VISIBLES du regroupement (les lignes d'un groupe replié
     * ne prennent ni rangée ni repère).
     */
    private fun deriverRegroupe(etat: EtatTamponSorties) {
        // Le Logcat a SA dérivation (lot L5) — classes propres à la
        // famille, le vocabulaire de tâches ne s'y applique pas.
        if (famille == FamilleSorties.LOGCAT) {
            deriverRegroupeLogcat(etat)
            return
        }
        val regroupe = RegroupementSorties.deriverClasse(
            etat.lignes,
            classifications,
            etatExecution.value,
        )
        _regroupement.value = regroupe
        val liste = ProjectionRegroupee.deriver(visibles, regroupe, replisActuels)
        val positions = HashMap<Int, Int>(visibles.size)
        var rang = 0
        val nouvellesRangees = liste.map { rangee ->
            when (rangee) {
                is jo.codeide.core.domain.RangeeRegroupee.Ligne -> {
                    positions[rangee.position] = rang
                    rang++
                    RangeeLecteur.Ligne(rangee.position, rangeeEnCache(etat.lignes[rangee.position]))
                }

                is jo.codeide.core.domain.RangeeRegroupee.EnteteTache -> RangeeLecteur.EnteteTache(
                    indiceGroupe = rangee.indiceGroupe,
                    groupe = rangee.groupe,
                    lignesVisibles = rangee.lignesVisibles,
                    replie = replisActuels.contains(rangee.indiceGroupe),
                )
            }
        }
        rangees = nouvellesRangees
        rangVisible = { position -> positions[position] }
        marquesErreurs = marquesDesLignes(rangees) { it.estErreur }
        marquesAvertissements = marquesDesLignes(rangees) { it.estAvertissement }
        publierMarques()
        publierProjection(etat, delta = null)
    }

    /**
     * La dérivation REGROUPÉE LOGCAT (lot L5, spec SORTIES § 5.4 M5) —
     * répétitions ×N et traces repliées via [RegroupementLogcat]
     * (classes PROPRES à la famille), projection
     * [ProjectionRegroupeeLogcat], rangées [RangeeLecteur.EnteteLogcat].
     * Le résumé Gradle ([_regroupement]) reste `null` : pas de carte de
     * résumé ni d'en-tête collant pour le Logcat — l'UI honnête. La
     * recherche est remappée sur les rangées VISIBLES du regroupement
     * (une ligne de groupe replié ne répond plus — MÊME discipline que
     * la vue Gradle). */
    private fun deriverRegroupeLogcat(etat: EtatTamponSorties) {
        val derive = RegroupementLogcat.deriver(etat.lignes)
        val liste = ProjectionRegroupeeLogcat.deriver(visibles, derive, replisActuels)
        val positions = HashMap<Int, Int>(visibles.size)
        var rang = 0
        val nouvellesRangees = liste.map { rangee ->
            when (rangee) {
                is RangeeRegroupeeLogcat.Ligne -> {
                    positions[rangee.position] = rang
                    rang++
                    RangeeLecteur.Ligne(rangee.position, rangeeEnCache(etat.lignes[rangee.position]))
                }

                is RangeeRegroupeeLogcat.EnteteLogcat -> RangeeLecteur.EnteteLogcat(
                    indiceGroupe = rangee.indiceGroupe,
                    groupe = rangee.groupe,
                    rangee = rangeeEnCache(etat.lignes[rangee.position]),
                    lignesVisibles = rangee.lignesVisibles,
                    causesVisibles = rangee.causesVisibles,
                    replie = replisActuels.contains(rangee.indiceGroupe),
                )
            }
        }
        rangees = nouvellesRangees
        rangVisible = { position -> positions[position] }
        marquesErreurs = marquesDesLignes(rangees) { it.estErreur }
        marquesAvertissements = marquesDesLignes(rangees) { it.estAvertissement }
        publierMarques()
        publierProjection(etat, delta = null)
    }

    /**
     * Bascule brut ↔ regroupé (spec SORTIES § 4, lot L3 ; Logcat inclus
     * au lot L5 — § 5.4). Le passage en
     * regroupé classe TOUTE la fenêtre UNE fois (le banc S5 mesure cette
     * bascule — chaque ligne n'est jamais analysée deux fois) ; le
     * retour au brut repart du chemin L2 sans coût.
     */
    public fun definirMode(mode: ModeLecture) {
        // Refus honnête hors familles à dérivation (GRADLE, LOGCAT — la
        // bascule est cachée dans l'UI pour JOURNAL).
        if (!modeDisponible) return
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                if (modeActuel == mode) return@withLock
                modeActuel = mode
                _mode.value = mode
                if (mode == ModeLecture.REGROUPE) {
                    classifierTout()
                    deriverRegroupe(tampon.instantane())
                } else {
                    replisActuels.clear()
                    _regroupement.value = null
                    classifications = ArrayList()
                    cacheRangees.clear()
                    rederriverComplete()
                }
            }
        }
    }

    /**
     * Repli/dépli d'UN groupe (l'en-tête est le geste) — la liste est
     * re-projetée EN UNE fois (delta nul : jamais un recalcul DiffUtil
     * caché, les rangées LIGNE restent en cache sans re-parse).
     */
    public fun basculerRepli(indiceGroupe: Int) {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                if (modeActuel != ModeLecture.REGROUPE) return@withLock
                if (!replisActuels.add(indiceGroupe)) {
                    replisActuels.remove(indiceGroupe)
                }
                deriverRegroupe(tampon.instantane())
            }
        }
    }

    /**
     * Ouvre une exécution persistée EN LECTURE SEULE (ADR 0161.5) — le
     * tampon reçoit les lignes expurgées du registre, le live est retenu
     * jusqu'au retour au direct ; projection BRUT par défaut (le
     * regroupement reste disponible si les données d'événements ont été
     * persistées avec).
     */
    public fun ouvrirExecution(lignes: List<LigneSortie>, execution: ExecutionSortie?) {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                consultationActive = true
                _enConsultation.value = true
                etatConsultationInterne.value = execution?.etat
                viderEtatVivant()
                tampon.ajouterLot(lignes)
                rederriverComplete()
            }
        }
    }

    /** Quitte la consultation (le direct reprend vierge — l'exécution
     *  passée reste dans le registre). */
    public fun revenirAuDirect() {
        viewModelScope.launch(dispatchers.default) {
            verrou.withLock {
                consultationActive = false
                _enConsultation.value = false
                etatConsultationInterne.value = null
                viderEtatVivant()
            }
        }
    }

    /** L'état vivant remis à zéro (consultation, retour au direct). */
    private suspend fun viderEtatVivant() {
        tampon.vider()
        visibles = emptyList()
        rangees = emptyList()
        marquesErreurs = emptyList()
        marquesAvertissements = emptyList()
        classifications = ArrayList()
        cacheRangees.clear()
        replisActuels.clear()
        rechercheLecteur.effacer()
        _recherche.value = null
        _regroupement.value = null
        _marques.value = MarquesLecteur()
        _projection.value = ProjectionLecteur.VIDE
    }

    /** Classe TOUTE la fenêtre (une bascule = une passe — banc S5).
     *  GRADLE seul : les classifications portent la structure de tâches
     *  ; le Logcat (lot L5) n'en a pas — sa dérivation lit les lignes
     *  directement (une passe sur le contenu, sans machine à états). */
    private fun classifierTout() {
        if (famille != FamilleSorties.GRADLE) {
            classifications = ArrayList()
            return
        }
        val etat = tampon.instantane()
        var section = SectionFluxGradle.FLUX
        classifications = ArrayList(etat.lignes.size)
        etat.lignes.forEach { ligne ->
            val classification = RegroupementSorties.classifier(ligne, section)
            section = classification.section
            classifications.add(classification)
        }
    }

    /**
     * Maintient les classifications ALIGNÉES après un lot : la tête
     * tronquée quitte, les lignes nouvelles sont classées (chaque ligne
     * est analysée UNE fois — jamais de re-scan à chaque lot).
     */
    private fun maintenirClassifications(etat: EtatTamponSorties, tailleAvant: Int, troncature: Int) {
        if (famille != FamilleSorties.GRADLE) return
        var section = classifications.lastOrNull()?.section ?: SectionFluxGradle.FLUX
        val nouvelles = etat.lignes.subList((tailleAvant - troncature).coerceAtLeast(0), etat.lignes.size)
        classifications = ArrayList(classifications.drop(troncature))
        nouvelles.forEach { ligne ->
            val classification = RegroupementSorties.classifier(ligne, section)
            section = classification.section
            classifications.add(classification)
        }
    }

    /** La rangée de rendu d'une ligne (UNE construction par ligne — le
     *  cache est purgé quand il déborde de la fenêtre courante). */
    private fun rangeeEnCache(ligne: LigneSortie): RangeeSortie {
        if (cacheRangees.size > seuilCache()) {
            cacheRangees.clear()
        }
        return cacheRangees.getOrPut(ligne) { RangeeSortie.de(ligne) }
    }

    /** Le seuil du cache (1,5 × la fenêtre — une purge = un re-parse
     *  amorti, jamais une croissance sans borne). */
    private fun seuilCache(): Int = (flux.capaciteFenetre() * FACTEUR_SEUIL_CACHE) / DEMI_CACHE

    /** Publication des repères de la poignée (espaces RANGÉES — les
     *  correspondances de recherche y sont projetées : une correspondance
     *  masquée ne prend aucun repère) — portée par une propriété : le
     *  ViewModel garde une responsabilité unique à onze fonctions près. */
    private val publierMarques = {
        val rangeesCorrespondances = rechercheLecteur
            .etat()
            ?.correspondances
            ?.mapNotNull { position -> rangVisible(position) }
            ?: emptyList()
        _marques.value = MarquesLecteur(marquesErreurs, marquesAvertissements, rangeesCorrespondances)
    }

    /** Lancement interne de la recherche (verrou tenu). */
    private suspend fun definirRechercheInterne(requete: RequeteRecherche) {
        val etat = tampon.instantane()
        val rangee = rechercheLecteur.definir(
            requete,
            etat.lignes,
            etat.messagesPlies,
            etat.etiquettesPlies,
            rangVisible,
        )
        _recherche.value = rechercheLecteur.etat()
        rangee?.let { _allerA.tryEmit(it) }
        publierMarques()
    }

    /** L'index de RANGÉE d'une position de tampon, ou `null` si masquée
     *  (brut : l'index filtré — regroupé : la carte des rangées visibles,
     *  les lignes repliées ne répondent plus). */
    private var rangVisible: (Int) -> Int? = { position ->
        visibles.binarySearch(position).takeIf { it >= 0 }
    }

    /** Publication de la projection (compteurs d'honnêteté inclus). */
    private fun publierProjection(etat: EtatTamponSorties, delta: DeltaProjection?) {
        _projection.value = ProjectionLecteur(
            rangees = rangees.toList(),
            delta = delta,
            tailleTampon = etat.lignes.size,
            pertes = etat.pertes,
            masquees = etat.lignes.size - visibles.size,
            origineMs = etat.lignes.firstOrNull()?.horodatageMs ?: 0L,
        )
    }
}

/**
 * Les repères de la poignée de défilement (espaces RANGÉES) — erreurs
 * (rouge), avertissements (ambre) et correspondances de recherche (bleu)
 * du lecteur, calculés sur l'INDEX de projection, jamais sur les vues
 * (ADR 0159 décision 2).
 *
 * @property erreurs indices de rangées graduées ERREUR/ASSERT.
 * @property avertissements indices de rangées graduées AVERTISSEMENT.
 * @property correspondances indices de rangées des correspondances de
 *           recherche VISIBLES (les masquées ne prennent aucun repère).
 */
private const val FACTEUR_SEUIL_CACHE = 3
private const val DEMI_CACHE = 2

internal data class MarquesLecteur(
    val erreurs: List<Int> = emptyList(),
    val avertissements: List<Int> = emptyList(),
    val correspondances: List<Int> = emptyList(),
)

/**
 * Décale des indices après un retrait de tête : les tombées quittent,
 * les autres glissent (discipline commune aux repères).
 */
private fun decalerListe(indices: List<Int>, troncature: Int): List<Int> = indices.mapNotNull {
    (it - troncature).takeIf { decale ->
        decale >=
            0
    }
}

/** Les indices des RANGÉES LIGNE d'une catégorie (pure, paramétrée —
 *  les en-têtes regroupés ne prennent pas de repère de gravité : ils
 *  portent leurs propres compteurs). */
private inline fun rangeesMarquees(rangees: List<RangeeLecteur>, marquee: (RangeeSortie) -> Boolean): List<Int> =
    rangees.withIndex().mapNotNull { (indice, rangee) ->
        (rangee as? RangeeLecteur.Ligne)?.takeIf { marquee(it.rangee) }?.let { indice }
    }

/** Alias honnête du même calcul (le chemin chaud du brut et la
 *  dérivation regroupée partagent la même discipline). */
private inline fun marquesDesLignes(rangees: List<RangeeLecteur>, marquee: (RangeeSortie) -> Boolean): List<Int> =
    rangeesMarquees(rangees, marquee)

/**
 * Le SAUT d'erreur (spec SORTIES § 5.4, lot L5) — pur, testé : la cible
 * dans les repères d'erreurs (indices de RANGÉES de la projection
 * courante), [pas] = +1 suivant / −1 précédent depuis [rangDepart], le
 * parcours BOUCLE (un « suivant » depuis la dernière erreur revient à la
 * première — jamais un geste mort). `null` : aucun repère.
 */
internal fun cibleErreur(marques: List<Int>, pas: Int, rangDepart: Int): Int? {
    if (marques.isEmpty()) return null
    return if (pas >= 0) {
        marques.firstOrNull { it > rangDepart } ?: marques.first()
    } else {
        marques.lastOrNull { it < rangDepart } ?: marques.last()
    }
}

/** L'état de filtre d'UNE étiquette (le cycle des puces, spec SORTIES
 *  § 5.4, lot L5 : inclure / exclure / neutre). */
internal enum class EtatPuceEtiquette {
    /** Le filtre ne parle pas de cette étiquette. */
    NEUTRE,

    /** L'étiquette est ADMISE seule (etiquettesInclues). */
    INCLUSE,

    /** L'étiquette est MASQUÉE (etiquettesExclues). */
    EXCLUE,
}

/**
 * Une suggestion d'étiquette avec son compteur (spec SORTIES § 5.4, lot
 * L5) — le menu « # » du lecteur la liste, la puce cycle son état.
 *
 * @property etiquette le tag exact (sensible à la casse — les tags
 *           logcat le sont, ADR 0160).
 * @property compte les lignes de la fenêtre portant cette étiquette.
 * @property etat l'état de la puce dans le filtre COURANT (le menu dit
 *           la vérité du filtre).
 */
internal data class EtiquetteComptee(val etiquette: String, val compte: Int, val etat: EtatPuceEtiquette)

/** Les étiquettes proposées au menu (borné — le menu reste lisible). */
internal const val BORNE_ETIQUETTES = 24

/**
 * Le comptage des étiquettes d'une fenêtre (pur, testé) — un passage
 * sur les lignes, tri compte décroissant puis étiquette (déterministe),
 * borné à [BORNE_ETIQUETTES]. Les lignes SANS étiquette ne comptent pas
 * (jamais une puce « sans objet »).
 */
internal fun etiquettesCompteesInterne(etat: EtatTamponSorties, filtre: FiltreSorties): List<EtiquetteComptee> {
    val comptes = HashMap<String, Int>()
    etat.lignes.forEach { ligne ->
        ligne.etiquette?.let { etiquette ->
            comptes[etiquette] = (comptes[etiquette] ?: 0) + 1
        }
    }
    return comptes.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(BORNE_ETIQUETTES)
        .map { (etiquette, compte) ->
            EtiquetteComptee(
                etiquette = etiquette,
                compte = compte,
                etat = etatPuceEtiquette(filtre, etiquette),
            )
        }
}

/** L'état de puce d'une étiquette dans le filtre (pur — l'incluse
 *  PRÉVAUT si les deux ensembles la portaient, ce que le cycle ne
 *  produit jamais : les deux contraintes ne se composent pas). */
internal fun etatPuceEtiquette(filtre: FiltreSorties, etiquette: String): EtatPuceEtiquette = when {
    etiquette in filtre.etiquettesInclues -> EtatPuceEtiquette.INCLUSE
    etiquette in filtre.etiquettesExclues -> EtatPuceEtiquette.EXCLUE
    else -> EtatPuceEtiquette.NEUTRE
}

/**
 * Le cycle de la puce (pur, testé) : neutre → incluse → exclue →
 * neutre. Le reste du filtre est CONSERVÉ (copy — le niveau poussé par
 * le chrome, la plage de temps, la session survivent à la bascule).
 */
internal fun cyclerEtiquette(filtre: FiltreSorties, etiquette: String): FiltreSorties =
    when (etatPuceEtiquette(filtre, etiquette)) {
        EtatPuceEtiquette.NEUTRE -> filtre.copy(
            etiquettesInclues = filtre.etiquettesInclues + etiquette,
        )

        EtatPuceEtiquette.INCLUSE -> filtre.copy(
            etiquettesInclues = filtre.etiquettesInclues - etiquette,
            etiquettesExclues = filtre.etiquettesExclues + etiquette,
        )

        EtatPuceEtiquette.EXCLUE -> filtre.copy(
            etiquettesExclues = filtre.etiquettesExclues - etiquette,
        )
    }
