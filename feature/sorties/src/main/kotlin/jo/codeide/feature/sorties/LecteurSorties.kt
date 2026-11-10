package jo.codeide.feature.sorties

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Le contrat du moteur de lecture du Centre de sorties (ADR 0159
 * « Conséquences » : « L2 implémente le moteur retenu derrière le contrat
 * `LecteurSorties` », lot L2) — l'interface que les surfaces consomment :
 * l'éditeur dédié (onglet + plein écran, L5a), le panneau compact
 * rebranché (L5b) et le volet tablette (L5c) montrent le MÊME composant —
 * jamais un moteur par surface (une seule source d'état, critère
 * § 10.1.11).
 *
 * Le moteur actuel est [LecteurSortiesRecycler] (recommandation ADR 0159
 * décision 2 — à confirmer par les PV du banc) ; si la mesure invalide
 * le choix, le contrat ne change pas — seul le moteur change (ADR 0159 :
 * c'est le but du banc).
 */
internal interface LecteurSorties {
    /** La racine du composant (liste + poignée), à poser dans la surface. */
    val racine: View

    /** Publie la projection (delta incrémental ou re-liaison complète). */
    fun afficherProjection(projection: ProjectionLecteur)

    /** Applique les réglages de rendu (bascules, zoom, origine). */
    fun definirReglages(options: OptionsRenduRangee, origineMs: Long)

    /** Teinte la sélection. */
    fun definirSelection(plage: IntRange?)

    /** Publie les repères de défilement (normalisés par l'index). */
    fun definirMarques(marques: MarquesLecteur)

    /** Joint une rangee (saut de recherche, retour au bas). */
    fun allerALaRangee(indice: Int)

    /** Le composant suit-il actuellement le bas ? (bouton « ⇣ suivre »). */
    val suitLeBas: Boolean

    /** Force le suivi du bas (bouton « ⇣ suivre »). */
    fun reprendreSuivi()

    /** La première rangee visible (position retenue en sortie de vue). */
    fun premiereRangeeVisible(): Int

    /** La position visible à retrouver à la prochaine re-liaison complète
     *  (rotation — le fragment la pose en entrée de vue). */
    var positionRetenue: Int?
}

/**
 * LE moteur de lecture retenu (recommandation ADR 0159 décision 2, lot
 * L2) : RecyclerView virtualisé sur le modèle de rangées, SANS DiffUtil —
 * append par lot, notifications bornées, hauteur variable (retour à la
 * ligne), suivi du bas HONNÊTE (le suivi s'arrête dès que l'utilisateur
 * remonte et le bouton « ⇣ suivre » ré-apparaît — comportement de
 * `PanneauConsoleFragment` généralisé, spec SORTIES § 3.2), zoom de
 * police au pincement (bornes 10–20 sp, spec § 3.7), repères de
 * défilement calculés sur l'INDEX, jamais sur les vues (ADR 0159
 * décision 2).
 *
 * Le suivi du bas : quand un lot arrive et que l'utilisateur était au
 * bas, la liste défile ; dès qu'il n'y était plus, le suivi s'arrête
 * ([surSuiviChange] prévient la surface pour afficher le bouton). Un
 * build fini ne défile plus — plus aucun lot n'arrive (honnêteté § 3.2).
 *
 * @param liste le RecyclerView hôte.
 * @param poignee la poignée de défilement à côté de la liste.
 * @param adapter l'adaptateur des rangees.
 * @param surSuiviChange prévenu quand le suivi du bas change.
 * @param surZoomPolice prévenu du zoom pincé (taille pliée 10–20 sp).
 */
internal class LecteurSortiesRecycler(
    private val liste: RecyclerView,
    private val poignee: PoigneeDefilement,
    private val adapter: LignesSortiesAdapter,
    private val surSuiviChange: (Boolean) -> Unit,
    private val surZoomPolice: (Int) -> Unit,
) : LecteurSorties {
    private val gestionnaire = LinearLayoutManager(liste.context)
    private var suiviActif = true

    override var positionRetenue: Int? = null

    override val racine: View
        get() = liste

    override val suitLeBas: Boolean
        get() = suiviActif

    init {
        liste.layoutManager = gestionnaire
        liste.adapter = adapter
        liste.addOnScrollListener(EcouteurFenetre())
        liste.addOnItemTouchListener(GesteZoom())
    }

    override fun afficherProjection(projection: ProjectionLecteur) {
        adapter.publierProjection(projection.rangees, projection.delta)
        val taille = adapter.itemCount
        if (taille == 0) {
            // Tampon vide : rien à suivre, la fenêtre est pleine page.
            poseSuivi(true)
            poignee.definirFenetre(0f, 1f)
            return
        }
        if (suiviActif && projection.delta != null) {
            liste.scrollToPosition(taille - 1)
        } else if (projection.delta == null) {
            // Re-liaison complète (reprise, filtre, rotation) : retrouver
            // la position retenue si elle est encore dans la fenêtre.
            val position = positionRetenue
            if (position != null && position < taille) {
                liste.scrollToPosition(position)
            }
        }
        majFenetre()
    }

    override fun definirReglages(options: OptionsRenduRangee, origineMs: Long) {
        adapter.definirOptions(options, origineMs)
    }

    override fun definirSelection(plage: IntRange?) {
        adapter.definirSelection(plage)
    }

    /**
     * Les repères arrivent en ESPACES RANGÉES (l'index de projection du
     * ViewModel) ; le lecteur les NORMALISE (0..1) pour la poignée —
     * une liste virtualisée ne connaît pas les hauteurs réelles de
     * toutes ses rangées, l'index est la seule vérité honnête.
     */
    override fun definirMarques(marques: MarquesLecteur) {
        val taille = adapter.itemCount.coerceAtLeast(1)
        poignee.definirMarques(
            erreurs = marques.erreurs.map { it / taille.toFloat() },
            avertissements = marques.avertissements.map { it / taille.toFloat() },
            correspondances = marques.correspondances.map { it / taille.toFloat() },
        )
    }

    override fun allerALaRangee(indice: Int) {
        if (adapter.itemCount == 0) return
        liste.scrollToPosition(indice.coerceIn(0, adapter.itemCount - 1))
    }

    override fun reprendreSuivi() {
        if (adapter.itemCount == 0) return
        liste.scrollToPosition(adapter.itemCount - 1)
        poseSuivi(true)
    }

    override fun premiereRangeeVisible(): Int = gestionnaire.findFirstVisibleItemPosition()

    /** Pose l'état du suivi et prévient la surface si changé. */
    private fun poseSuivi(suit: Boolean) {
        if (suiviActif != suit) {
            suiviActif = suit
            surSuiviChange(suit)
        }
    }

    /** La fenêtre visible → poignée (positions normalisées). */
    private fun majFenetre() {
        val taille = adapter.itemCount
        if (taille == 0) {
            poignee.definirFenetre(0f, 1f)
            return
        }
        val premier = gestionnaire.findFirstVisibleItemPosition()
        val dernier = gestionnaire.findLastVisibleItemPosition()
        poignee.definirFenetre(
            premier / taille.toFloat(),
            (dernier + 1) / taille.toFloat(),
        )
    }

    /** Écouteur de défilement : fenêtre de la poignée + suivi honnête. */
    private inner class EcouteurFenetre : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            majFenetre()
            val taille = adapter.itemCount
            val dernier = gestionnaire.findLastCompletelyVisibleItemPosition()
            val auBas = taille == 0 || dernier >= taille - 1
            // Le suivi du bas est HONNÊTE : actif quand on est au bas ;
            // toute remontée le coupe (spec SORTIES § 3.2). Un défilement
            // vers le bas NE réactive PAS le suivi tant que le bas n'est
            // pas atteint — le bouton « ⇣ suivre » reste la solution.
            poseSuivi(auBas)
        }
    }

    /** Le zoom au pincement (bornes gérées par [OptionsRenduRangee]). */
    private inner class GesteZoom : RecyclerView.SimpleOnItemTouchListener() {
        private val detecteur = ScaleGestureDetector(
            liste.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detecteur: ScaleGestureDetector): Boolean {
                    val taille = (adapter.optionsCourantes.taillePoliceSp * detecteur.scaleFactor)
                        .roundToInt()
                        .coerceIn(OptionsRenduRangee.TAILLE_MIN_SP, OptionsRenduRangee.TAILLE_MAX_SP)
                    if (taille != adapter.optionsCourantes.taillePoliceSp) {
                        surZoomPolice(taille)
                    }
                    return true
                }
            },
        )

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            detecteur.onTouchEvent(e)
            return detecteur.isInProgress
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            detecteur.onTouchEvent(e)
        }
    }
}
