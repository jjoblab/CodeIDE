package jo.codeide.feature.editor

import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Contrôleur du rendu tooling de l'en-tête du panneau inférieur (v4,
 * correctif n°13 du prompt « tooling professionnel » ; §3.3 étape 5 :
 * en-tête ENRICHI) : ce rendu vivait DANS [EditorActivity] (1 800+
 * lignes) — il vit désormais ici, l'activité ne garde que le cycle de vie
 * et la collecte.
 *
 * Le rendu est piloté par [PresentationTooling.etatEntete] (présentateur
 * pur, §3.2) : pastille de canal colorée avec spinner animé en vol / coche
 * verte de succès / croix rouge d'échec, titre numéroté « étape n/N » en
 * vol de sync, sous-titre = étape courante + détail (octets, compteur),
 * chrono monospace en vol ou durée figée, bouton Arrêter pendant un
 * build, progression DÉTERMINÉE quand les octets totaux sont connus
 * (indéterminée sinon, pleine au succès), peek élargi pour l'en-tête.
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
    /** L'en-tête tooling a-t-il quelque chose à montrer ? */
    private var ligneActivee = false

    /** Ticker du chrono en vol (annulé au prochain rendre ou à la destruction). */
    private var travailMinuteur: Job? = null

    init {
        // Arrêt de l'activité tooling en cours (v0.32.5) : bouton de
        // l'en-tête — visible seulement pendant un build (la
        // synchronisation ne s'annule pas).
        liaison.boutonArreterTooling.setOnClickListener { surArret() }
    }

    /**
     * Rend l'état tooling : pastille de canal, titre numéroté, sous-titre
     * d'étape, chrono (en vol ou figé), arrêt, progression déterminée ou
     * indéterminée — le peek suit la présence de l'en-tête.
     *
     * v0.39.1 (correctif n°5) : quand `canal` est `null` (aucune activité
     * tooling n'a jamais démarré OU l'état a été réinitialisé), on RE-
     * REND un état REPOS propre (pastille masquée, titre et sous-titre
     * effacés) au lieu de laisser le RENDU PRÉCÉDENT en place — évite
     * qu'une pastille EN_VOL/SUCCES reste visible si l'état repasse à
     * `null` puis re-devient non-null (ré-ouverture d'espace, etc.).
     */
    fun rendre(etat: EtatGradle) {
        val entete = PresentationTooling.etatEntete(etat)
        val canal = etat.canalActif ?: etat.canalDernierResultat
        ligneActivee = canal != null
        if (canal != null) {
            rendrePastille(entete, canal)
            liaison.activiteTooling.text = entete.titre.resoudre(activite)
            rendreSousTitre(entete)
        } else {
            // v0.39.1 : pas d'état tooling — on RE-REND un en-tête VIDE
            // (pastille masquée, titres effacés) pour qu'aucun résidu d'un
            // build précédent ne reste affiché si la ligne redevient
            // visible (par exemple, à la ré-ouverture d'un espace dont
            // l'état a été nettoyé par `attacher()`).
            reinitialiserEntete()
        }
        rendreProgression(entete, etat)
        liaison.boutonArreterTooling.isVisible = entete.arret

        // Chrono : en vol il TICHE (demi-seconde), terminé il fige la
        // durée du résultat — jamais de temps figé qui ment.
        travailMinuteur?.cancel()
        travailMinuteur = null
        val depart = entete.chronoMs
        travailMinuteur = depart?.let(::lancerMinuteur)
        if (depart == null) {
            liaison.minuteurTooling.text = entete.dureeFigeeMs?.let(DureesLisibles::formater) ?: ""
        }

        // Le peek suit la présence de l'en-tête (visible = état courant du
        // fondu conservé — un panneau étendu n'a pas d'en-tête de toute
        // façon).
        liaison.ligneTooling.isVisible = ligneActivee && liaison.entetePanneau.isVisible
        majPeekPanneau()
    }

    /**
     * Réinitialise l'en-tête tooling à un état REPOS (v0.39.1, correctif
     * n°5) — pastille masquée, titres effacés, sous-titre masqué. Évite
     * les résidus visuels d'un build précédent quand le `canal` repasse
     * à `null` puis re-devient non-null.
     */
    private fun reinitialiserEntete() {
        liaison.spinnerCanalTooling.isVisible = false
        liaison.iconeCanalTooling.isVisible = false
        liaison.activiteTooling.text = ""
        liaison.sousTitreTooling.isVisible = false
        liaison.sousTitreTooling.text = ""
        liaison.minuteurTooling.text = ""
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

    // ---- Pastille de canal (§3.3) ---------------------------------------

    /** Pastille : fond teinté (canal harmonisé / succès / échec), icône
     *  blanche en repos (coche de succès, croix d'échec, glyphe du canal),
     *  SPINNER animé en vol. */
    private fun rendrePastille(
        entete: EtatEnteteTooling,
        canal: CanalTooling,
    ) {
        liaison.pastilleCanalTooling.backgroundTintList =
            ColorStateList.valueOf(couleurStatut(entete.statut, entete.couleur))
        val enVol = entete.statut == StatutEntete.EN_VOL
        liaison.spinnerCanalTooling.isVisible = enVol
        liaison.iconeCanalTooling.isVisible = !enVol
        if (!enVol) {
            val icone =
                when (entete.statut) {
                    StatutEntete.SUCCES -> jo.codeide.core.ui.R.drawable.ic_fait
                    StatutEntete.ECHOUE -> jo.codeide.core.ui.R.drawable.ic_fermer_onglet
                    StatutEntete.EN_VOL, StatutEntete.NEUTRE -> canal.icone
                }
            liaison.iconeCanalTooling.setImageResource(icone)
        }
    }

    /** Couleur de la pastille selon le statut visuel : canal harmonisé en
     *  vol/au repos, vert de succès, rouge d'échec (rôles sémantiques
     *  ADR 0059 — suivent les 8 palettes et la nuit). */
    private fun couleurStatut(
        statut: StatutEntete,
        couleurCanal: Int,
    ): Int =
        when (statut) {
            StatutEntete.SUCCES -> {
                MaterialColors.getColor(liaison.root, jo.codeide.core.ui.R.attr.colorSucces)
            }

            StatutEntete.ECHOUE -> {
                MaterialColors.getColor(liaison.root, androidx.appcompat.R.attr.colorError)
            }

            StatutEntete.EN_VOL, StatutEntete.NEUTRE -> {
                ThemeHarmonizer.harmoniserAvecPrimaire(activite, couleurCanal)
            }
        }

    /** Sous-titre (§3.3) : libellé de la phase courante combiné à son
     *  détail (« Phase — 42 Mo · 3 éléments »), ou le texte du repos
     *  (succès : « 48 tâches disponibles »). */
    private fun rendreSousTitre(entete: EtatEnteteTooling) {
        val texte =
            entete.libelleEtape?.let { libelle ->
                val phase = activite.getString(libelle)
                entete.sousTitre?.let { detail ->
                    activite.getString(
                        R.string.editor_tooling_entete_sous_titre,
                        phase,
                        detail.resoudre(activite),
                    )
                } ?: phase
            } ?: entete.sousTitre?.resoudre(activite)
        liaison.sousTitreTooling.isVisible = texte != null
        if (texte != null) {
            liaison.sousTitreTooling.text = texte
        }
    }

    // ---- Progression (§3.3) ---------------------------------------------

    /** Progression : DÉTERMINÉE (octets reçus/total, pleine au succès)
     *  quand elle existe, indéterminée pendant une activité en vol —
     *  couleur de canal harmonisée, vert au succès. Material exige de
     *  MASQUER l'indicateur pour basculer de mode. */
    private fun rendreProgression(
        entete: EtatEnteteTooling,
        etat: EtatGradle,
    ) {
        val indicateur = liaison.progressionTooling
        val visible = etat.activiteEnCours || entete.statut == StatutEntete.SUCCES
        val couleurBarre = couleurStatut(entete.statut, entete.couleur)
        indicateur.isVisible = false
        val progression = entete.progression
        if (progression != null) {
            if (indicateur.isIndeterminate) {
                indicateur.isIndeterminate = false
            }
            indicateur.setProgressCompat((progression.coerceIn(0f, 1f) * POURCENT_MAX).roundToInt(), true)
        } else {
            indicateur.isIndeterminate = true
        }
        indicateur.setIndicatorColor(couleurBarre)
        indicateur.isVisible = visible
    }

    // ---- Chrono ----------------------------------------------------------

    /**
     * Ticker du chrono en vol (500 ms — le centième de seconde est du
     * bruit sur un build). Correctif n°12 : le ticker ne tourne QUE
     * STARTED (`repeatOnLifecycle`) et lit l'HORLOGE INJECTÉE.
     */
    private fun lancerMinuteur(depart: Long): Job? =
        activite.lifecycleScope.launch {
            activite.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val ecoule = (horloge.nowMillis() - depart).coerceAtLeast(0L)
                    liaison.minuteurTooling.text = DureesLisibles.formater(ecoule)
                    delay(PERIODE_MINUTEUR_MS)
                }
            }
        }

    /** Peek du panneau : en-tête seul, + en-tête tooling enrichi (et
     *  progression) quand une activité s'y affiche — l'activité tooling
     *  reste visible même replié (v0.32.5). */
    private fun majPeekPanneau() {
        val peekReposPx = activite.resources.getDimensionPixelSize(R.dimen.editor_panneau_replie)
        var peek = peekReposPx
        if (ligneActivee) {
            peek += activite.resources.getDimensionPixelSize(R.dimen.editor_entete_tooling_hauteur)
            if (liaison.progressionTooling.isVisible) {
                peek += (HAUTEUR_PROGRESSION_TOOLING_DP * activite.resources.displayMetrics.density).toInt()
            }
        }
        comportementPanneau.peekHeight = peek
    }

    private companion object {
        /** Période du chrono de l'en-tête tooling (500 ms). */
        const val PERIODE_MINUTEUR_MS = 500L

        /** Hauteur de la bande de progression tooling (dp). */
        const val HAUTEUR_PROGRESSION_TOOLING_DP = 4

        /** Alpha sous lequel l'en-tête fondu passe INVISIBLE. */
        const val SEUIL_FONDU_VISIBLE = 0.02f

        /** Progression maximale en pourcentage (determinée 0..1 → 0..100). */
        const val POURCENT_MAX = 100f
    }
}
