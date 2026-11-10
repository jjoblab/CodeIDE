package jo.codeide.feature.sorties

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatTamponSorties
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.LotTampon
import jo.codeide.core.domain.ProjectionSorties
import jo.codeide.core.domain.RequeteRecherche
import jo.codeide.core.domain.TamponSorties
import jo.codeide.core.domain.TimeProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * @property rangées rangées de rendu, alignées sur l'index de projection.
 * @property delta delta d'application, ou `null` pour tout relier.
 * @property tailleTampon lignes présentes dans la fenêtre du tampon.
 * @property pertes lignes perdues depuis la dernière vidange (bandeau).
 * @property masquees lignes masquées par le filtre (bandeau — les lignes
 *           restent dans le tampon, spec SORTIES § 3.5).
 * @property origineMs horodatage de la première ligne de la fenêtre
 *           (origine de l'horodatage relatif).
 */
internal data class ProjectionLecteur(
    val rangees: List<RangeeSortie>,
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
 * Une instance par famille de source (la source injectée porte sa
 * capacité de fenêtre) ; le panneau compact et l'éditeur dédié liront le
 * MÊME état (une seule source d'état — critère § 10.1.11, lot L5).
 *
 * @property flux la source de lots (source factice au lot L2 — banc
 *           SORTIES_BANC_MESURE ; le vrai flux tooling arrive en L3).
 * @property dispatchers dispatchers injectés (règle 5).
 * @property horloge horloge injectée pour l'horodatage à l'ingestion.
 */
@HiltViewModel
internal class LecteurSortiesViewModel
@Inject
constructor(
    private val flux: FluxSorties,
    private val dispatchers: DispatcherProvider,
    horloge: TimeProvider,
) : ViewModel() {
    private val tampon = TamponSorties(flux.capaciteFenetre(), horloge)
    private val verrou = Mutex()

    private var filtreActuel: FiltreSorties = FiltreSorties()
    private var visibles: List<Int> = emptyList()
    private var rangees: List<RangeeSortie> = emptyList()
    private var marquesErreurs: List<Int> = emptyList()
    private var marquesAvertissements: List<Int> = emptyList()
    private var enPause = false
    private val rechercheLecteur = RechercheLecteur()

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
            // Rangées : les survivantes sont RÉUTILISÉES (aucune re-parse),
            // seules les nouvelles sont construites.
            val nouvelles = apres.visibles.drop(survivants)
            val nouvellesRangees = nouvelles.map { indice -> RangeeSortie.de(etat.lignes[indice]) }
            rangees = rangees.drop(tombes) + nouvellesRangees
            marquesErreurs = decalerListe(marquesErreurs, tombes) +
                rangeesMarquees(nouvellesRangees) { it.estErreur }.map { survivants + it }
            marquesAvertissements = decalerListe(marquesAvertissements, tombes) +
                rangeesMarquees(nouvellesRangees) { it.estAvertissement }.map { survivants + it }
            publierMarques()
            publierProjection(etat, DeltaProjection(tombes, nouvellesRangees.size))
        }
    }

    /**
     * Re-dérivation COMPLÈTE (reprise de pause, changement de filtre) —
     * delta `null` : le lecteur relie tout EN UNE fois ; la recherche
     * vivante est rejouée sur le tampon courant.
     */
    private suspend fun rederriverComplete() {
        val etat = tampon.instantane()
        val apres = ProjectionSorties.deriver(
            etat.lignes,
            etat.messagesPlies,
            etat.etiquettesPlies,
            filtreActuel,
        )
        visibles = apres.visibles
        rangees = visibles.map { indice -> RangeeSortie.de(etat.lignes[indice]) }
        marquesErreurs = rangees.withIndex().filter { it.value.estErreur }.map { it.index }
        marquesAvertissements = rangees.withIndex().filter { it.value.estAvertissement }.map { it.index }
        publierMarques()
        val requeteActive = _recherche.value?.requete
        if (requeteActive != null) definirRechercheInterne(requeteActive)
        publierProjection(etat, delta = null)
    }

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

    /** L'index de RANGÉE d'une position de tampon, ou `null` si masquée. */
    private val rangVisible: (Int) -> Int? = { position ->
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

/** Les indices des rangees d'une catégorie (pure, paramétrée). */
private inline fun rangeesMarquees(rangees: List<RangeeSortie>, marquee: (RangeeSortie) -> Boolean): List<Int> =
    rangees.withIndex().filter { marquee(it.value) }.map { it.index }
