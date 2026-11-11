package jo.codeide.feature.editor

import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
 * Contrôleur du rendu tooling de l'en-tête UNIFIÉ du panneau inférieur
 * (v4, correctif n°13 ; en-tête unifié v2 — maquette sorties) : ce rendu
 * vivait DANS [EditorActivity] (1 800+ lignes) — il vit désormais ici,
 * l'activité ne garde que le cycle de vie et la collecte.
 *
 * En-tête unifié v2 : l'INDICATEUR COMPACT UNIQUE du rang 1 porte TOUT
 * le retour tooling (l'ancienne pile — ligne enrichie 34 dp sur Console,
 * indicateur compact n°1 dans l'ancienne première section, indicateur
 * compact n°2 du rang des onglets — est retirée) : pastille de canal
 * colorée avec spinner animé en vol / coche verte de succès / croix
 * rouge d'échec, titre (numéroté « étape n/N » en vol de sync),
 * sous-titre d'étape + détail (octets, compteur) — ou la note
 * « Sync non annulable » qui explique l'absence du bouton Arrêter
 * (INC-12) —, chrono monospace en vol ou durée figée, bouton Arrêter
 * pendant un build, progression DÉTERMINÉE quand les octets totaux
 * sont connus (indéterminée sinon, pleine au succès). La bande vit
 * sous le rang 1 pour TOUS les onglets ; repliée, elle passe sous le
 * peek de 48 dp — le peek ne bouge plus jamais (INC-18).
 *
 * Le rendu est piloté par [PresentationTooling.etatEntete] (présentateur
 * pur, §3.2 — INC-02 : une seule dérivation, quel que soit l'onglet ou
 * l'état du sheet).
 *
 * Exemption detekt ciblée (règle 16, v0.80.1) : `TooManyFunctions` —
 * une fonction par SECTION de l'indicateur (pastille, sous-titre,
 * progression, chrono) ; les regrouper masquerait la structure du §3.3
 * du prompt tooling.
 *
 * @param activite hôte (contexte de rendu + portée de cycle de vie).
 * @param liaison liaison de l'espace de travail (vues du rang 1).
 * @param horloge horloge injectée (correctif n°12 : jamais l'horloge directe).
 * @param surArret action d'annulation du build (l'activité relaie le
 *        ViewModel — le contrôleur ne connaît ni ViewModel ni actions).
 */
@Suppress("TooManyFunctions")
internal class PanneauToolingController(
    private val activite: AppCompatActivity,
    private val liaison: ActivityEditorBinding,
    private val horloge: TimeProvider,
    private val surArret: () -> Unit,
) {
    /** L'indicateur compact a-t-il quelque chose à montrer ? */
    private var indicateurActivee = false

    /** Ticker du chrono en vol (annulé au prochain rendre ou à la destruction). */
    private var travailMinuteur: Job? = null

    /** Dernier état d'en-tête rendu (INC-14 : dédoublonnage STRUCTUREL —
     *  la collecte de l'état Gradle ENTIER re-rend l'en-tête à chaque
     *  émission ; un état identique ne refait plus le travail). */
    private var dernierEntete: EtatEnteteTooling? = null

    /** Dernier canal rendu (complète la clé de dédoublonnage : l'indicateur
     *  actif dépend du canal, pas seulement de l'en-tête). */
    private var dernierCanal: CanalTooling? = null

    /** Dernier départ de chrono rendu (INC-14 : le ticker n'est ANNULÉ et
     *  relancé que si le départ CHANGE — plus de coroutine recréée à
     *  chaque émission d'un téléchargement). */
    private var dernierDepartChrono: Long? = null

    /** Dernière progression DÉTERMINÉE appliquée à la barre (INC-14). */
    private var derniereProgressionRendue: Float? = null

    /** Instant de la dernière mise à jour de progression (INC-14 : bornée
     *  à ~10/s — la rafale des événements de téléchargement ne repeint
     *  plus le fil principal pour chaque octet). */
    private var derniereMajProgressionMs: Long = 0

    /** Rendus EFFECTIFS de l'en-tête (INC-14 : observabilité test — une
     *  rafale d'états identiques ne doit produire qu'UN rendu). */
    var nbRendusEntete: Int = 0
        private set

    /** Relances du ticker du chrono (INC-14 : observabilité test — le
     *  chrono n'est relancé qu'au CHANGEMENT de départ). */
    var nbRelancesChrono: Int = 0
        private set

    /** Mises à jour EFFECTIVES de la valeur de progression (INC-14 :
     *  observabilité test — bornées à ~10/s, bornes 0/1 toujours). */
    var nbMajProgression: Int = 0
        private set

    /** Une activité tourne-t-elle (INC-13 : alimente la visibilité de la
     *  bande avec le flash de verdict, indépendamment du rendu). */
    private var activiteCourante = false

    /** Flash de verdict en cours (INC-13 : la bande s'allume ~1,5 s au
     *  verdict terminal — succès OU échec, symétrique — puis se masque,
     *  plus de bande verte pleine permanente au repos). */
    private var flashVerdict = false

    /** Travail du masquage du flash (annulé au verdict suivant ou au
     *  redémarrage d'une activité). */
    private var travailFlash: Job? = null

    /** Dernier verdict flaché (statut + durée) : un NOUVEAU verdict
     *  re-flashe, une ré-émission identique non. */
    private var dernierStatutFlashe: StatutEntete? = null
    private var derniereDureeFlashee: Long? = null

    init {
        // Arrêt de l'activité tooling en cours (v0.32.5 ; INC-11 : UNE
        // seule action, un seul libellé — le bouton de l'indicateur
        // compact du rang 1 et celui de la console y délègent tous ;
        // visible seulement pendant un build, la synchronisation ne
        // s'annule pas — INC-12 : le sous-titre dit pourquoi).
        liaison.indicateurCompactEntete.boutonArreterCompact.setOnClickListener { surArret() }
    }

    /**
     * Rend l'état tooling : pastille de canal, titre numéroté, sous-titre
     * d'étape (ou note « Sync non annulable »), chrono (en vol ou figé),
     * arrêt, progression déterminée ou indéterminée — dans l'indicateur
     * compact UNIQUE du rang 1, quel que soit l'onglet.
     *
     * v0.39.1 (correctif n°5) : quand `canal` est `null` (aucune activité
     * tooling n'a jamais démarré OU l'état a été réinitialisé), on RE-
     * REND un état REPOS propre (pastille masquée, titres effacés) au
     * lieu de laisser le RENDU PRÉCÉDENT en place — évite qu'une pastille
     * EN_VOL/SUCCES reste visible si l'état repasse à `null` puis
     * re-devient non-null (ré-ouverture d'espace, etc.).
     */
    fun rendre(etat: EtatGradle) {
        val entete = PresentationTooling.etatEntete(etat)
        val canal = etat.canalActif ?: etat.canalDernierResultat
        // INC-14 : un en-tête structurellement IDENTIQUE au dernier rendu
        // ne refait AUCUN travail — la collecte de l'état Gradle ENTIER
        // (téléchargements, lignes de build, étapes) émettait à chaque
        // événement : chaque appel relançait la coroutine du chrono,
        // réaffectait les visibilités et rappelait setProgressCompat
        // (animate = true) — charge inutile du fil principal et risque de
        // scintillement de la bande.
        if (entete == dernierEntete && canal == dernierCanal) {
            return
        }
        dernierEntete = entete
        dernierCanal = canal
        nbRendusEntete++
        indicateurActivee = canal != null
        activiteCourante = etat.canalActif != null

        // INC-13 : flash SYMÉTRIQUE au verdict terminal puis masquage.
        majFlashVerdict(entete)

        if (canal != null) {
            rendreIndicateurCompact(entete, canal)
        } else {
            // v0.39.1 : pas d'état tooling — on RE-REND un indicateur VIDE
            // (pastille masquée, titres effacés) pour qu'aucun résidu d'un
            // build précédent ne reste affiché si le rang redevient
            // informatif (par exemple, à la ré-ouverture d'un espace dont
            // l'état a été nettoyé par `attacher()`).
            reinitialiserIndicateur()
        }
        rendreProgression(entete)
        // La visibilité effective suit le flash calculé ci-dessus —
        // rendreProgression a MASQUÉ la bande pour poser son mode, c'est
        // ici qu'elle reprend sa place (INC-13).
        majVisibiliteBande()
        liaison.indicateurCompactEntete.boutonArreterCompact.isVisible = entete.arret
        // Chrono : en vol il TICHE (demi-seconde), terminé il fige la
        // durée du résultat — jamais de temps figé qui ment. INC-14 : le
        // ticker n'est annulé/relancé que si le DÉPART change.
        val depart = entete.chronoMs
        if (depart != dernierDepartChrono) {
            travailMinuteur?.cancel()
            travailMinuteur = null
            travailMinuteur = depart?.let(::lancerMinuteur)
            dernierDepartChrono = depart
            if (depart != null) {
                nbRelancesChrono++
            }
        }
        if (depart == null) {
            liaison.indicateurCompactEntete.minuteurCompact.text =
                entete.dureeFigeeMs?.let(DureesLisibles::formater) ?: ""
        }
    }

    /**
     * Réinitialise l'indicateur compact à un état REPOS (v0.39.1,
     * correctif n°5) — pastille masquée, titres effacés, sous-titre
     * masqué. Évite les résidus visuels d'un build précédent quand le
     * `canal` repasse à `null` puis re-devient non-null.
     */
    private fun reinitialiserIndicateur() {
        with(liaison.indicateurCompactEntete) {
            pastilleCanalCompact.isVisible = false
            spinnerCanalCompact.isVisible = false
            iconeCanalCompact.isVisible = false
            activiteCompact.text = ""
            sousTitreCompact.isVisible = false
            sousTitreCompact.text = ""
            minuteurCompact.text = ""
        }
    }

    // ---- Indicateur compact (INC-10 ; en-tête unifié v2) ---------------

    /** Rend l'indicateur compact UNIQUE du rang 1 : pastille + spinner en
     *  vol / icône de statut, titre, sous-titre d'étape ou note
     *  « Sync non annulable », bouton Arrêter. La dérivation reste unique
     *  (INC-02) : [PresentationTooling.etatEntete]. */
    private fun rendreIndicateurCompact(entete: EtatEnteteTooling, canal: CanalTooling) {
        val indicateur = liaison.indicateurCompactEntete
        indicateur.pastilleCanalCompact.isVisible = true
        indicateur.pastilleCanalCompact.backgroundTintList =
            ColorStateList.valueOf(couleurStatut(entete.statut, entete.couleur))
        val enVol = entete.statut == StatutEntete.EN_VOL
        indicateur.spinnerCanalCompact.isVisible = enVol
        indicateur.iconeCanalCompact.isVisible = !enVol
        if (!enVol) {
            val icone =
                when (entete.statut) {
                    StatutEntete.SUCCES -> jo.codeide.core.ui.R.drawable.ic_fait
                    StatutEntete.ECHOUE -> jo.codeide.core.ui.R.drawable.ic_fermer_onglet
                    StatutEntete.ANNULE -> R.drawable.ic_arreter_build
                    StatutEntete.EN_VOL, StatutEntete.NEUTRE -> canal.icone
                }
            indicateur.iconeCanalCompact.setImageResource(icone)
        }
        indicateur.activiteCompact.text = entete.titre.resoudre(activite)
        rendreSousTitreCompact(entete, canal)
    }

    /** Sous-titre de l'indicateur (§3.3) : la note « Sync non annulable »
     *  OCCUPE l'emplacement du bouton Arrêter absent (INC-12 — pendant
     *  une sync, l'honnêteté prime sur le détail d'étape, qui reste dans
     *  le lecteur de la console) ; sinon le libellé de la phase courante
     *  combiné à son détail (« Phase — 42 Mo · 3 éléments »), ou le texte
     *  du repos (succès : « 48 tâches disponibles »). */
    private fun rendreSousTitreCompact(entete: EtatEnteteTooling, canal: CanalTooling) {
        val indicateur = liaison.indicateurCompactEntete
        if (canal == CanalTooling.SYNC && entete.statut == StatutEntete.EN_VOL) {
            indicateur.sousTitreCompact.isVisible = true
            indicateur.sousTitreCompact.text =
                activite.getString(R.string.editor_tooling_sync_non_annulable)
            return
        }
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
        indicateur.sousTitreCompact.isVisible = texte != null
        if (texte != null) {
            indicateur.sousTitreCompact.text = texte
        }
    }

    /** Couleur de la pastille selon le statut visuel : canal harmonisé en
     *  vol/au repos, vert de succès, rouge d'échec, AVERTISSEMENT pour un
     *  annulé (INC-15 — distinct de l'échec, rôles sémantiques ADR 0059,
     *  suivent les 8 palettes et la nuit). */
    private fun couleurStatut(statut: StatutEntete, couleurCanal: Int): Int = when (statut) {
        StatutEntete.SUCCES -> {
            MaterialColors.getColor(liaison.root, jo.codeide.core.ui.R.attr.colorSucces)
        }

        StatutEntete.ECHOUE -> {
            MaterialColors.getColor(liaison.root, androidx.appcompat.R.attr.colorError)
        }

        StatutEntete.ANNULE -> {
            MaterialColors.getColor(liaison.root, jo.codeide.core.ui.R.attr.colorAvertissement)
        }

        StatutEntete.EN_VOL, StatutEntete.NEUTRE -> {
            ThemeHarmonizer.harmoniserAvecPrimaire(activite, couleurCanal)
        }
    }

    // ---- Progression (§3.3) ---------------------------------------------

    /** Progression : DÉTERMINÉE (octets reçus/total, pleine au succès)
     *  quand elle existe, indéterminée pendant une activité en vol —
     *  couleur de canal harmonisée, vert au succès. Material exige de
     *  MASQUER l'indicateur pour basculer de mode. En-tête unifié v2 :
     *  la bande vit sous le rang 1 pour TOUS les onglets — repliée elle
     *  passe sous le peek de 48 dp, le peek ne bouge plus (INC-18).
     *  INC-14 : la VALEUR de la barre est bornée à ~10 mises à jour/s
     *  (les bornes 0 et 1 passent toujours, tout comme le retour du mode
     *  indéterminé). */
    private fun rendreProgression(entete: EtatEnteteTooling) {
        val indicateur = liaison.progressionTooling
        val couleurBarre = couleurStatut(entete.statut, entete.couleur)
        indicateur.isVisible = false
        val progression = entete.progression
        if (progression != null || flashVerdict) {
            // INC-13 : au flash de verdict sans valeur déterminée (échec,
            // annulé), la bande est PLEINE — pas une animation sans fin.
            val repasseDeterminee = indicateur.isIndeterminate
            if (repasseDeterminee) {
                indicateur.isIndeterminate = false
            }
            val maintenant = horloge.nowMillis()
            if (majProgressionAutorisee(progression, repasseDeterminee, maintenant)) {
                val valeur = progression ?: 1f
                indicateur.setProgressCompat((valeur.coerceIn(0f, 1f) * POURCENT_MAX).roundToInt(), true)
                derniereProgressionRendue = progression
                derniereMajProgressionMs = maintenant
                nbMajProgression++
            }
        } else {
            indicateur.isIndeterminate = true
        }
        indicateur.setIndicatorColor(couleurBarre)
    }

    // ---- Chrono ----------------------------------------------------------

    /**
     * Ticker du chrono en vol (500 ms — le centième de seconde est du
     * bruit sur un build). Correctif n°12 : le ticker ne tourne QUE
     * STARTED (`repeatOnLifecycle`) et lit l'HORLOGE INJECTÉE.
     */
    private fun lancerMinuteur(depart: Long): Job? = activite.lifecycleScope.launch {
        activite.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val ecoule = (horloge.nowMillis() - depart).coerceAtLeast(0L)
                liaison.indicateurCompactEntete.minuteurCompact.text = DureesLisibles.formater(ecoule)
                delay(PERIODE_MINUTEUR_MS)
            }
        }
    }

    // ---- Flash de verdict (INC-13) ---------------------------------------

    /**
     * Flash SYMÉTRIQUE au verdict terminal (INC-13 : ~1,5 s, succès vert /
     * échec rouge / annulé avertissement) puis masquage — plus de bande
     * pleine permanente au succès ni de disparition immédiate à l'échec.
     * Une activité qui (re)démarre éteint le flash en cours ; un NOUVEAU
     * verdict (statut ou durée différente du dernier flaché) le relance.
     */
    private fun majFlashVerdict(entete: EtatEnteteTooling) {
        if (activiteCourante) {
            travailFlash?.cancel()
            flashVerdict = false
            return
        }
        val verdictTerminal =
            entete.statut == StatutEntete.SUCCES ||
                entete.statut == StatutEntete.ECHOUE ||
                entete.statut == StatutEntete.ANNULE
        val nouveauVerdict =
            entete.statut != dernierStatutFlashe || entete.dureeFigeeMs != derniereDureeFlashee
        if (verdictTerminal && nouveauVerdict) {
            dernierStatutFlashe = entete.statut
            derniereDureeFlashee = entete.dureeFigeeMs
            travailFlash?.cancel()
            flashVerdict = true
            travailFlash = activite.lifecycleScope.launch {
                activite.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    delay(DUREE_FLASH_VERDICT_MS)
                    flashVerdict = false
                    majVisibiliteBande()
                }
            }
        }
    }

    /** Visibilité de la bande de progression (INC-13) : en vol ou au
     *  flash de verdict — jamais de bande morte permanente au repos. */
    private fun majVisibiliteBande() {
        liaison.progressionTooling.isVisible = activiteCourante || flashVerdict
    }

    /** Arrêt propre du ticker et du flash (destruction de la vue). */
    fun detruire() {
        travailMinuteur?.cancel()
        travailMinuteur = null
        travailFlash?.cancel()
        travailFlash = null
    }

    /** La nouvelle valeur de progression est-elle APPLIABLE (INC-13/14) :
     *  au flash de verdict, au retour du mode indéterminé, à la finale
     *  (1f) — toujours — sinon seulement si la valeur a changé ET que le
     *  seuil de ~10 mises à jour/s est attendu. */
    private fun majProgressionAutorisee(progression: Float?, repasseDeterminee: Boolean, maintenant: Long): Boolean =
        flashVerdict ||
            repasseDeterminee ||
            progression == 1f ||
            (
                progression != derniereProgressionRendue &&
                    maintenant - derniereMajProgressionMs >= SEUIL_MAJ_PROGRESSION_MS
                )

    private companion object {
        /** Période du chrono de l'indicateur tooling (500 ms). */
        const val PERIODE_MINUTEUR_MS = 500L

        /** Seuil de rafraîchissement de la progression déterminée
         *  (INC-14 : ~10 mises à jour par seconde au plus). */
        const val SEUIL_MAJ_PROGRESSION_MS = 100L

        /** Durée du flash de verdict de la bande de progression (INC-13 :
         *  bref, symétrique succès/échec, puis masquage). */
        const val DUREE_FLASH_VERDICT_MS = 1_500L

        /** Progression maximale en pourcentage (determinée 0..1 → 0..100). */
        const val POURCENT_MAX = 100f
    }
}
