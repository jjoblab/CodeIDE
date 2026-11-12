package jo.codeide.feature.sorties

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
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
 * Retour propriétaire du 2026-11-12 (« ça donne l'impression de
 * secouer ») : le suivi n'appelle PLUS `scrollToPosition` à CHAQUE lot
 * — un saut sec par lot (jusqu'à 20/s, plusieurs hauteurs d'écran avec
 * des lots de 256 lignes) relisait comme un tremblement. Désormais :
 *  - UN SEUL lissage par passe de boucle (les lots qui s'empilent dans
 *    la même image partagent la même cible) ;
 *  - `LinearSmoothScroller` en `SNAP_TO_END` — le BAS de la dernière
 *    rangée s'aligne au BAS du viewport (exact pour une rangée plus
 *    haute que l'écran), le défilement est continu, jamais un
 *    téléportage ;
 *  - la durée est plafonnée (le lissage ne peut pas prendre de retard
 *    sur le flux) ;
 *  - la détection honnête « au bas » est suspendue pendant le lissage
 *    programmatique (un défilement de suivi n'est pas une remontée
 *    utilisateur — le geste utilisateur l'annule et reprend ses
 *    droits).
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

    /** Un lissage de suivi est-il déjà en attente pour cette passe de
     *  boucle ? (coalescence — retour 2026-11-12.) */
    private var lissageEnAttente = false

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
        when {
            // Lot vivant (brut) : le suivi se déplace LISSÉ vers le bas —
            // jamais un saut sec par lot (retour 2026-11-12).
            suiviActif && projection.delta != null -> suivreLeBasLisse()

            // Re-liaison complète PENDANT le suivi (filtre en vol,
            // reprise de pause, bascule regroupée) : la fenêtre reste au
            // bas — retrouver une ANCIENNE position n'aurait pas de sens.
            suiviActif -> suivreLeBasLisse()

            // Re-liaison complète hors suivi (rotation, attachement) :
            // retrouver la position retenue si elle est encore dans la
            // fenêtre — UNE SEULE FOIS (la position est CONSOMMÉE : le
            // mode regroupé re-derive à chaque lot, un rappel en boucle
            // ramenait la liste en arrière — retour 2026-11-12).
            else -> {
                val position = positionRetenue
                if (position != null && position < taille) {
                    liste.scrollToPosition(position)
                }
                positionRetenue = null
            }
        }
        majFenetre()
    }

    /**
     * Le lissage du suivi du bas (retour propriétaire du 2026-11-12) :
     * UN `LinearSmoothScroller` par passe de boucle (coalescence par
     * [lissageEnAttente]), `SNAP_TO_END` — le bas de la dernière rangée
     * contre le bas du viewport — et une durée PLAFONNÉE : un flux rapide
     * (lot de 256 lignes toutes les 50 ms) déroule CONTINU au lieu de
     * téléporter l'écran vingt fois par seconde.
     */
    private fun suivreLeBasLisse() {
        if (lissageEnAttente) return
        lissageEnAttente = true
        liste.post {
            lissageEnAttente = false
            if (!suiviActif || adapter.itemCount == 0) return@post
            val lisseur =
                object : LinearSmoothScroller(liste.context) {
                    override fun getVerticalSnapPreference(): Int = SNAP_TO_END

                    override fun calculateTimeForScrolling(dx: Int): Int =
                        super.calculateTimeForScrolling(dx).coerceAtMost(DUREE_MAX_SUIVI_MS)
                }
            lisseur.targetPosition = adapter.itemCount - 1
            gestionnaire.startSmoothScroll(lisseur)
        }
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
            // Retour 2026-11-12 : un lissage PROGRAMMATIQUE de suivi ne
            // coupe JAMAIS le suivi — en plein vol, la dernière rangée
            // n'est pas encore complètement visible, la détection
            // « au bas » clignotait et le bouton « ⇣ suivre » pulserait.
            // Le geste utilisateur ANNULE le lissage : les événements
            // suivants viennent du doigt, la détection reprend ses
            // droits.
            if (gestionnaire.isSmoothScrolling) return
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

    private companion object {
        /**
         * Durée MAXIMALE d'un lissage de suivi (retour 2026-11-12) : au
         * rythme des lots (50 ms), un lissage plus long prendrait du
         * retard sur le flux — le plafond garde le défilement CONTINU
         * sans jamais décrocher du bas.
         */
        const val DUREE_MAX_SUIVI_MS = 120
    }
}
