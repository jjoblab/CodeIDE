package jo.codeide.feature.editor

import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import jo.codeide.feature.editor.databinding.IndicateurToolingCompactBinding
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
 * (indéterminée sinon, pleine au succès).
 *
 * v0.185.0 (retour propriétaire : « en collapse la barre de navigation
 * transparente laisse voir une partie du sheet, seul l'en-tête devrait
 * être visible ») : le contrôleur ne POSE plus le `peekHeight` du
 * comportement — il expose la hauteur de CONTENU replié
 * ([peekContenuPx]) et l'activité la confie au
 * [GestionnaireInsetsEditeur.definirPeekContenu] qui AJOUTE l'inset de
 * barre de navigation : replié, l'en-tête vit ENTIÈREMENT au-dessus de
 * la barre et SEUL l'espace rembourré pend derrière elle.
 *
 * Exemption detekt ciblée (règle 16, v0.80.1) : `TooManyFunctions` —
 * une fonction par SECTION de l'en-tête tooling (pastille, sous-titre,
 * progression, chrono, peek) ; les regrouper masquerait la structure
 * du §3.3 du prompt tooling.
 *
 * @param activite hôte (contexte de rendu + portée de cycle de vie).
 * @param liaison liaison de l'espace de travail (vues de l'en-tête).
 * @param horloge horloge injectée (correctif n°12 : jamais l'horloge directe).
 * @param surArret action d'annulation du build (l'activité relaie le
 *        ViewModel — le contrôleur ne connaît ni ViewModel ni actions).
 * @param surPeekChange appelé après chaque changement de hauteur de
 *        contenu replié (l'activité relaie au gestionnaire d'insets qui
 *        re-pose le peek TOTAL et la réserve sous l'éditeur).
 */
@Suppress("TooManyFunctions")
internal class PanneauToolingController(
    private val activite: AppCompatActivity,
    private val liaison: ActivityEditorBinding,
    private val horloge: TimeProvider,
    private val surArret: () -> Unit,
    /** B3 : appelé après chaque changement de hauteur de contenu replié
     *  (v0.185.0 : le peek TOTAL est posé par le gestionnaire d'insets,
     *  qui y AJOUTE l'inset de barre de navigation). */
    private val surPeekChange: () -> Unit = {},
) {
    /** L'en-tête tooling a-t-il quelque chose à montrer ? */
    private var ligneActivee = false

    /** Onglet actif du panneau (v0.80.1) : Console → la ligne tooling EST
     *  l'en-tête, la première section disparaît et le peek s'adapte.
     *  v0.80.2 : Problèmes/Journal → la ligne tooling n'est PLUS affichée
     *  (seule la première section porte les informations de l'onglet). */
    private var ongletCourant: OngletPanneau = OngletPanneau.JOURNAL

    /** Alpha courant du fondu de l'en-tête (v0.80.1) : la visibilité de la
     *  ligne tooling ne dépend PLUS de celle de la première section. */
    private var alphaCourant = 1f

    /** Le sheet est-il ÉTENDU (état stabilisé, v0.80.2) : à l'extension la
     *  ligne tooling disparaît COMPLÈTEMENT (GONE — les onglets montent au
     *  sommet du sheet, comme l'en-tête d'AndroidIDE à l'extension). */
    private var sheetEtendu = false

    /** La progression tooling a-t-elle quelque chose à montrer (v0.80.2 —
     *  indépendant de l'onglet : c'est le rendu qui la pose, l'onglet
     *  décide de sa visibilité effective). */
    private var progressionVisiblee = false

    /** Ticker du chrono en vol (annulé au prochain rendre ou à la destruction). */
    private var travailMinuteur: Job? = null

    /** Dernier état d'en-tête rendu (INC-14 : dédoublonnage STRUCTUREL —
     *  la collecte de l'état Gradle ENTIER re-rend l'en-tête à chaque
     *  émission ; un état identique ne refait plus le travail). */
    private var dernierEntete: EtatEnteteTooling? = null

    /** Dernier canal rendu (complète la clé de dédoublonnage : la ligne
     *  active dépend du canal, pas seulement de l'en-tête). */
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

    /** Flash de verdict en cours (INC-13 : la bande s’allume ~1,5 s au
     *  verdict terminal — succès OU échec, symétrique — puis se masque,
     *  plus de bande verte pleine permanente au repos). */
    private var flashVerdict = false

    /** Travail du masquage du flash (annulé au verdict suivant ou au
     *  redémarrage d’une activité). */
    private var travailFlash: Job? = null

    /** Dernier verdict flaché (statut + durée) : un NOUVEAU verdict
     *  re-flashe, une ré-émission identique non. */
    private var dernierStatutFlashe: StatutEntete? = null
    private var derniereDureeFlashee: Long? = null

    /**
     * Hauteur de CONTENU du sheet replié SANS l'inset de barre de
     * navigation (v0.185.0) : première section sur Problèmes/Journal,
     * ligne tooling + progression + onglets sur Console. Le peek TOTAL
     * (`contenu + inset`) est posé par [GestionnaireInsetsEditeur] —
     * replié, l'en-tête reste ENTIÈREMENT au-dessus de la barre de
     * navigation transparente.
     *
     * Valeur initiale : la première section (onglet Journal par défaut,
     * `editor_panneau_replie`) — recalculée par [majPeekPanneau].
     */
    var peekContenuPx: Int = activite.resources.getDimensionPixelSize(R.dimen.editor_panneau_replie)
        private set

    init {
        // Arrêt de l'activité tooling en cours (v0.32.5 ; INC-11 : UNE
        // seule action, un seul libellé — le bouton de la ligne enrichie,
        // ceux des DEUX indicateurs compacts et celui de la console y
        // délèguent tous ; visible seulement pendant un build, la
        // synchronisation ne s'annule pas — INC-12).
        liaison.boutonArreterTooling.setOnClickListener { surArret() }
        liaison.indicateurCompactEntete.boutonArreterCompact.setOnClickListener { surArret() }
        liaison.indicateurCompactOnglets.boutonArreterCompact.setOnClickListener { surArret() }
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
        ligneActivee = canal != null
        activiteCourante = etat.canalActif != null

        // INC-13 : flash SYMÉTRIQUE au verdict terminal (~1,5 s, succès
        // vert / échec rouge / annulé avertissement) puis masquage — plus
        // de bande pleine permanente au succès ni de disparition
        // immédiate à l'échec. Une activité qui (re)démarre éteint le
        // flash en cours.
        if (activiteCourante) {
            travailFlash?.cancel()
            flashVerdict = false
        } else if (entete.statut == StatutEntete.SUCCES ||
            entete.statut == StatutEntete.ECHOUE ||
            entete.statut == StatutEntete.ANNULE
        ) {
            if (entete.statut != dernierStatutFlashe || entete.dureeFigeeMs != derniereDureeFlashee) {
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

        if (canal != null) {
            rendrePastille(entete, canal)
            liaison.activiteTooling.text = entete.titre.resoudre(activite)
            rendreSousTitre(entete)
            // INC-10 : les DEUX indicateurs compacts reçoivent le MÊME
            // état d'en-tête (la dérivation reste unique, INC-02) — la
            // première section les porte sur les autres onglets, le rang
            // des onglets quand le sheet est étendu.
            rendreIndicateurCompact(liaison.indicateurCompactEntete, entete, canal)
            rendreIndicateurCompact(liaison.indicateurCompactOnglets, entete, canal)
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
        // INC-12 : honnêteté — pendant une sync (non annulable),
        // l'emplacement du bouton Arrêter explique son absence, au lieu
        // d'un silence de 3 minutes (vécu : sync de 194 s).
        liaison.noteNonAnnulable.isVisible = etat.canalActif == CanalTooling.SYNC
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
            val figee = entete.dureeFigeeMs?.let(DureesLisibles::formater) ?: ""
            liaison.minuteurTooling.text = figee
            liaison.indicateurCompactEntete.minuteurCompact.text = figee
            liaison.indicateurCompactOnglets.minuteurCompact.text = figee
        }

        // Le peek suit la composition de l'en-tête (v0.80.1) : première
        // section visible sur Problèmes/Journal, ligne tooling seule sur
        // Console — les onglets deviennent alors la poignée repliée du
        // sheet quand aucune activité tooling ne tourne. v0.80.2 : la
        // ligne tooling ne compte PLUS dans le peek hors onglet Console.
        appliquerVisibiliteLigne()
        majPeekPanneau()
    }

    /**
     * Onglet actif du panneau (v0.80.1, appelé par l'activité à chaque
     * rendu) : compose le peek — Console masque la première section, les
     * onglets deviennent sa poignée repliée quand aucune activité tooling
     * ne tourne. v0.80.2 : sur Problèmes/Journal la ligne tooling est
     * masquée (seule la première section est visible).
     */
    fun definirOnglet(onglet: OngletPanneau) {
        if (onglet == ongletCourant) return
        ongletCourant = onglet
        appliquerVisibiliteLigne()
        majPeekPanneau()
    }

    /**
     * État ÉTENDU stabilisé du sheet (v0.80.2, appelé par l'activité sur
     * `onStateChanged`) : à l'extension la ligne tooling passe GONE — les
     * onglets montent au sommet du sheet étendu ; au repli elle reprend
     * sa place dans le peek (INVISIBLE puis VISIBLE avec le fondu, jamais
     * GONE en plein glissement : la hauteur du sheet ne saute pas).
     */
    fun definirSheetEtendu(etendu: Boolean) {
        if (etendu == sheetEtendu) return
        sheetEtendu = etendu
        appliquerVisibiliteLigne()
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

    /** Le fondu de l'en-tête réapplique la visibilité de la ligne (v0.80.1 :
     *  indépendante de la première section — la ligne EST l'en-tête sur
     *  l'onglet Console ; v0.80.2 : INVISIBLE sous le seuil — la place est
     *  conservée en cours de glissement, GONE seulement une fois étendu). */
    fun appliquerFondu(alpha: Float) {
        alphaCourant = alpha
        appliquerVisibiliteLigne()
    }

    /**
     * Visibilité effective de la ligne tooling (v0.80.2) : visible sur
     * l'onglet CONSOLE uniquement (sur Problèmes/Journal la PREMIÈRE
     * section porte les informations de l'onglet — la 2e section est
     * éteinte) ; GONE une fois le sheet étendu (elle disparaît), GONE si
     * inactive, INVISIBLE sous le seuil du fondu (place conservée — pas
     * de saut de hauteur en plein glissement), VISIBLE sinon.
     */
    private fun appliquerVisibiliteLigne() {
        liaison.ligneTooling.visibility =
            when {
                ongletCourant != OngletPanneau.CONSOLE || sheetEtendu || !ligneActivee -> View.GONE
                alphaCourant > SEUIL_FONDU_VISIBLE -> View.VISIBLE
                else -> View.INVISIBLE
            }
        // INC-10 : l'indicateur COMPACT prend le relais partout où la
        // ligne enrichie ne s'affiche pas. Celui de la première section
        // n'a pas d'autres portes (son parent est GONE sur Console et à
        // l'extension) ; celui du rang des onglets ne vit qu'étendu.
        liaison.indicateurCompactEntete.root.isVisible = ligneActivee
        liaison.indicateurCompactOnglets.root.isVisible = ligneActivee && sheetEtendu
        // La progression tooling suit la règle d'onglet (v0.80.2) :
        // bande d'activité réservée à la Console — mais elle reste VISIBLE
        // même étendue (retour d'activité pendant l'extension). INC-13 :
        // visible en vol ou pendant le flash de verdict, JAMAIS au repos.
        majVisibiliteBande()
    }

    /** Visibilité de la bande de progression (INC-13) : en vol ou au
     *  flash de verdict — jamais de bande morte permanente au repos. */
    private fun majVisibiliteBande() {
        progressionVisiblee = activiteCourante || flashVerdict
        liaison.progressionTooling.isVisible =
            progressionVisiblee && ongletCourant == OngletPanneau.CONSOLE
    }

    /** Arrêt propre du ticker et du flash (destruction de la vue). */
    fun detruire() {
        travailMinuteur?.cancel()
        travailMinuteur = null
        travailFlash?.cancel()
        travailFlash = null
    }

    // ---- Indicateur compact (INC-10) ------------------------------------

    /** Rend UN indicateur compact (INC-10) : le MÊME état d'en-tête que
     *  la ligne enrichie — pastille + spinner en vol, titre court,
     *  durée figée, bouton Arrêter. La dérivation reste unique
     *  (INC-02) : seule l'instance de vue change. */
    private fun rendreIndicateurCompact(
        indicateur: IndicateurToolingCompactBinding,
        entete: EtatEnteteTooling,
        canal: CanalTooling,
    ) {
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
        indicateur.boutonArreterCompact.isVisible = entete.arret
    }

    // ---- Pastille de canal (§3.3) ---------------------------------------

    /** Pastille : fond teinté (canal harmonisé / succès / échec / annulé),
     *  icône blanche en repos (coche de succès, croix d'échec, carré
     *  d'arrêt pour un annulé — INC-15), SPINNER animé en vol. */
    private fun rendrePastille(entete: EtatEnteteTooling, canal: CanalTooling) {
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
                    StatutEntete.ANNULE -> R.drawable.ic_arreter_build
                    StatutEntete.EN_VOL, StatutEntete.NEUTRE -> canal.icone
                }
            liaison.iconeCanalTooling.setImageResource(icone)
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
     *  MASQUER l'indicateur pour basculer de mode. v0.80.2 : la visibilité
     *  mémorisée ([progressionVisiblee]) est distincte de la visibilité
     *  effective (Console uniquement). INC-14 : la VALEUR de la barre est
     *  bornée à ~10 mises à jour/s (les bornes 0 et 1 passent toujours,
     *  tout comme le retour du mode indéterminé). */
    private fun rendreProgression(entete: EtatEnteteTooling, etat: EtatGradle) {
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
            if (
                flashVerdict || repasseDeterminee || progression == 1f ||
                (
                    progression != derniereProgressionRendue &&
                        maintenant - derniereMajProgressionMs >= SEUIL_MAJ_PROGRESSION_MS
                    )
            ) {
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
                val texte = DureesLisibles.formater(ecoule)
                liaison.minuteurTooling.text = texte
                liaison.indicateurCompactEntete.minuteurCompact.text = texte
                liaison.indicateurCompactOnglets.minuteurCompact.text = texte
                delay(PERIODE_MINUTEUR_MS)
            }
        }
    }

    /** Peek de CONTENU du panneau (v0.185.0 : l'inset de barre est
     *  AJOUTÉ par le gestionnaire d'insets, qui pose le peek TOTAL) :
     *  en-tête seul sur Problèmes/Journal, + ligne tooling enrichie
     *  (et progression) sur Console quand une activité s'y affiche,
     *  + les ONGLETS qui deviennent la poignée repliée de l'onglet
     *  Console — sans activité tooling, le sheet replié montre ses
     *  onglets au lieu de disparaître. v0.80.2 : sur Problèmes/Journal
     *  la ligne tooling ne compte plus dans le peek (seule la première
     *  section est repliée).
     *  B3 : notifie l'activité pour qu'elle relaye au gestionnaire
     *  d'insets (peek total + réserve sous l'éditeur). */
    private fun majPeekPanneau() {
        var peek = 0
        if (ongletCourant != OngletPanneau.CONSOLE) {
            peek += activite.resources.getDimensionPixelSize(R.dimen.editor_panneau_replie)
        }
        if (ligneActivee && ongletCourant == OngletPanneau.CONSOLE) {
            peek += activite.resources.getDimensionPixelSize(R.dimen.editor_entete_tooling_hauteur)
            // INC-18 : l'emplacement de la bande (4 dp) est RÉSERVÉ tant
            // que la ligne est comptée — le démarrage et la fin d'une
            // activité ne déplacent plus l'éditeur (la bande elle-même
            // flashe au verdict puis se masque, INC-13, sans bouger le
            // peek).
            peek += (HAUTEUR_PROGRESSION_TOOLING_DP * activite.resources.displayMetrics.density).toInt()
        }
        if (ongletCourant == OngletPanneau.CONSOLE) {
            peek += activite.resources.getDimensionPixelSize(R.dimen.editor_panneau_onglets_hauteur)
        }
        if (peek != peekContenuPx) {
            peekContenuPx = peek
            surPeekChange()
        }
    }

    private companion object {
        /** Période du chrono de l'en-tête tooling (500 ms). */
        const val PERIODE_MINUTEUR_MS = 500L

        /** Seuil de rafraîchissement de la progression déterminée
         *  (INC-14 : ~10 mises à jour par seconde au plus). */
        const val SEUIL_MAJ_PROGRESSION_MS = 100L

        /** Durée du flash de verdict de la bande de progression (INC-13 :
         *  bref, symétrique succès/échec, puis masquage). */
        const val DUREE_FLASH_VERDICT_MS = 1_500L

        /** Hauteur de la bande de progression tooling (dp). */
        const val HAUTEUR_PROGRESSION_TOOLING_DP = 4

        /** Alpha sous lequel l'en-tête fondu passe INVISIBLE. */
        const val SEUIL_FONDU_VISIBLE = 0.02f

        /** Progression maximale en pourcentage (determinée 0..1 → 0..100). */
        const val POURCENT_MAX = 100f
    }
}
