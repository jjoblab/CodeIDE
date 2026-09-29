package jo.codeide.feature.editor

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Contrôleur du rendu tooling de l'en-tête du panneau inférieur (v4,
 * correctif n°13 du prompt « tooling professionnel ») : ce rendu vivait
 * DANS [EditorActivity] (1 800+ lignes) — il vit désormais ici, l'activité
 * ne garde que le cycle de vie et la collecte.
 *
 * Le rendu est piloté par [PresentationTooling.etatEntete] (présentateur
 * pur, §3.2) : titre par canal, sous-titre d'étape (v4), chrono en vol
 * (ticker démarré/arrêté par le contrôleur) ou durée figée, progression
 * indéterminée (la forme DÉTERMINÉE attend l'étape UI 5), bouton Arrêter
 * pendant un build, peek élargi pour la ligne.
 *
 * @param activite hôte (contexte de rendu + portée de cycle de vie).
 * @param liaison liaison de l'espace de travail (vues de l'en-tête).
 * @param comportementPanneau comportement du sheet (peek ajusté — seul
 *        `peekHeight` est piloté, la vue reste à l'activité).
 * @param horloge horloge injectée (correctif n°12 : jamais l'horloge directe).
 * @param surArret action d'annulation du build (l'activité relaie le
 *        ViewModel — le contrôleur ne connaît ni ViewModel ni actions).
 */
internal class PanneauToolingController(
    private val activite: AppCompatActivity,
    private val liaison: ActivityEditorBinding,
    private val comportementPanneau: BottomSheetBehavior<*>,
    private val horloge: TimeProvider,
    private val surArret: () -> Unit,
) {
    /** La ligne tooling a-t-elle quelque chose à montrer ? */
    private var ligneActivee = false

    /** Ticker du chrono en vol (annulé au prochain rendre ou à la destruction). */
    private var travailMinuteur: Job? = null

    init {
        // Arrêt de l'activité tooling en cours (v0.32.5) : bouton de la
        // ligne d'activité — visible seulement pendant un build (la
        // synchronisation ne s'annule pas).
        liaison.boutonArreterTooling.setOnClickListener { surArret() }
    }

    /**
     * Rend l'état tooling : canal, activité, chrono (en vol ou figé),
     * arrêt, progression — le peek suit la présence de la ligne.
     */
    fun rendre(etat: EtatGradle) {
        val entete = PresentationTooling.etatEntete(etat)
        ligneActivee = etat.canalActif ?: etat.canalDernierResultat != null
        if (ligneActivee) {
            liaison.iconeCanalTooling.setImageResource(
                (etat.canalActif ?: etat.canalDernierResultat)!!.icone,
            )
            // Couleur de marque du canal : harmonisée avec le primaire du
            // thème courant (ADR 0059).
            liaison.iconeCanalTooling.setColorFilter(
                ThemeHarmonizer.harmoniserAvecPrimaire(activite, entete.couleur),
            )
            liaison.activiteTooling.text = entete.titre.resoudre(activite)
        }
        liaison.progressionTooling.isVisible = etat.activiteEnCours
        liaison.boutonArreterTooling.isVisible = entete.arret

        // Chrono : en vol il TICHE (demi-seconde), terminé il fige la
        // durée du résultat — jamais de temps figé qui ment.
        travailMinuteur?.cancel()
        travailMinuteur = null
        val depart = entete.chronoMs
        if (depart != null) {
            travailMinuteur = lancerMinuteur { majTexteMinuteur(depart) }
        } else {
            liaison.minuteurTooling.text = entete.dureeFigeeMs?.let(DureesLisibles::formater) ?: ""
        }

        // Le peek suit la présence de la ligne (visible = état courant du
        // fondu conservé — un panneau étendu n'a pas d'en-tête de toute
        // façon).
        liaison.ligneTooling.isVisible = ligneActivee && liaison.entetePanneau.isVisible
        majPeekPanneau()
    }

    /** Le fondu de l'en-tête réapplique la visibilité de la ligne. */
    fun appliquerFondu(alpha: Float) {
        liaison.ligneTooling.isVisible = alpha > SEUIL_FONDU_VISIBLE && ligneActivee
    }

    /** Arrêt propre du ticker (destruction de la vue de l'activité). */
    fun detruire() {
        travailMinuteur?.cancel()
        travailMinuteur = null
    }

    /**
     * Ticker du chrono en vol (500 ms — le centième de seconde est du
     * bruit sur un build). Correctif n°12 : le ticker ne tourne QUE
     * STARTED (`repeatOnLifecycle`) et lit l'HORLOGE INJECTÉE.
     */
    private fun lancerMinuteur(texte: () -> Unit): Job? =
        activite.lifecycleScope.launch {
            activite.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    texte()
                    delay(PERIODE_MINUTEUR_MS)
                }
            }
        }

    /** Texte du chrono : temps écoulé depuis [depart] (ms de l'horloge injectée). */
    private fun majTexteMinuteur(depart: Long) {
        val maintenant = horloge.nowMillis()
        liaison.minuteurTooling.text =
            DureesLisibles.formater((maintenant - depart).coerceAtLeast(0L))
    }

    /** Peek du panneau : en-tête seul, + ligne tooling (et progression)
     *  quand une activité s'y affiche — l'activité tooling reste
     *  visible même repli (v0.32.5). */
    private fun majPeekPanneau() {
        val peekReposPx = activite.resources.getDimensionPixelSize(R.dimen.editor_panneau_replie)
        var peek = peekReposPx
        if (ligneActivee) {
            peek += activite.resources.getDimensionPixelSize(R.dimen.editor_ligne_tooling_hauteur)
            if (liaison.progressionTooling.isVisible) {
                peek += (HAUTEUR_PROGRESSION_TOOLING_DP * activite.resources.displayMetrics.density).toInt()
            }
        }
        comportementPanneau.peekHeight = peek
    }

    private companion object {
        /** Période du chrono de la ligne tooling (500 ms). */
        const val PERIODE_MINUTEUR_MS = 500L

        /** Hauteur de la bande de progression tooling (dp). */
        const val HAUTEUR_PROGRESSION_TOOLING_DP = 4

        /** Alpha sous lequel l'en-tête fondu passe INVISIBLE. */
        const val SEUIL_FONDU_VISIBLE = 0.02f
    }
}
