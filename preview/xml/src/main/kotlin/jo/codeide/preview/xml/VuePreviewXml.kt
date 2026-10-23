package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.min

/**
 * Panneau de rendu de la preview XML (PE1 — PREVIEW.md §3.3, étendu PE3 :
 * sélection P6) : dessine le bitmap off-screen du [MoteurPreviewXml] avec
 * **zoom/pan** (pincer, glisser, double-tap pour réajuster) et damier de
 * transparence, PLUS les états du panneau (bandeau d'erreur en haut,
 * signal discret d'édition, accueil) — TOUT est dessiné ici (aucune vue
 * enfant : la vue EST le panneau, la bibliothèque Material n'entre pas
 * dans ce module).
 *
 * PE3/P6 : l'appui SIMPLE (confirmé — pas de conflit avec le double-tap
 * de réajustement) est converti en coordonnées BITMAP (zoom/pan inversés)
 * et remis à [auTap] ; les vues rendues ([afficherRendu]) portent leur
 * span source, le contrôleur décide de la sélection. Le SURLIGNAGE de
 * la vue sélectionnée dans l'éditeur se dessine en overlay (rect pleins
 * bords sur les bounds de la vue).
 *
 * PE6a — CALQUES (maquette `docs/mockups/apercu-xml.html` § « Calques »,
 * « sans nouvelle donnée ») : cadre de sélection avec POIGNÉES d'angle,
 * NOM et DIMENSIONS en dp (densité du PROFIL — conversion pure
 * [CalquesApercu]); PARENT en pointillés; FRÈRES en filigrane; PASTILLE
 * d'état (durée du rendu, nombre de vues). Tous les tracés gardent une
 * épaisseur ÉCRAN constante au zoom (le patron LARGEUR_TRAIT_SURLIGNAGE
 * / echelle existant).
 *
 * PE7b — ZONES DE DÉPÔT (maquette § « Zones de dépôt et glisser-déposer
 * natif ») : la vue dessine les zones en Canvas (conteneurs en violet,
 * conteneur visé plein, barre d'insertion entre les frères) et
 * implémente [OnDragListener] : la palette (PE7c) lance
 * `View.startDragAndDrop` puis se masque — le moteur de glisser-déposer
 * d'Android livre les événements ICI sans relais à la main ; le dépôt
 * remonte par [auDepot] en coordonnées BITMAP (l'écriture passe par
 * [InsertionXml] et le port `SourceApercu.remplacer`). Pendant un
 * glisser, les calques de sélection (parent/frères, box-model,
 * surlignage, pastilles) se MASENT (miroir `!S.dropView` de la maquette),
 * les bornes et contraintes restent.
 *
 * PE10d/e — MODE D'AFFICHAGE (maquette § « Comparaison » : « boutons de
 * zoom, barre de navigation, mode Plan ») : [modeAffichage] suit la
 * pastille segmented de la toolbar — DESIGN dessine le bitmap, PLAN
 * dessine le SQUELETTE des vues rendues (contours pointillés accent sur
 * le damier, miroir `.screen.bp`), LES_DEUX pose les deux planches CÔTE
 * À CÔTE (miroir `buildBoards` : deux `.board`, écart 40 px). Le hit
 * (tap, drag) se RE-MAPPE sur la planche frappée (miroir `toDesign` qui
 * vise toujours la planche [0]).
 *
 * PE10a — L'ombre « flottante » : l'écran rendu FLOTTE sur le damier
 * (miroir `.screen{box-shadow:0 0 0 1px rgba(0,0,0,.18),0 6px 24px …}`)
 * — halos concentriques à épaisseur ÉCRAN constante + liseré 1 px.
 * La PASTILLE d'état (durée, vues) QUITTE ce Canvas : elle devient une
 * vue du chrome (`:feature:preview` — PE10a, la règle zéro-Material du
 * module retrouve tout son sens).
 *
 * PE11i — JETONS INJECTÉS (ADR 0134 D7, PREVIEW_UI_ECRANS.md § 11) :
 * TOUTE couleur du moteur vient d'un objet PUR [JetonsCalques], posé par
 * [appliquerJetons] (l'activité résout le thème clair/nuit, le moteur ne
 * lit JAMAIS le thème) — les constantes de couleur disparaissent. Le
 * BANDEAU d'erreur et le signal d'édition QUITTENT aussi ce Canvas (D6) :
 * le bandeau est la vue `banniere_rendu`, l'état de frappe vit dans la
 * pastille — le moteur RELAIE l'état par le flux du moteur. Les calques
 * suivent § 3.5 : damier 8 dp sur `apercuSurface2`, poignées CARRÉES
 * 9 dp (surface + trait 2 accent), parent pointillé 1,5 dp à 75 %,
 * frères en BORD 1 dp à 35 %, étiquettes RECTANGLE rayon 4 mono 11,
 * épingles liserées `apercuSurCalque`, LÉGENDE de planche (§ 3.4).
 *
 * Le bitmap n'est jamais recyclé par la vue (le moteur peut en émettre un
 * nouveau pendant un dessin) : le GC d'ART collecte.
 *
 * Le calcul d'échelle « ajuster à la vue » vit dans [CalculsVuePreview]
 * (fonctions pures, testées sans Android).
 */
@Suppress("TooManyFunctions", "LargeClass") // Vue auto-dessinée : un panneau UNE vue.
public class VuePreviewXml :
    View,
    View.OnDragListener {
    private var bitmap: Bitmap? = null
    private var echelle = 1f

    /**
     * La planche a-t-elle déjà été AJUSTÉE (fit) dans cette session ?
     * v0.166.5 : le canvas de l'aperçu se RETAILLE au fil de la feuille
     * inférieure (repli/déploiement — le rendu ne passe jamais sous la
     * feuille) ; le resize ne RE-FIT plus une planche déjà posée : le zoom
     * de l'utilisateur SURVIT, seul le pan se re-borne ([bornerPan]).
     */
    private var plancheAjustee = false
    private var decalageX = 0f
    private var decalageY = 0f
    private var accueilAffiche = true

    /**
     * Jetons de couleur du moteur (PE11i — ADR 0134 D7) : AUCUNE couleur
     * n'est dessinée avant [appliquerJetons] — l'activité injecte dès
     * onCreate, avant le premier tracé.
     */
    private var jetonsCalques: JetonsCalques? = null

    /**
     * Le libellé du PROFIL courant pour la LÉGENDE de planche (PE11i,
     * miroir `.cap` « DESIGN · PIXEL 4 » — § 3.4) : posé par l'activité
     * (nom du profil, ex. « Pixel 4 »).
     */
    public var libelleProfil: String = ""
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Vues du dernier rendu (hit-test P6 — vides hors LAYOUT). */
    private var vues: List<InfoVueRendu> = emptyList()

    /** Indice de la vue surlignée (sélection éditeur → preview, -1 aucune). */
    private var indiceSurligne: Int = -1

    /** Densité du profil de rendu (PE6a — conversion px → dp des libellés). */
    private var densiteDpi: Int = 0

    /**
     * Le mode d'affichage (PE10d/e — pastille Design/Plan/Les deux de la
     * toolbar) : changer de mode RE-AJUSTE le contenu (Les deux double
     * la largeur) puis redessine.
     */
    public var modeAffichage: ModeApercu = ModeApercu.DESIGN
        set(valeur) {
            if (field == valeur) return
            field = valeur
            if (width > 0 && height > 0) ajusterALaVue()
            invalidate()
        }

    /** Problèmes du dernier rendu (PE6c — pastilles de la maquette). */
    private var problemes: List<ProblemesPreview.ProblemeApercu> = emptyList()

    /**
     * Les lignes de RELATIONS du calque (PE12e — spec § 8, miroir
     * `drawRelations`) : règles et ancres de chaque parent
     * `RelativeLayout`/`CoordinatorLayout`, composées par l'activité
     * ([CalquesApercu.lignesRelations] — les règles se lisent sur le
     * TEXTE pour rester justes quand le rendu s'interrompt, les bornes
     * viennent du dernier rendu).
     */
    public var lignesRelations: List<CalquesApercu.LigneRelation> = emptyList()
        set(valeur) {
            field = valeur
            invalidate()
        }

    // ── Calques activables (PE6b — popover « Calques » de la maquette) ──

    /** Parent pointillés + frères filigrane (PE6a, activables PE6b). */
    public var calquesParentFreres: Boolean = true
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Bornes de TOUTES les vues (PE6b — calque optionnel de la maquette). */
    public var calquesBornes: Boolean = false
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Marges (orange) et padding (vert) de la sélection (PE6c). */
    public var calquesMargesPadding: Boolean = false
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Écarts des enfants de la racine aux bords/frères (PE6c). */
    public var calquesContraintes: Boolean = false
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Pastilles de problèmes aux coins des vues fautives (PE6c). */
    public var calquesProblemes: Boolean = false
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Grille 8 dp du profil, au-dessus du rendu (PE6b). */
    public var grilleAffichee: Boolean = false
        set(valeur) {
            field = valeur
            invalidate()
        }

    // ── Zones de dépôt (PE7b — miroir drawDrop de la maquette) ──

    /** Les zones de dépôt sont affichées (un glisser portant [ZonesDepot.ContenuDepot] est en cours). */
    public var zonesDepotAffichees: Boolean = false
        set(valeur) {
            field = valeur
            if (!valeur) fieldCibleDepot = null
            invalidate()
        }

    /** Le conteneur visé + l'index entre frères (change au survol). */
    public var cibleDepot: ZonesDepot.CibleDepot?
        get() = fieldCibleDepot
        set(valeur) {
            if (valeur == fieldCibleDepot) return
            fieldCibleDepot = valeur
            invalidate()
        }
    private var fieldCibleDepot: ZonesDepot.CibleDepot? = null

    /**
     * Le DÉPÔT confirmé (ACTION_DROP) : contenu glissé + point en
     * coordonnées BITMAP — le contrôleur compose l'édition ([InsertionXml])
     * et l'écrit dans la session (port `SourceApercu.remplacer`).
     */
    public var auDepot: ((contenu: ZonesDepot.ContenuDepot, x: Float, y: Float) -> Unit)? = null

    /**
     * Le REPÈRE EN DIRECT du popup de placement (PE7c, miroir
     * `S.dropView = {w, target}` de la maquette) : le contenu porte le
     * nom de la pill « + Widget · i/n », les zones de dépôt restent
     * affichées jusqu'à [masquerApercuPlacement], la CIBLE suit le choix
     * de référence/position via [cibleDepot] — le même calque que le
     * drag PE7b dessine le tout (conteneurs violets, barre d'insertion).
     */
    public fun montrerApercuPlacement(contenu: ZonesDepot.ContenuDepot) {
        contenuGlisse = contenu
        zonesDepotAffichees = true
        cibleDepot = null
    }

    /** Efface le repère de placement (fermeture du popup, dépôt, annulation). */
    public fun masquerApercuPlacement() {
        if (!zonesDepotAffichees) return
        zonesDepotAffichees = false
        contenuGlisse = null
    }

    /** Le contenu glissé courant (localState du drag natif — PE7b). */
    private var contenuGlisse: ZonesDepot.ContenuDepot? = null

    /** Appui simple confirmé, en coordonnées BITMAP (contenu, pas écran). */
    public var auTap: ((x: Float, y: Float) -> Unit)? = null

    // ── Redimensionnement par poignée (PE11l — demandé par le propriétaire) ──

    /**
     * Le RELÂCHEMENT d'une poignée d'angle de la sélection (PE11l +
     * PE14-S2, ADR 0138) : UNE édition groupée des dimensions FIXES de
     * la [RedimensionApercu.CibleRedimension] (plancher 8 dp, miroir
     * `mutate` de la maquette — l'activité relaie au port, une seule
     * entrée d'historique par geste) ; la cible porte l'ANCRE et le coin
     * visé — le par-layout en dérive les ajustements (poids, marges,
     * positions `tools:`). `false` = écriture REFUSÉE (sélection
     * périmée) : le fantôme ne s'attarde pas (pas de faux confort).
     */
    public var auRedimensionner: ((cible: RedimensionApercu.CibleRedimension) -> Boolean)? = null

    /** Le coin ANCRE du redimensionnement (coordonnées CONTENU — null : pas de geste en cours). */
    private var redimensionAncre: Pair<Int, Int>? = null

    /** Le point SAISI au départ (dérive mesurée depuis lui — le contact seul n'écrit rien). */
    private var redimensionDepart: Pair<Int, Int>? = null

    /** Le point COURANT du doigt (coordonnées CONTENU — dessine le fantôme). */
    private var redimensionPoint: Pair<Int, Int>? = null

    /**
     * La COMPENSATION DE PRÉHENSION (PE14-S2 C1) : le doigt saisit DANS
     * le rayon de 18 dp écran, pas SUR le coin. Le `DOWN` mémorise
     * `decalage = coinSaisi − contact` et la machine ne stocke QUE des
     * points COMPENSÉS (contact + decalage — le DÉPART vaut le COIN
     * SAISI) : le fantôme part DE LA TAILLE ACTUELLE de la vue au lieu
     * de sauter, la dérive se mesure sur la même trajectoire (le
     * décalage est constant sur tout le geste — § 1.3 de l'étude PE14).
     */
    private var redimensionDecalage: Pair<Int, Int>? = null

    /**
     * Le fantôme PERSISTANT du relâchement (C4 — px contenu) : la cible
     * reste affichée jusqu'au RENDU SUIVANT, puis se fond (150 ms) — le
     * panneau n'a jamais d'instant vide entre le geste et le re-rendu
     * asynchrone (l'écriture part en `soumettreImmediat`, le bitmap
     * arrive quand il arrive).
     */
    private var fantomePersistant: RectF? = null

    /** Le départ du FONDU du fantôme persistant (null : plein jusqu'au prochain rendu). */
    private var debutFonduFantome: Long? = null

    /**
     * Changement d'échelle (PE6b — boutons de zoom, gestes, ajustement) :
     * l'activité y met à jour le pourcentage affiché dans le HUD.
     */
    public var auChangementEchelle: ((echelle: Float) -> Unit)? = null

    /** Fond du canvas (jeton surface2 — PE11i, § 3.4). */
    private val peintFondCanvas =
        Paint().apply {
            style = Paint.Style.FILL
        }

    /** Cases alternées du damier (jeton damierCase — PE11i, § 3.4). */
    private val peintCaseDamier =
        Paint().apply {
            style = Paint.Style.FILL
        }

    private val peintTexteEtat =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Grille 8 dp (PE6b) — traits fins au pas du profil. */
    private val peintGrille =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Bornes de toutes les vues (PE6b) — rect de contour. */
    private val peintBorne =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Lignes de contrainte (PE6c) — traits pointillés accent. */
    private val peintContrainte =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Bandes de marges/padding du box-model (PE6c) — remplissage translucide. */
    private val peintBoxModel =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Contour pointillé des CONTENEURS (PE7b — miroir `.dz` de la maquette). */
    private val peintZone =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Conteneur VISÉ (PE7b — miroir `.dz.on` : fond violet doux, bord solide). */
    private val peintZoneCible =
        Paint().apply {
            style = Paint.Style.FILL_AND_STROKE
            isAntiAlias = true
        }

    /** Barre d'insertion + halo (PE7b — miroir `.slot`). */
    private val peintBarreDepot =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Contour pointillé des bandes de MARGE (PE6c — miroir `.mg` de la maquette). */
    private val peintBoxModelContour =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Fond des pastilles de problème (PE6c) — disque ambre/rouge. */
    private val peintPin =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Trait du surlignage de sélection (P6) — couleur du thème, épaisseur écran constante. */
    private val peintSurlignage =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Fond translucide du surlignage (P6). */
    private val peintFondSurlignage =
        Paint().apply {
            style = Paint.Style.FILL
        }

    /** Parent de la sélection — pointillés (PE6a). */
    private val peintParent =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Frères de la sélection — BORD 1 dp à 35 % (PE11i, § 3.5 `.sib`). */
    private val peintFrere =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Poignées d'angle — CARRÉ plein surface (PE11i, § 3.5 `.sel .h`). */
    private val peintPoignee =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Trait des poignées d'angle — accent 2 dp (PE11i, § 3.5). */
    private val peintTraitPoignee =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Fond des étiquettes (nom · dimensions, pastille) — pill semi-opaque (PE6a). */
    private val peintPill =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Texte des étiquettes (`.lab`) — MONO 11/500 (PE11i, § 3.5). */
    private val peintEtiquette =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }

    /** Texte des épingles (`.pin`) — sans-serif 11/700 (PE11i, § 3.5). */
    private val peintTextePin =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

    /** Texte de la LÉGENDE de planche (`.cap`) — mono 11/500, interlettrage 0,04 em (PE11i, § 3.4). */
    private val peintLegende =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            letterSpacing = INTERLETTRE_LEGENDE_EM
        }

    /** Liseré des épingles — 2 dp `apercuSurCalque` (PE11i, § 3.5 `.pin`). */
    private val peintPinLisere =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Squelette PLAN (PE10d) — contour pointillé accent. */
    private val peintPlan =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    /** Halos de l'ombre « flottante » (PE10a). */
    private val peintOmbre =
        Paint().apply {
            style = Paint.Style.FILL
        }

    /** Liseré du contour de l'écran rendu (PE10a — miroir 0 0 0 1px). */
    private val peintLisere =
        Paint().apply {
            style = Paint.Style.STROKE
        }

    private val detecteurZoom =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val ancienne = echelle
                    echelle = CalculsVuePreview.bornerEchelle(echelle * detector.scaleFactor)
                    // Le contenu reste sous les doigts : le décalage suit le
                    // point focal rapporté à la nouvelle échelle.
                    val rapport = echelle / ancienne
                    decalageX = detector.focusX - rapport * (detector.focusX - decalageX)
                    decalageY = detector.focusY - rapport * (detector.focusY - decalageY)
                    bornerPan()
                    notifierEchelle()
                    invalidate()
                    return true
                }
            },
        )
    private val detecteurGestes =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    decalageX -= distanceX
                    decalageY -= distanceY
                    bornerPan()
                    invalidate()
                    return true
                }

                override fun onDoubleTap(evenement: MotionEvent): Boolean {
                    ajusterALaVue()
                    invalidate()
                    return true
                }

                override fun onSingleTapConfirmed(evenement: MotionEvent): Boolean {
                    // P6 : coordonnées BITMAP (zoom/pan inversés) — le
                    // contrôleur fait le hit-test et décide de la sélection.
                    val (xContenu, yContenu) =
                        CalculsVuePreview.coordonneesContenu(
                            xVue = evenement.x,
                            yVue = evenement.y,
                            decalageX = decalageX,
                            decalageY = decalageY,
                            echelle = echelle,
                        )
                    // PE10e : le tap sur la planche Plan (Les deux) se
                    // re-mappe sur la planche design (même arbre rendu).
                    val (xPlanche, yPlanche) =
                        CalculsVuePreview.coordonneesPlanche(
                            xContenu,
                            yContenu,
                            decalagePlancheHit(),
                        )
                    auTap?.invoke(xPlanche, yPlanche)
                    return true
                }
            },
        )

    public constructor(contexte: Context) : this(contexte, null)

    public constructor(
        contexte: Context,
        attributs: AttributeSet?,
    ) : super(contexte, attributs) {
        peintTexteEtat.textSize = echellePolice() * TAILLE_TEXTE_ETAT_DP
        contentDescription = contexte.getString(R.string.preview_cd)
        // PE7b : le drag natif d'Android livre ses événements ICI
        // (la palette lance startDragAndDrop, la vue reçoit).
        setOnDragListener(this)
    }

    /** Affiche un rendu valide (remplace le bitmap).
     *
     * v0.166.4 — LE ZOOM SURVIT AUX ÉDITIONS (retour propriétaire du
     * 2026-10-22 : « le zoom pendant l'édition n'est pas respecté »).
     * Avant, CHAQUE rendu repassait par [ajusterALaVue] : éditer une vue,
     * interagir avec le volet des attributs ou relâcher une poignée de
     * redimensionnement DÉZOOMAIT la planche — le geste suivant partait
     * d'une vue sautée. Désormais :
     *  - PREMIER rendu (pas de bitmap précédent) → ajustement [ajusterALaVue] ;
     *  - rendu suivant de MÊME planche (dimensions bitmap identiques — le
     *    cas de TOUTE édition : la taille vient du profil, pas du layout)
     *    → zoom et décalage INTACTS ;
     *  - planche RETAILLÉE (changement de profil, paysage, échelle de
     *    police) → zoom conservé, décalage ré-ancré sur le centre de
     *    contenu visible ([recadrerSurNouvellePlanche]) ;
     *  - vue NON MESURÉE (width/height = 0 — la feuille inférieure
     *    TOTALE couvre le canvas, v0.173.0 : « feuille expand + édition :
     *    le rendu devient petit (de zoomé) ») → zoom et pan CONSERVÉS
     *    pour une planche déjà POSÉE : éditer feuille déployée ne
     *    dézoome plus ; la première planche d'une session prend
     *    l'échelle de repli, [onSizeChanged] ajustera au prochain layout.
     * Le double-tap reste l'ajustement EXPLICITE de l'utilisateur.
     *
     *  PE6a : [densiteDpiProfil] (conversion dp des libellés de calques,
     *  0 = repli px). PE10a : la durée et le nombre de vues partent à la
     *  PASTILLE-VUE du chrome (couche HUD de l'activité). */
    public fun afficherRendu(
        bitmap: Bitmap,
        vuesRendues: List<InfoVueRendu> = emptyList(),
        densiteDpiProfil: Int = 0,
    ) {
        val ancien = this.bitmap
        val memesDimensions =
            ancien != null && ancien.width == bitmap.width && ancien.height == bitmap.height
        this.bitmap = bitmap
        this.vues = vuesRendues
        densiteDpi = densiteDpiProfil
        problemes = emptyList()
        accueilAffiche = false
        indiceSurligne = -1
        when {
            width <= 0 || height <= 0 -> {
                // La vue n'a pas encore été mesurée — on reporte l'ajustement.
                // onSizeChanged re-appellera ajusterALaVue() + invalidate().
                // v0.173.0 — retour propriétaire « feuille expand + édition :
                // le rendu devient petit (de zoomé) » : la feuille TOTALE
                // écrase le canvas à hauteur nulle et CHAQUE édition passait
                // ICI, pulvérisant l'échelle au repli (ECHELLE_REPLI = 0,1×)
                // — replier montrait un rendu minuscule. Une planche DÉJÀ
                // POSÉE garde donc son zoom et son pan INTACTS : la vue se
                // redresse à la prochaine mesure (onSizeChanged ne re-fit
                // pas une planche posée, seul le pan se re-borne).
                if (!plancheAjustee) {
                    // Première planche jamais ajustée : échelle de repli pour
                    // ne pas dessiner à échelle 0 (invisible).
                    echelle = ECHELLE_REPLI
                    decalageX = 0f
                    decalageY = 0f
                }
            }

            ancien == null -> {
                ajusterALaVue()
            }

            !memesDimensions -> {
                recadrerSurNouvellePlanche(ancien)
            }

            // Même planche, vue mesurée : zoom et pan INTACTS — c'est le
            // correctif du retour propriétaire (l'édition ne dézoome plus).
            else -> {
                Unit
            }
        }
        // PE14-S2 (C4) — le rendu SUIVANT arrive : le fantôme persistant
        // du dernier relâchement entame son fondu (la cible écrite est
        // maintenant DANS la planche — le fantôme n'est plus une
        // promesse mais un doublon qui s'efface en douceur).
        if (fantomePersistant != null) {
            debutFonduFantome = SystemClock.uptimeMillis()
        }
        notifierEchelle()
        invalidate()
    }

    /**
     * La planche a été RETAILLÉE (profil, paysage, échelle de police) :
     * le zoom de l'utilisateur est conservé, le décalage est ré-ancré
     * pour que le CENTRE de contenu visible AVANT le changement reste
     * au centre APRÈS (v0.166.4 — miroir du point focal du pincement).
     */
    private fun recadrerSurNouvellePlanche(ancien: Bitmap) {
        val bmp = bitmap ?: return
        val (centreX, centreY) =
            CalculsVuePreview.coordonneesContenu(
                xVue = width / 2f,
                yVue = height / 2f,
                decalageX = decalageX,
                decalageY = decalageY,
                echelle = echelle,
            )
        val rapportX = if (ancien.width > 0) bmp.width.toFloat() / ancien.width else 1f
        val rapportY = if (ancien.height > 0) bmp.height.toFloat() / ancien.height else 1f
        decalageX = width / 2f - centreX * rapportX * echelle
        decalageY = height / 2f - centreY * rapportY * echelle
        bornerPan()
    }

    /**
     * Pose les JETONS de couleur du moteur (PE11i — ADR 0134 D7) :
     * l'activité résout les 21 jetons du thème `Theme.CodeIDE.Apercu`
     * (clair ou nuit) et les INJECTE ici, à la création puis à chaque
     * changement de thème — le moteur ne lit jamais le thème lui-même.
     */
    public fun appliquerJetons(jetons: JetonsCalques) {
        jetonsCalques = jetons
        invalidate()
    }

    /**
     * Réinitialise (fermeture du panneau, plus de document). PE11i (D6) :
     * l'erreur et la frappe ne passent PLUS par la vue — le bandeau est
     * la vue `banniere_rendu`, la pastille d'état porte la frappe (le
     * moteur RELAIE son état par son flux).
     */
    public fun reinitialiser() {
        bitmap = null
        vues = emptyList()
        indiceSurligne = -1
        densiteDpi = 0
        problemes = emptyList()
        accueilAffiche = true
        plancheAjustee = false
        redimensionAncre = null
        redimensionDepart = null
        redimensionPoint = null
        redimensionDecalage = null
        effacerFantomePersistant()
        notifierEchelle()
        invalidate()
    }

    /**
     * Surligne la vue d'indice [indice] (sélection éditeur → preview,
     * P6) ; -1 efface le surlignage. Un indice hors bornes est ignoré.
     */
    public fun surlignerVue(indice: Int) {
        val borne = indice in vues.indices
        if (indice == indiceSurligne || (indice == -1 && indiceSurligne == -1)) return
        indiceSurligne = if (borne) indice else -1
        invalidate()
    }

    /** Dernier arbre de vues affiché (le contrôleur y fait son hit-test). */
    public val vuesRendues: List<InfoVueRendu> get() = vues

    /**
     * Échelle courante (PE6b) — le HUD de zoom affiche le pourcentage.
     */
    public val echelleCourante: Float get() = echelle

    /**
     * Zoome d'un FACTEUR au centre de la vue (PE6b — boutons + / − de
     * la maquette, miroir `zoomAt(mid)`) : le contenu reste centré,
     * l'échelle est bornée (0,25× … 6×), le pan suit le point focal.
     */
    public fun zoomer(facteur: Float) {
        if (bitmap == null || facteur <= 0f) return
        val ancienne = echelle
        val nouvelle = CalculsVuePreview.bornerEchelle(echelle * facteur)
        if (nouvelle == ancienne) return
        val rapport = nouvelle / ancienne
        val focusX = width / 2f
        val focusY = height / 2f
        echelle = nouvelle
        decalageX = focusX - rapport * (focusX - decalageX)
        decalageY = focusY - rapport * (focusY - decalageY)
        bornerPan()
        notifierEchelle()
        invalidate()
    }

    /**
     * Réajuste le contenu dans la vue (PE6b — bouton ⤢ de la maquette,
     * même geste que le double-tap).
     */
    public fun ajuster() {
        if (bitmap == null) return
        ajusterALaVue()
        invalidate()
    }

    /** Un seul point de notification du changement d'échelle (PE6b). */
    private fun notifierEchelle() {
        auChangementEchelle?.invoke(echelle)
    }

    /** Remplace la liste des problèmes affichés (PE6c — pastilles). */
    public fun afficherProblemes(problemes: List<ProblemesPreview.ProblemeApercu>) {
        this.problemes = problemes
        invalidate()
    }

    override fun onSizeChanged(
        largeur: Int,
        hauteur: Int,
        ancienneLargeur: Int,
        ancienneHauteur: Int,
    ) {
        super.onSizeChanged(largeur, hauteur, ancienneLargeur, ancienneHauteur)
        if (largeur != ancienneLargeur || hauteur != ancienneHauteur) {
            if (largeur <= 0 || hauteur <= 0) return
            // v0.166.5 — retour propriétaire : « le zoom pendant l'édition
            // n'est pas respecté » prolongé au RESIZE du canvas (la feuille
            // inférieure le retaille en continu) : une planche DÉJÀ AJUSTÉE
            // garde son échelle, seul le pan se re-borne ; le FIT reste
            // réservé au PREMIER dimensionnement d'une planche posée (et à
            // ⤢/double-tap, les gestes EXPLICITES de l'utilisateur).
            if (bitmap != null && plancheAjustee) {
                bornerPan()
            } else {
                ajusterALaVue()
            }
            notifierEchelle()
            invalidate()
        }
    }

    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        // PE11l : la POIGNÉE PRIME sur pan/zoom/tap — la saisie se joue en
        // ACTION_DOWN, le doigt suit (fantôme), UNE écriture au
        // relâchement ; le geste consommé n'atteint JAMAIS les détecteurs.
        if (traiterRedimension(evenement.actionMasked, evenement.x, evenement.y)) {
            // ClickableViewAccessibility : le relâchement d'un contact
            // simple sur une poignée se signale comme les autres.
            if (evenement.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        val geste = detecteurGestes.onTouchEvent(evenement)
        val zoom = detecteurZoom.onTouchEvent(evenement)
        // ClickableViewAccessibility : un appui SIMPLE se signale via
        // performClick (le double-tap de réajustement reste au détecteur).
        if (evenement.actionMasked == MotionEvent.ACTION_UP) performClick()
        return zoom || geste || super.onTouchEvent(evenement)
    }

    /**
     * La MACHINE du redimensionnement par poignée (PE11l — internal :
     * testée sans [MotionEvent], patron [traiterEvenementDrag]) :
     * ACTION_DOWN saisit la poignée sous le doigt (coin ANCRE opposé +
     * COMPENSATION de préhension PE14-S2 C1 — la cible tactile de
     * 18 dp écran dépasse le carré visuel de 9 dp, dérogation ADR 0133,
     * le doigt n'y tombe JAMAIS pile : les points suivants sont stockés
     * COMPENSÉS, le coin visuel suit le doigt), MOVE suit le point, UP
     * écrit UNE fois les dimensions cibles si le geste a DÉRIVÉ
     * (contact seul = rien, miroir le tap) et GARDE le fantôme jusqu'au
     * rendu suivant (C4), CANCEL abandonne. `true` = événement CONSOMMÉ.
     *
     * Exemption ciblée (règle 16) : refus MOVE/UP sans saisie = ISSUES
     * DISTINCTES, même patron que la machine de drag.
     */
    @Suppress("ReturnCount")
    internal fun traiterRedimension(
        action: Int,
        xVue: Float,
        yVue: Float,
    ): Boolean {
        val (xCourant, yCourant) =
            CalculsVuePreview.coordonneesContenu(xVue, yVue, decalageX, decalageY, echelle)
        val xContenu = Math.round(xCourant)
        val yContenu = Math.round(yCourant)
        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                val saisie = saisirPoignee(xContenu, yContenu)
                if (saisie == null) {
                    false
                } else {
                    val decalage =
                        RedimensionApercu.decalagePrehension(
                            coinX = saisie.coin.first,
                            coinY = saisie.coin.second,
                            pointX = xContenu,
                            pointY = yContenu,
                        )
                    redimensionAncre = saisie.ancre
                    // DÉPART et point COMPENSÉS : le départ vaut le COIN
                    // saisi — la dérive part de zéro, la cible part de la
                    // taille actuelle (C1).
                    redimensionDepart = saisie.coin
                    redimensionPoint = saisie.coin
                    redimensionDecalage = decalage
                    effacerFantomePersistant()
                    invalidate()
                    true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (redimensionAncre == null) return false
                redimensionPoint = pointCompense(xContenu, yContenu)
                invalidate()
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val ancre = redimensionAncre ?: return false
                val depart = redimensionDepart
                val point = redimensionPoint
                redimensionAncre = null
                redimensionDepart = null
                redimensionPoint = null
                redimensionDecalage = null
                // Contact SANS dérive : rien ne s'écrit (miroir le tap) ;
                // CANCEL n'écrit JAMAIS (règle 6 : pas d'écriture surprise).
                val cible = cibleDuRelachement(action, ancre, depart, point)
                if (cible == null) {
                    effacerFantomePersistant()
                } else {
                    poserFantomePersistant(cible)
                    // `false` (sélection périmée, racine non éditable) : le
                    // fantôme ne s'attarde pas sur une cible non écrite.
                    if (auRedimensionner?.invoke(cible) != true) effacerFantomePersistant()
                }
                invalidate()
                true
            }

            else -> {
                redimensionAncre != null
            }
        }
    }

    /** Le point de contact RAMENÉ au coin visuel (C1 — contact + compensation de préhension). */
    private fun pointCompense(
        xContenu: Int,
        yContenu: Int,
    ): Pair<Int, Int> {
        val decalage = redimensionDecalage ?: return xContenu to yContenu
        return (xContenu + decalage.first) to (yContenu + decalage.second)
    }

    /**
     * Les dimensions cibles du RELÂCHEMENT — `null` : contact sans
     * dérive (miroir le tap), CANCEL (règle 6 : pas d'écriture surprise),
     * densité inconnue ou geste immobile. La cible porte l'ANCRE et le
     * COIN visé (contact + compensation) : le par-layout en dérive ses
     * ajustements (PE14-S2 C3). Le `when` en EXPRESSION garde les issues
     * DISTINCTES sans flotter de retours (miroir le PE11l d'origine).
     */
    private fun cibleDuRelachement(
        action: Int,
        ancre: Pair<Int, Int>,
        depart: Pair<Int, Int>?,
        point: Pair<Int, Int>?,
    ): RedimensionApercu.CibleRedimension? =
        when {
            action != MotionEvent.ACTION_UP -> {
                null
            }

            point == null || depart == null -> {
                null
            }

            !RedimensionApercu.aDerive(
                depart.first,
                depart.second,
                point.first,
                point.second,
                seuilContactPx(),
            ) -> {
                null
            }

            else -> {
                RedimensionApercu
                    .dimensions(
                        ancreX = ancre.first,
                        ancreY = ancre.second,
                        pointX = point.first,
                        pointY = point.second,
                        densiteDpi = densiteDpi,
                    )?.let { dimensions ->
                        RedimensionApercu.CibleRedimension(
                            largeurDp = dimensions.first,
                            hauteurDp = dimensions.second,
                            ancreX = ancre.first,
                            ancreY = ancre.second,
                            coinX = point.first,
                            coinY = point.second,
                        )
                    }
            }
        }

    /**
     * La poignée saisie sous le point, s'il y en a une : la sélection
     * doit exister et la densité du profil être CONNUE (sans elle le
     * geste ne peut pas produire de dp — aucun faux repli en px).
     */
    private fun saisirPoignee(
        xContenu: Int,
        yContenu: Int,
    ): RedimensionApercu.PoigneeSaisie? {
        if (densiteDpi <= 0 || indiceSurligne !in vues.indices) return null
        return RedimensionApercu.poigneeSous(
            vue = vues[indiceSurligne],
            xContenu = xContenu,
            yContenu = yContenu,
            rayonPx = tailleEcran(RAYON_SAISIE_POIGNEE_DP),
        )
    }

    /** Le seuil de dérive d'un contact (px contenu — miroir le slop tactile). */
    private fun seuilContactPx(): Float = tailleEcran(SEUIL_DERIVE_DP)

    override fun performClick(): Boolean = super.performClick()

    // ── Glisser-déposer natif (PE7b, miroir du flux beginDrag/moveDrag/endDrag) ──

    /**
     * Le moteur de glisser-déposer d'Android livre ses événements ICI
     * (« sans qu'il faille les relayer à la main », maquette PE7b) :
     * seuls les drags portant un [ZonesDepot.ContenuDepot] en localState
     * sont acceptés (les autres — internes au système — passent au travers).
     */
    override fun onDrag(
        vue: View?,
        evenement: DragEvent,
    ): Boolean = traiterEvenementDrag(evenement.action, evenement.x, evenement.y, evenement.localState)

    /**
     * La MACHINE d'états du drag (internal : testée sans [DragEvent],
     * qui n'a pas de constructeur public) : STARTED accepte et révèle
     * les zones, LOCATION suit le point (cible recalculée), EXITED
     * efface la cible, DROP remonte [auDepot], ENDED remet tout à zéro.
     */
    @Suppress("ReturnCount") // Chaque action du drag (refus, refus sans bitmap, remise à zéro) est une issue DISTINCTE.
    internal fun traiterEvenementDrag(
        action: Int,
        xVue: Float,
        yVue: Float,
        localState: Any?,
    ): Boolean {
        val contenu = localState as? ZonesDepot.ContenuDepot ?: return false
        when (action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (bitmap == null) return false
                contenuGlisse = contenu
                zonesDepotAffichees = true
                cibleDepot = null
            }

            DragEvent.ACTION_DRAG_LOCATION -> {
                if (!zonesDepotAffichees) return true
                val (xContenu, yContenu) =
                    CalculsVuePreview.coordonneesContenu(xVue, yVue, decalageX, decalageY, echelle)
                // PE10e : un dépôt sur la planche Plan (Les deux) vise la
                // même position sur la planche design.
                val (x, y) =
                    CalculsVuePreview.coordonneesPlanche(xContenu, yContenu, decalagePlancheHit())
                // PE12d : le contenu glissé participe à la cible (ancre
                // `FloatingActionButton`, taille du déposé, comportement lié).
                cibleDepot = ZonesDepot.cibleSous(vues, x, y, contenu, densiteDpi)
            }

            DragEvent.ACTION_DRAG_EXITED -> {
                cibleDepot = null
            }

            DragEvent.ACTION_DROP -> {
                val cible = cibleDepot
                if (cible != null) {
                    val (xContenu, yContenu) =
                        CalculsVuePreview.coordonneesContenu(xVue, yVue, decalageX, decalageY, echelle)
                    val (x, y) =
                        CalculsVuePreview.coordonneesPlanche(xContenu, yContenu, decalagePlancheHit())
                    auDepot?.invoke(contenu, x, y)
                }
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                contenuGlisse = null
                zonesDepotAffichees = false
                cibleDepot = null
            }
        }
        return true
    }

    override fun onDraw(canevas: Canvas) {
        super.onDraw(canevas)
        // PE11i (ADR 0134 D7) : toute couleur vient des jetons injectés —
        // rien n'est dessiné avant [appliquerJetons] (l'activité injecte
        // dès onCreate, avant le premier tracé).
        val jetons = jetonsCalques ?: return
        val bmp = bitmap
        if (bmp == null) {
            dessinerAccueil(canevas, jetons)
            return
        }
        dessinerDamier(canevas, jetons)
        canevas.save()
        canevas.translate(decalageX, decalageY)
        canevas.scale(echelle, echelle)
        // PE10a : l'écran rendu FLOTTE sur le damier (ombre + liseré).
        dessinerOmbrePlanche(canevas, jetons, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        if (modeAffichage != ModeApercu.PLAN) {
            canevas.drawBitmap(bmp, 0f, 0f, null)
            dessinerLegendePlanche(canevas, jetons, 0, estPlan = false)
        }
        if (modeAffichage != ModeApercu.DESIGN) {
            // PE10d : la planche PLAN — squelette pointillé des vues
            // rendues (miroir `.screen.bp`), sélection en creux.
            // PE11l : le décalage suit [CalculsVuePreview.decalagePlancheAffichage]
            // (0 en mode Plan seul, planche + écart en « Les deux »).
            val decalageAffichage =
                CalculsVuePreview.decalagePlancheAffichage(modeAffichage, largeurPlanche(), ECART_PLANCHES)
            canevas.translate(decalageAffichage.toFloat(), 0f)
            dessinerOmbrePlanche(canevas, jetons, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            dessinerPlan(canevas, jetons)
            dessinerLegendePlanche(canevas, jetons, 0, estPlan = true)
            canevas.translate(-decalageAffichage.toFloat(), 0f)
        }
        // Calques, du plus large au plus étroit (miroir drawOverlay de la
        // maquette) : grille, bornes, frères, parent, contraintes,
        // box-model, sélection, pastilles de problèmes.
        dessinerGrille(canevas, jetons, bmp)
        dessinerBornes(canevas, jetons)
        dessinerFreres(canevas, jetons)
        dessinerParent(canevas, jetons)
        dessinerContraintes(canevas, jetons)
        dessinerRelations(canevas, jetons)
        dessinerMargesPadding(canevas, jetons)
        dessinerSurlignage(canevas, jetons)
        dessinerRedimensionnement(canevas, jetons)
        dessinerPastillesProblemes(canevas, jetons)
        // Zones de dépôt EN DERNIER (miroir drawDrop de la maquette : les
        // zones recouvrent les calques de sélection masqués au drag).
        dessinerZonesDepot(canevas, jetons)
        canevas.restore()
    }

    // ── Mode d'affichage (PE10d/e, miroir buildBoards/toDesign) ──

    /** La largeur d'une planche (px contenu — le bitmap rendu). */
    private fun largeurPlanche(): Int = bitmap?.width ?: 0

    /** Le décalage de la planche Plan (px contenu — planche + écart du monde). */
    private fun decalagePlanche(): Int = largeurPlanche() + ECART_PLANCHES

    /** Le décalage de re-mappage du HIT (0 = mode à une planche). */
    private fun decalagePlancheHit(): Float =
        if (modeAffichage == ModeApercu.LES_DEUX) decalagePlanche().toFloat() else 0f

    /** La largeur TOTALE du contenu selon le mode (fit et pan s'y alignent). */
    private fun largeurContenu(): Int =
        if (modeAffichage == ModeApercu.LES_DEUX) largeurPlanche() * 2 + ECART_PLANCHES else largeurPlanche()

    /**
     * Le squelette PLAN (PE10d, miroir `.screen.bp` : tous les contours
     * en pointillés accent, le reste transparent — le damier se lit
     * dessous) ; la sélection y dessine son cadre plein (miroir le
     * surlignage accent qui traverse les deux planches).
     */
    private fun dessinerPlan(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        peintPlan.color = jetons.accent
        peintPlan.pathEffect = EFFET_PLAN
        peintPlan.strokeWidth = tailleEcran(LARGEUR_TRAIT_PLAN)
        for (vue in vues) {
            if (vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            canevas.drawRect(
                RectF(vue.gauche.toFloat(), vue.haut.toFloat(), vue.droite.toFloat(), vue.bas.toFloat()),
                peintPlan,
            )
        }
        if (indiceSurligne in vues.indices) {
            val vue = vues[indiceSurligne]
            val rect =
                RectF(
                    vue.gauche.toFloat(),
                    vue.haut.toFloat(),
                    vue.droite.toFloat(),
                    vue.bas.toFloat(),
                )
            peintPlan.pathEffect = null
            peintPlan.strokeWidth = tailleEcran(LARGEUR_TRAIT_SURLIGNAGE)
            peintPlan.color = jetons.accent
            canevas.drawRect(rect, peintPlan)
            peintFondSurlignage.color = jetons.accentDoux
            canevas.drawRect(rect, peintFondSurlignage)
        }
    }

    /**
     * L'OMBRE « flottante » sous une planche (PE10a, miroir le
     * box-shadow de `.screen` ; PE11i : jetons `apercuEcranOmbre` /
     * `apercuEcranTrait`) : trois halos concentriques à épaisseur ÉCRAN
     * constante — l'alpha de chaque pas DÉRIVE du jeton (alpha total
     * réparti), aucune constante de couleur — puis le LISÉRÉ du contour.
     */
    private fun dessinerOmbrePlanche(
        canevas: Canvas,
        jetons: JetonsCalques,
        gauche: Float,
        droite: Float,
        bas: Float,
    ) {
        val rayon = tailleEcran(RAYON_OMBRE_DP)
        val descente = tailleEcran(DESCENTE_OMBRE_DP)
        peintOmbre.color = jetons.ecranOmbre
        val alphaPas = ((jetons.ecranOmbre ushr DECALAGE_ALPHA) + PASSES_OMBRE - 1) / PASSES_OMBRE
        for (pas in PASSES_OMBRE downTo 1) {
            val croissance = rayon * pas / PASSES_OMBRE
            peintOmbre.alpha = alphaPas
            canevas.drawRoundRect(
                RectF(gauche - croissance, -croissance + descente, droite + croissance, bas + croissance + descente),
                croissance,
                croissance,
                peintOmbre,
            )
        }
        // Le liseré (jeton ecranTrait, miroir `0 0 0 1px rgba(0,0,0,.18)`).
        peintLisere.color = jetons.ecranTrait
        peintLisere.strokeWidth = tailleEcran(TRAIT_LISERE_ECRAN_DP)
        canevas.drawRect(RectF(gauche, 0f, droite, bas), peintLisere)
    }

    /** Calque « frères » (PE11i, § 3.5 `.sib`) : BORD 1 dp accent à 35 % (plus d'aplat). */
    private fun dessinerFreres(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (!calquesParentFreres || zonesDepotAffichees) return
        if (indiceSurligne !in vues.indices) return
        peintFrere.color = jetons.accent
        peintFrere.alpha = ALPHA_FRERE
        peintFrere.strokeWidth = tailleEcran(LARGEUR_TRAIT_FRERE)
        for (indice in CalquesApercu.indicesFreres(vues, indiceSurligne)) {
            val frere = vues[indice]
            if (frere.droite <= frere.gauche || frere.bas <= frere.haut) continue
            canevas.drawRect(
                RectF(frere.gauche.toFloat(), frere.haut.toFloat(), frere.droite.toFloat(), frere.bas.toFloat()),
                peintFrere,
            )
        }
    }

    /** Calque « parent en pointillés » (PE6a) : trait à épaisseur écran constante. */
    @Suppress("ReturnCount") // Les gardes (masqué au drag, pas de parent, vue dégénérée) sont des issues DISTINCTES.
    private fun dessinerParent(canevas: Canvas, jetons: JetonsCalques) {
        if (zonesDepotAffichees) return
        val indice = if (calquesParentFreres) CalquesApercu.indiceParent(vues, indiceSurligne) else -1
        val parent = vues.getOrNull(indice) ?: return
        if (parent.droite <= parent.gauche || parent.bas <= parent.haut) return
        // PE11i (§ 3.5 `.par`) : 1,5 dp pointillé accent à 75 %.
        peintParent.strokeWidth = tailleEcran(LARGEUR_TRAIT_PARENT)
        peintParent.color = jetons.accent
        peintParent.alpha = ALPHA_MASQUE_75
        peintParent.pathEffect = EFFET_POINTILLES
        canevas.drawRect(
            RectF(parent.gauche.toFloat(), parent.haut.toFloat(), parent.droite.toFloat(), parent.bas.toFloat()),
            peintParent,
        )
    }

    /**
     * Calque « Grille 8 dp » (PE6b, miroir `.screen.grid` de la
     * maquette) : traits fins au pas de 8 dp DU PROFIL (conversion par
     * la densité du rendu), au-dessus du bitmap — le pas suit l'écran
     * simulé, pas l'appareil hôte.
     */
    private fun dessinerGrille(
        canevas: Canvas,
        jetons: JetonsCalques,
        bmp: Bitmap,
    ) {
        if (!grilleAffichee || densiteDpi <= 0) return
        val pas = PAS_GRILLE_DP * densiteDpi / DENSITE_REFERENCE
        if (pas < 2) return
        // PE11i (§ 3.5 `.screen.grid`) : accent à 18 %.
        peintGrille.color = (jetons.accent and MASQUE_COULEUR_RGB) or (ALPHA_GRILLE shl DECALAGE_ALPHA)
        peintGrille.strokeWidth = tailleEcran(LARGEUR_TRAIT_GRILLE)
        var x = 0
        while (x <= bmp.width) {
            canevas.drawLine(x.toFloat(), 0f, x.toFloat(), bmp.height.toFloat(), peintGrille)
            x += pas
        }
        var y = 0
        while (y <= bmp.height) {
            canevas.drawLine(0f, y.toFloat(), bmp.width.toFloat(), y.toFloat(), peintGrille)
            y += pas
        }
    }

    /** Calque « Bornes de toutes les vues » (PE6b, miroir `.bnd` — jeton `apercuBornes`). */
    private fun dessinerBornes(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (!calquesBornes) return
        peintBorne.color = jetons.bornes
        peintBorne.strokeWidth = tailleEcran(LARGEUR_TRAIT_BORNE)
        for (vue in vues) {
            if (vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            canevas.drawRect(
                RectF(vue.gauche.toFloat(), vue.haut.toFloat(), vue.droite.toFloat(), vue.bas.toFloat()),
                peintBorne,
            )
        }
    }

    /** Calque « Contraintes » (PE6c, miroir `drawConstraints` — jeton accent). */
    private fun dessinerContraintes(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (!calquesContraintes) return
        peintContrainte.color = jetons.accent
        peintContrainte.strokeWidth = tailleEcran(LARGEUR_TRAIT_CONTRAINTE)
        peintContrainte.pathEffect = EFFET_CONTRAINTE
        val rayonPoint = tailleEcran(RAYON_POINT_CONTRAINTE_DP)
        for (ligne in CalquesApercu.lignesContraintes(vues, densiteDpi)) {
            canevas.drawLine(
                ligne.x1.toFloat(),
                ligne.y1.toFloat(),
                ligne.x2.toFloat(),
                ligne.y2.toFloat(),
                peintContrainte,
            )
            peintPin.color = jetons.accent
            canevas.drawCircle(ligne.x1.toFloat(), ligne.y1.toFloat(), rayonPoint, peintPin)
            canevas.drawCircle(ligne.x2.toFloat(), ligne.y2.toFloat(), rayonPoint, peintPin)
            // Pill « valeur en dp » décalée de la ligne (miroir maquette).
            val milieuX = (ligne.x1 + ligne.x2) / 2f
            val milieuY = (ligne.y1 + ligne.y2) / 2f
            val decalage = tailleEcran(DECALAGE_PILL_CONTRAINTE_DP)
            if (ligne.verticale) {
                dessinerPillValeur(canevas, jetons, ligne.valeurDp, milieuX + decalage, milieuY)
            } else {
                dessinerPillValeur(canevas, jetons, ligne.valeurDp, milieuX, milieuY - decalage)
            }
        }
    }

    /**
     * Le calque RELATIONS (PE12e — spec § 8, miroir `drawRelations`) :
     * pour chaque ligne du document — trait PLEIN pour les règles de
     * côté et les bords du parent, POINTILLÉ pour les alignements, les
     * centres et les ancres — extrémités en disques 3,5 dp, trait 1,5 dp,
     * couleur accent (§ 8 : AUCUN nouveau jeton) ; l'étiquette (nom de
     * la règle, « parent », « anchor · gravité ») ne se dessine que pour
     * la vue SÉLECTIONNÉE.
     */
    private fun dessinerRelations(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (!calquesContraintes || lignesRelations.isEmpty()) return
        peintContrainte.color = jetons.accent
        peintContrainte.strokeWidth = tailleEcran(LARGEUR_TRAIT_RELATION_DP)
        val rayonPoint = tailleEcran(RAYON_POINT_RELATION_DP)
        peintEtiquette.textSize = tailleTexteEcran(TAILLE_TEXTE_ETIQUETTE_DP)
        for (relation in lignesRelations) {
            peintContrainte.pathEffect = if (relation.pointillee) EFFET_POINTILLES else null
            canevas.drawLine(
                relation.x1.toFloat(),
                relation.y1.toFloat(),
                relation.x2.toFloat(),
                relation.y2.toFloat(),
                peintContrainte,
            )
            peintPin.color = jetons.accent
            canevas.drawCircle(relation.x1.toFloat(), relation.y1.toFloat(), rayonPoint, peintPin)
            canevas.drawCircle(relation.x2.toFloat(), relation.y2.toFloat(), rayonPoint, peintPin)
            if (relation.indiceVue == indiceSurligne) {
                etiquetteRelation(canevas, jetons, relation)
            }
        }
    }

    /** L'ÉTIQUETTE d'une relation de la vue sélectionnée (miroir chip `dim`, au milieu du segment). */
    private fun etiquetteRelation(
        canevas: Canvas,
        jetons: JetonsCalques,
        relation: CalquesApercu.LigneRelation,
    ) {
        val texte = relation.etiquette ?: return
        val centreX = (relation.x1 + relation.x2) / 2f
        val centreY = (relation.y1 + relation.y2) / 2f - tailleEcran(DECALAGE_ETIQUETTE_RELATION_DP)
        val largeurTexte = peintEtiquette.measureText(texte)
        val padH = tailleEcran(MARGE_ETIQUETTE_H_DP)
        val demiHauteur = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        peintPill.color = jetons.texte
        peintEtiquette.color = jetons.fond
        dessinerEtiquetteRectangle(
            canevas,
            texte,
            centreX - largeurTexte / 2f - padH,
            centreY - demiHauteur,
        )
    }

    /**
     * Calque « Marges et padding » (PE6c, miroir `drawBoxModel` de la
     * maquette) : bandes ORANGE autour du cadre (marges, contour
     * pointillé) et bandes VERTES à l'intérieur (padding) de la
     * sélection, valeurs en dp du profil aux centres.
     */
    private fun dessinerMargesPadding(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (!calquesMargesPadding || zonesDepotAffichees || indiceSurligne !in vues.indices) return
        val vue = vues[indiceSurligne]
        if (vue.droite <= vue.gauche || vue.bas <= vue.haut) return
        dessinerBandesMarges(canevas, jetons, vue)
        dessinerBandesPadding(canevas, jetons, vue)
    }

    /** Bandes de MARGE autour du cadre — orange, contour pointillé (miroir `.mg`). */
    private fun dessinerBandesMarges(
        canevas: Canvas,
        jetons: JetonsCalques,
        vue: InfoVueRendu,
    ) {
        if (uneBandeSignificative(vue.margesHaut, vue.margesGauche, vue.margesDroite, vue.margesBas)) {
            val gauche = vue.gauche.toFloat()
            val haut = vue.haut.toFloat()
            val droite = vue.droite.toFloat()
            val bas = vue.bas.toFloat()
            // PE11i (§ 3.5 `.mg`) : aplat margeFond (alpha DANS le jeton) + liseré pointillé marge.
            peintBoxModel.color = jetons.margeFond
            peintBoxModelContour.color = jetons.marge
            peintBoxModelContour.strokeWidth = tailleEcran(LARGEUR_TRAIT_BORNE)
            peintBoxModelContour.pathEffect = EFFET_POINTILLES
            val bandes =
                listOf(
                    // haut, bas, gauche, droite — miroir drawBoxModel.
                    RectF(gauche - vue.margesGauche, haut - vue.margesHaut, droite + vue.margesDroite, haut),
                    RectF(gauche - vue.margesGauche, bas, droite + vue.margesDroite, bas + vue.margesBas),
                    RectF(gauche - vue.margesGauche, haut, gauche, bas),
                    RectF(droite, haut, droite + vue.margesDroite, bas),
                )
            for (bande in bandes) {
                if (bande.width() <= 0f || bande.height() <= 0f) continue
                canevas.drawRect(bande, peintBoxModel)
                canevas.drawRect(bande, peintBoxModelContour)
                dessinerPillValeur(
                    canevas,
                    jetons,
                    CalquesApercu.pxVersDp(
                        maxOf(bande.width(), bande.height()).toInt(),
                        densiteDpi,
                    ),
                    bande.centerX(),
                    bande.centerY(),
                )
            }
        }
    }

    /** Bandes de PADDING à l'intérieur du cadre — vertes (miroir `.pd`). */
    private fun dessinerBandesPadding(
        canevas: Canvas,
        jetons: JetonsCalques,
        vue: InfoVueRendu,
    ) {
        if (uneBandeSignificative(vue.paddingHaut, vue.paddingGauche, vue.paddingDroite, vue.paddingBas)) {
            val gauche = vue.gauche.toFloat()
            val haut = vue.haut.toFloat()
            val droite = vue.droite.toFloat()
            val bas = vue.bas.toFloat()
            // PE11i (§ 3.5 `.pd`) : aplat remplissageFond (alpha DANS le jeton).
            peintBoxModel.color = jetons.remplissageFond
            val bandes =
                listOf(
                    RectF(gauche, haut, droite, haut + vue.paddingHaut),
                    RectF(gauche, bas - vue.paddingBas, droite, bas),
                    RectF(gauche, haut + vue.paddingHaut, gauche + vue.paddingGauche, bas - vue.paddingBas),
                    RectF(droite - vue.paddingDroite, haut + vue.paddingHaut, droite, bas - vue.paddingBas),
                )
            for (bande in bandes) {
                if (bande.width() <= 0f || bande.height() <= 0f) continue
                canevas.drawRect(bande, peintBoxModel)
                dessinerPillValeur(
                    canevas,
                    jetons,
                    CalquesApercu.pxVersDp(
                        maxOf(bande.width(), bande.height()).toInt(),
                        densiteDpi,
                    ),
                    bande.centerX(),
                    bande.centerY(),
                )
            }
        }
    }

    /** Au moins UNE des quatre bandes (haut/gauche/droite/bas) est non nulle ? */
    private fun uneBandeSignificative(
        haut: Int,
        gauche: Int,
        droite: Int,
        bas: Int,
    ): Boolean = haut > 0 || gauche > 0 || droite > 0 || bas > 0

    /**
     * Calque « Problèmes » (PE6c, miroir des pins de `drawOverlay`) :
     * UNE pastille par vue fautive au coin HAUT-DROIT — ambre
     * (avertissement) ou rouge (erreur), compteur quand plusieurs
     * problèmes se partagent la vue.
     */
    private fun dessinerPastillesProblemes(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (!calquesProblemes || zonesDepotAffichees || problemes.isEmpty()) return
        val comptes = LinkedHashMap<Int, Int>()
        val indicesErreur = HashSet<Int>()
        for (probleme in problemes) {
            comptes[probleme.indiceVue] = (comptes[probleme.indiceVue] ?: 0) + 1
            if (probleme.severite == ProblemesPreview.Severite.ERREUR) {
                indicesErreur.add(probleme.indiceVue)
            }
        }
        val rayon = tailleEcran(RAYON_PIN_DP)
        peintTextePin.textSize = tailleTexteEcran(TAILLE_TEXTE_PIN_DP)
        val demiHauteur = (peintTextePin.descent() + peintTextePin.ascent()) / 2f
        for ((indice, compte) in comptes) {
            val vue = vues.getOrNull(indice)
            if (vue == null || vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            val centreX = vue.droite.toFloat()
            val centreY = vue.haut.toFloat()
            // PE11i (§ 3.5 `.pin`) : disque jeton + LISERÉ 2 dp surCalque.
            peintPin.color = if (indice in indicesErreur) jetons.epingleErreur else jetons.epingleAvert
            canevas.drawCircle(centreX, centreY, rayon, peintPin)
            peintPinLisere.color = jetons.surCalque
            peintPinLisere.strokeWidth = tailleEcran(LISE_PIN_DP)
            canevas.drawCircle(centreX, centreY, rayon, peintPinLisere)
            val texte = if (compte > 1) compte.toString() else "!"
            peintTextePin.color = jetons.surCalque
            canevas.drawText(
                texte,
                centreX - peintTextePin.measureText(texte) / 2f,
                centreY - demiHauteur,
                peintTextePin,
            )
        }
    }

    // ── Zones de dépôt : dessin Canvas (PE7b, miroir drawDrop) ──

    /**
     * Les zones de dépôt (calque EN DERNIER, miroir `drawDrop`) : TOUS
     * les conteneurs en contour pointillé violet (`.dz`), le conteneur
     * visé plein violet doux (`.dz.on`) avec sa pill « Dans X », la
     * BARRE d'insertion violette (`.slot`) avec sa pill « + Nom · i/n ».
     * Tracés à épaisseur ÉCRAN constante (patron `/ echelle`).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque absence
     * (calque coupé, cible, contenu, barre) est une issue DISTINCTE ;
     * MagicNumber — les alphas reproduisent le miroir CSS de la maquette.
     */
    @Suppress("ReturnCount")
    private fun dessinerZonesDepot(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (!zonesDepotAffichees || vues.isEmpty()) return
        // 1. Tous les conteneurs : contour pointillé 1,5 dp à 75 % (miroir `.dz` — jeton nouveau).
        peintZone.color = jetons.nouveau
        peintZone.alpha = ALPHA_MASQUE_75
        peintZone.pathEffect = EFFET_POINTILLES
        for (vue in vues) {
            if (!vue.conteneur || vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            peintZone.strokeWidth = tailleEcran(LARGEUR_TRAIT_ZONE_DP)
            canevas.drawRect(
                RectF(vue.gauche.toFloat(), vue.haut.toFloat(), vue.droite.toFloat(), vue.bas.toFloat()),
                peintZone,
            )
        }
        val cible = cibleDepot ?: return
        // 2. Le conteneur visé : fond violet doux + bordure SOLIDE (miroir `.dz.on`).
        val parent = vues.getOrNull(cible.indiceParent) ?: return
        peintZoneCible.color = jetons.nouveauDoux
        canevas.drawRect(
            RectF(parent.gauche.toFloat(), parent.haut.toFloat(), parent.droite.toFloat(), parent.bas.toFloat()),
            peintZoneCible,
        )
        peintZone.pathEffect = null
        peintZone.alpha = ALPHA_PLEIN
        peintZone.strokeWidth = tailleEcran(LARGEUR_TRAIT_ZONE_DP)
        canevas.drawRect(
            RectF(parent.gauche.toFloat(), parent.haut.toFloat(), parent.droite.toFloat(), parent.bas.toFloat()),
            peintZone,
        )
        // 3. La pill « Dans X » (miroir chip `nw` : au-dessus du conteneur, sinon sous).
        val auDessus = parent.haut - TAILLE_TEXTE_ETIQUETTE_DP * echellePolice() >= 0f
        peintEtiquette.textSize = tailleTexteEcran(TAILLE_TEXTE_ETIQUETTE_DP)
        pillInsertion(
            canevas,
            jetons,
            ZonesDepot.libelleCible(vues, cible),
            parent.gauche.toFloat(),
            centreEtiquetteBord(
                if (auDessus) parent.haut.toFloat() else parent.bas.toFloat(),
                auDessus,
            ),
        )
        // 4. Le REPÈRE DU PLAN par famille (PE12d, miroir `drawDrop` kinds
        //    `frame`/`rel`) : cellule 3 × 3 (fond violet doux, trait violet
        //    2 dp, rayon 4 dp), FANTÔME pointillé du déposé, TRAIT DE RÈGLE
        //    vers le frère, pill « + Nom · libellé » — sinon la BARRE (§ 6.2).
        val contenu = contenuGlisse ?: return
        val plan = cible.plan
        if (plan != null) {
            dessinerReperePlan(canevas, jetons, cible, plan, contenu.nomAffiche)
            return
        }
        dessinerBarreDepot(canevas, jetons, cible, contenu.nomAffiche)
    }

    /**
     * La BARRE d'insertion (miroir `.slot` : halo doux PUIS barre pleine)
     * + pill « + Nom · i/n » sous la barre — le parent à barre (§ 6.2).
     */
    private fun dessinerBarreDepot(
        canevas: Canvas,
        jetons: JetonsCalques,
        cible: ZonesDepot.CibleDepot,
        nomWidget: String,
    ) {
        val epaisseur = tailleEcran(EPAISSEUR_BARRE_DP)
        val barre = ZonesDepot.emplacement(vues, cible, densiteDpi, epaisseur) ?: return
        val halo = tailleEcran(HALO_BARRE_DP)
        peintBarreDepot.color = jetons.nouveauDoux
        val rectHalo = RectF(barre.gauche - halo, barre.haut - halo, barre.droite + halo, barre.bas + halo)
        canevas.drawRect(rectHalo, peintBarreDepot)
        peintBarreDepot.color = jetons.nouveau
        canevas.drawRect(RectF(barre.gauche, barre.haut, barre.droite, barre.bas), peintBarreDepot)
        pillInsertion(
            canevas,
            jetons,
            ZonesDepot.libelleInsertion(vues, cible, nomWidget),
            barre.gauche,
            centreEtiquetteBord(
                barre.bas + tailleEcran(DECALAGE_PILL_BARRE_DP),
                auDessus = false,
            ),
        )
    }

    /**
     * Le REPÈRE D'UN PLAN PAR FAMILLE (PE12d — miroir `drawDrop`, kinds
     * `frame` et `rel`) : la vue ANCRE marquée (dz on), la CELLULE du plan
     * (sur le parent, ou sur l'ancre SANS padding — miroir
     * `cellRect(S.b[h.anchor], PAD0, …)`), le FANTÔME pointillé du déposé,
     * le TRAIT DE RÈGLE du fantôme vers le frère visé (trait plein 1,5 dp,
     * extrémités 3 dp — miroir `relLine`) et la pill « + Nom · libellé »
     * sous la cellule (frame) ou le fantôme (rel).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque absence
     * (ancre, cellule, pill) est une issue DISTINCTE du dessin partiel.
     */
    @Suppress("ReturnCount")
    private fun dessinerReperePlan(
        canevas: Canvas,
        jetons: JetonsCalques,
        cible: ZonesDepot.CibleDepot,
        plan: PlanInsertionFamille.Plan,
        nomWidget: String,
    ) {
        // 1. La vue ANCRE d'un `FloatingActionButton` : marquée comme le
        //    conteneur visé (miroir `box('dz on', …)` sur l'ancre).
        val ancre = plan.indiceAncre?.let { vues.getOrNull(it) }
        if (ancre != null) {
            peintZoneCible.color = jetons.nouveauDoux
            canevas.drawRect(
                RectF(ancre.gauche.toFloat(), ancre.haut.toFloat(), ancre.droite.toFloat(), ancre.bas.toFloat()),
                peintZoneCible,
            )
        }
        // 2. La CELLULE du plan : fond violet doux + trait violet 2 dp, à
        //    COINS ARRONDIS 4 dp (miroir `.cell`).
        val cellule =
            plan.cellule?.let {
                PlanInsertionFamille.rectangleCellule(vues, cible.indiceParent, it, plan.indiceAncre)
            }
        if (cellule != null) {
            val rect = RectF(cellule.gauche, cellule.haut, cellule.droite, cellule.bas)
            val rayon = tailleEcran(RAYON_CELLULE_DP)
            peintZoneCible.color = jetons.nouveauDoux
            canevas.drawRoundRect(rect, rayon, rayon, peintZoneCible)
            peintZone.pathEffect = null
            peintZone.alpha = ALPHA_PLEIN
            peintZone.color = jetons.nouveau
            peintZone.strokeWidth = tailleEcran(TRAIT_CELLULE_DP)
            canevas.drawRoundRect(rect, rayon, rayon, peintZone)
        }
        // 3. Le FANTÔME du déposé : fond violet doux + trait pointillé
        //    1,5 dp (miroir `.gbox`).
        val fantome = plan.fantome
        if (fantome != null) {
            val rect = RectF(fantome.gauche, fantome.haut, fantome.droite, fantome.bas)
            peintZoneCible.color = jetons.nouveauDoux
            canevas.drawRect(rect, peintZoneCible)
            peintZone.pathEffect = EFFET_POINTILLES
            peintZone.color = jetons.nouveau
            peintZone.strokeWidth = tailleEcran(TRAIT_FANTOME_DP)
            canevas.drawRect(rect, peintZone)
        }
        // 4. Le TRAIT DE RÈGLE vers le frère visé (miroir `relLine` : trait
        //    plein, extrémités 3 dp, la RÈGLE PRINCIPALE = premier attribut).
        val frere = plan.indiceReference?.let { vues.getOrNull(it) }
        if (frere != null && fantome != null && plan.traitRegle) {
            peintZoneCible.color = jetons.nouveauDoux
            canevas.drawRect(
                RectF(frere.gauche.toFloat(), frere.haut.toFloat(), frere.droite.toFloat(), frere.bas.toFloat()),
                peintZoneCible,
            )
            dessinerTraitRegle(canevas, jetons, plan, fantome, frere)
        }
        // 5. La pill « + Nom · libellé » sous la CELLULE (frame) ou le
        //    FANTÔME (rel) — miroir `chip(ov, 'nw', '+ ' + dv.w.name + ' · ' + h.text, …)`.
        val ancrePill = cellule ?: fantome ?: return
        peintEtiquette.textSize = tailleTexteEcran(TAILLE_TEXTE_ETIQUETTE_DP)
        pillInsertion(
            canevas,
            jetons,
            "+ $nomWidget · ${plan.libelle}",
            ancrePill.gauche,
            centreEtiquetteBord(ancrePill.bas + tailleEcran(DECALAGE_PILL_BARRE_DP), auDessus = false),
        )
    }

    /**
     * Le trait de règle du FANTÔME vers le FRÈRE (miroir `relLine` :
     * `below` → du haut du fantôme au bas du frère, `above` → du bas au
     * haut, `toEndOf` → de la gauche à la droite, `toStartOf` → l'inverse).
     */
    private fun dessinerTraitRegle(
        canevas: Canvas,
        jetons: JetonsCalques,
        plan: PlanInsertionFamille.Plan,
        fantome: ZonesDepot.RectangleDepot,
        frere: InfoVueRendu,
    ) {
        val regle =
            plan.attributs
                .firstOrNull()
                ?.first
                ?.substringAfterLast(':') ?: return
        val xFantome = (fantome.gauche + fantome.droite) / 2f
        val yFantome = (fantome.haut + fantome.bas) / 2f
        val ligne =
            when (regle) {
                "below" -> LigneRegle(xFantome, fantome.haut, xFantome, frere.bas.toFloat())
                "above" -> LigneRegle(xFantome, fantome.bas, xFantome, frere.haut.toFloat())
                "toEndOf" -> LigneRegle(fantome.gauche, yFantome, frere.droite.toFloat(), yFantome)
                else -> LigneRegle(fantome.droite, yFantome, frere.gauche.toFloat(), yFantome)
            }
        peintZone.pathEffect = null
        peintZone.alpha = ALPHA_PLEIN
        peintZone.color = jetons.nouveau
        peintZone.strokeWidth = tailleEcran(TRAIT_FANTOME_DP)
        canevas.drawLine(ligne.x1, ligne.y1, ligne.x2, ligne.y2, peintZone)
        peintPin.color = jetons.nouveau
        val rayon = tailleEcran(RAYON_POINT_REGLE_DP)
        canevas.drawCircle(ligne.x1, ligne.y1, rayon, peintPin)
        canevas.drawCircle(ligne.x2, ligne.y2, rayon, peintPin)
    }

    /** Les extrémités d'un trait de règle (dessin — pas de quadruplet en Kotlin). */
    private class LigneRegle(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    )

    /**
     * Pill d'insertion (miroir `.lab.nw` : fond `nouveau`, texte
     * `surNouveau`), ancrée au bord gauche — le centre vertical est
     * calculé par l'appelant ([centreEtiquetteBord]).
     */
    private fun pillInsertion(
        canevas: Canvas,
        jetons: JetonsCalques,
        texte: String,
        gaucheX: Float,
        centreY: Float,
    ) {
        if (texte.isEmpty()) return
        peintPill.color = jetons.nouveau
        peintEtiquette.color = jetons.surNouveau
        dessinerEtiquetteRectangle(canevas, texte, gaucheX, centreY)
    }

    /**
     * Le centre VERTICAL d'une étiquette posée au BORD d'un conteneur
     * (AU-DESSUS sinon dessous) — la taille de texte doit être posée.
     */
    private fun centreEtiquetteBord(
        bordY: Float,
        auDessus: Boolean,
    ): Float {
        val demiHauteur = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        val margeV = tailleEcran(MARGE_ETIQUETTE_V_DP)
        return if (auDessus) bordY - demiHauteur * 2f - margeV * 2f else bordY + demiHauteur + margeV
    }

    /** Pill « valeur » (dp des box-models/contraintes — `.lab.dim`) centrée sur le point. */
    private fun dessinerPillValeur(
        canevas: Canvas,
        jetons: JetonsCalques,
        valeurDp: Int,
        centreX: Float,
        centreY: Float,
    ) {
        if (valeurDp <= 0) return
        peintEtiquette.textSize = tailleTexteEcran(TAILLE_TEXTE_ETIQUETTE_DP)
        val largeurTexte = peintEtiquette.measureText(valeurDp.toString())
        val padH = tailleEcran(MARGE_ETIQUETTE_H_DP)
        peintPill.color = jetons.texte
        peintEtiquette.color = jetons.fond
        dessinerEtiquetteRectangle(
            canevas,
            valeurDp.toString(),
            centreX - largeurTexte / 2f - padH,
            centreY,
        )
    }

    /**
     * L'étiquette RECTANGLE commune (PE11i, miroir `.lab` § 3.5) :
     * rayon 4, padding 3 / 6, mono 11/500 — les COULEURS viennent des
     * peintures posées par l'appelant (idiome du moteur :
     * accent/surCalque pour la sélection, texte/fond pour les cotes,
     * nouveau/surNouveau pour l'insertion).
     */
    private fun dessinerEtiquetteRectangle(
        canevas: Canvas,
        texte: String,
        gauche: Float,
        centreY: Float,
    ) {
        peintEtiquette.textSize = tailleTexteEcran(TAILLE_TEXTE_ETIQUETTE_DP)
        val largeurTexte = peintEtiquette.measureText(texte)
        val demiHauteur = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        val padV = tailleEcran(MARGE_ETIQUETTE_V_DP)
        val padH = tailleEcran(MARGE_ETIQUETTE_H_DP)
        val rayon = tailleEcran(RAYON_ETIQUETTE_DP)
        canevas.drawRoundRect(
            RectF(
                gauche,
                centreY - demiHauteur - padV,
                gauche + largeurTexte + 2 * padH,
                centreY + demiHauteur + padV,
            ),
            rayon,
            rayon,
            peintPill,
        )
        canevas.drawText(texte, gauche + padH, centreY - demiHauteur, peintEtiquette)
    }

    /**
     * Overlay de sélection (P6, étendu PE6a) : cadre sur les bounds de
     * la vue surlignée, POIGNÉES d'angle, étiquette « nom · dimensions
     * en dp » au-dessus — tout à épaisseur/taille ÉCRAN constante.
     */
    private fun dessinerSurlignage(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        if (zonesDepotAffichees) return
        if (indiceSurligne !in vues.indices) return
        val vue = vues[indiceSurligne]
        val rect =
            RectF(
                vue.gauche.toFloat(),
                vue.haut.toFloat(),
                vue.droite.toFloat(),
                vue.bas.toFloat(),
            )
        // PE11i (§ 3.5 `.sel`) : cadre 2 dp accent + aplat accentDoux.
        peintSurlignage.strokeWidth = tailleEcran(LARGEUR_TRAIT_SURLIGNAGE)
        peintSurlignage.color = jetons.accent
        canevas.drawRect(rect, peintSurlignage)
        peintFondSurlignage.color = jetons.accentDoux
        canevas.drawRect(rect, peintFondSurlignage)
        dessinerPoignees(canevas, jetons, rect)
        // PE11l : pendant un redimensionnement, l'étiquette de SÉLECTION
        // cède la place à celle du FANTÔME (les dimensions en direct —
        // deux pills superposées ne se liraient pas).
        if (redimensionAncre == null) dessinerEtiquette(canevas, jetons, rect, vue)
    }

    /** Poignées d'angle (PE11i, § 3.5 `.sel .h`) : CARRÉ 9 dp centré sur le coin. */
    private fun dessinerPoignees(
        canevas: Canvas,
        jetons: JetonsCalques,
        rect: RectF,
    ) {
        val demi = tailleEcran(TAILLE_POIGNEE_DP) / 2f
        val rayon = tailleEcran(RAYON_COIN_POIGNEE_DP)
        peintTraitPoignee.strokeWidth = tailleEcran(TRAIT_POIGNEE_DP)
        for (coin in COINS) {
            val x = if (coin.first) rect.left else rect.right
            val y = if (coin.second) rect.top else rect.bottom
            val carre = RectF(x - demi, y - demi, x + demi, y + demi)
            peintPoignee.color = jetons.surface
            canevas.drawRoundRect(carre, rayon, rayon, peintPoignee)
            peintTraitPoignee.color = jetons.accent
            canevas.drawRoundRect(carre, rayon, rayon, peintTraitPoignee)
        }
    }

    /**
     * Le FANTÔME de redimensionnement (PE11l) : rectangle pointillé accent
     * du coin ANCRE au point courant + pill « largeur×hauteur dp » en
     * direct — RIEN n'est écrit avant le relâchement (le fantôme vit
     * dans le TRACÉ, pas dans le document : un rendu par édition
     * écrite, règle du brief PE11). PE14-S2 (C4) : le relâchement GARDE
     * la cible affichée jusqu'au rendu suivant, puis la fond sur
     * [RedimensionApercu.DUREE_FONDU_FANTOME_MS] — le panneau n'a
     * jamais d'instant vide.
     */
    private fun dessinerRedimensionnement(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        val ancre = redimensionAncre
        if (ancre == null) {
            dessinerFantomePersistant(canevas, jetons)
            return
        }
        val rect = rectangleFantome() ?: return
        dessinerFantome(canevas, jetons, rect, alpha = 1f)
    }

    /**
     * Le fantôme PERSISTANT (C4) : dessiné PLEIN jusqu'au rendu suivant
     * ([afficherRendu] pose le départ du fondu), puis fond — l'alpha
     * dérive de l'écart mesuré ([RedimensionApercu.alphaFantome], pur) ;
     * la boucle de dessin s'auto-réinvalide tant que le fondu vit, et
     * nettoie les champs à son terme (jamais de résidu).
     */
    private fun dessinerFantomePersistant(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        val rect = fantomePersistant ?: return
        val depart = debutFonduFantome
        val alpha =
            if (depart == null) {
                1f
            } else {
                RedimensionApercu.alphaFantome(SystemClock.uptimeMillis() - depart)
            }
        if (alpha <= 0f) {
            effacerFantomePersistant()
            return
        }
        dessinerFantome(canevas, jetons, rect, alpha)
        if (depart != null) postInvalidateOnAnimation()
    }

    /**
     * Le TRACÉ commun du fantôme (geste vivant ou persistant) : rectangle
     * pointillé accent + pill dp — l'alpha porte le fondu (C4), posé sur
     * les peintures puis RESTAURÉ (elles sont partagées avec le plan).
     */
    private fun dessinerFantome(
        canevas: Canvas,
        jetons: JetonsCalques,
        rect: RectF,
        alpha: Float,
    ) {
        peintPlan.color = jetons.accent
        peintPlan.alpha = (alpha * ALPHA_PLEIN).toInt()
        peintPlan.pathEffect = EFFET_POINTILLES
        peintPlan.strokeWidth = tailleEcran(LARGEUR_TRAIT_SURLIGNAGE)
        canevas.drawRect(rect, peintPlan)
        peintPlan.pathEffect = null
        // La pill dp en direct (mêmes peintures que l'étiquette de
        // sélection — miroir le chip `dim` du box-model) ; SANS cote
        // lisible (densité inconnue) le fantôme reste muet.
        val dimensions =
            CalquesApercu.dimensions(
                largeurPx = Math.round(rect.width()),
                hauteurPx = Math.round(rect.height()),
                densiteDpi = densiteDpi,
            )
        if (dimensions.isNotBlank()) {
            val padH = tailleEcran(MARGE_ETIQUETTE_H_DP)
            val padV = tailleEcran(MARGE_ETIQUETTE_V_DP)
            val xPill = (rect.left - padH).coerceAtLeast(0f)
            val centreY = (rect.top - tailleEcran(TAILLE_POIGNEE_DP) - padV).coerceAtLeast(padV)
            peintPill.color = jetons.accent
            peintPill.alpha = (alpha * ALPHA_PLEIN).toInt()
            peintEtiquette.color = jetons.surCalque
            peintEtiquette.alpha = (alpha * ALPHA_PLEIN).toInt()
            dessinerEtiquetteRectangle(canevas, dimensions, xPill, centreY)
            peintPill.alpha = ALPHA_PLEIN
            peintEtiquette.alpha = ALPHA_PLEIN
        }
        peintPlan.alpha = ALPHA_PLEIN
    }

    /**
     * POSE le fantôme persistant du relâchement (C4) : le rectangle cible
     * du geste reste affiché PLEIN jusqu'au prochain rendu
     * ([afficherRendu] amorce le fondu).
     */
    private fun poserFantomePersistant(cible: RedimensionApercu.CibleRedimension) {
        val (hautGauche, basDroite) =
            RedimensionApercu.rectangle(
                ancreX = cible.ancreX,
                ancreY = cible.ancreY,
                pointX = cible.coinX,
                pointY = cible.coinY,
            )
        fantomePersistant =
            RectF(
                hautGauche.first.toFloat(),
                hautGauche.second.toFloat(),
                basDroite.first.toFloat(),
                basDroite.second.toFloat(),
            )
        debutFonduFantome = null
    }

    /** EFFACE le fantôme persistant (fin de fondu, nouveau geste, échec, réinitialisation). */
    private fun effacerFantomePersistant() {
        if (fantomePersistant == null) return
        fantomePersistant = null
        debutFonduFantome = null
    }

    /**
     * Le fantôme PERSISTANT est-il affiché ? (PE14-S2 C4 — sonde interne
     * de test : le tracé vit dans `onDraw`, la machine d'état se vérifie
     * par son effet visible — posé au relâchement, fondu au rendu
     * suivant, parti au terme du fondu.)
     */
    internal fun fantomePersistantAffiche(): Boolean = fantomePersistant != null

    /**
     * Le RECTANGLE du fantôme (px contenu) — de l'ancre au point courant
     * COMPENSÉ de la préhension (C1 : la machine ne stocke QUE des
     * points compensés), `null` hors geste.
     */
    private fun rectangleFantome(): RectF? {
        val ancre = redimensionAncre
        val point = redimensionPoint
        return if (ancre == null || point == null) {
            null
        } else {
            val (hautGauche, basDroite) =
                RedimensionApercu.rectangle(
                    ancreX = ancre.first,
                    ancreY = ancre.second,
                    pointX = point.first,
                    pointY = point.second,
                )
            RectF(
                hautGauche.first.toFloat(),
                hautGauche.second.toFloat(),
                basDroite.first.toFloat(),
                basDroite.second.toFloat(),
            )
        }
    }

    /** Étiquette « nom · dimensions en dp » (PE11i, `.lab`) au-dessus du cadre, bornée au contenu. */
    private fun dessinerEtiquette(
        canevas: Canvas,
        jetons: JetonsCalques,
        rect: RectF,
        vue: InfoVueRendu,
    ) {
        val texte =
            CalquesApercu.libelleSelection(
                vue.libelle,
                CalquesApercu.dimensions(vue.droite - vue.gauche, vue.bas - vue.haut, densiteDpi),
            )
        if (texte.isBlank()) return
        // Rectangle au-dessus du cadre (décalé d'une poignée), borné à gauche.
        val padH = tailleEcran(MARGE_ETIQUETTE_H_DP)
        val padV = tailleEcran(MARGE_ETIQUETTE_V_DP)
        val xPill = (rect.left - padH).coerceAtLeast(0f)
        val centreY = (rect.top - tailleEcran(TAILLE_POIGNEE_DP) - padV).coerceAtLeast(padV)
        peintPill.color = jetons.accent
        peintEtiquette.color = jetons.surCalque
        dessinerEtiquetteRectangle(canevas, texte, xPill, centreY)
    }

    /**
     * Le DAMIER (PE11i, § 3.4) : fond `apercuSurface2`, cases alternées
     * de **8 dp** `apercuDamierCase` (identique clair et nuit), origine
     * (0, 0) — cases en PIXELS ÉCRAN (le damier vit AVANT le zoom).
     */
    private fun dessinerDamier(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        peintFondCanvas.color = jetons.surface2
        canevas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), peintFondCanvas)
        val cote = TAILLE_CASE_DAMIER_DP * resources.displayMetrics.density
        if (cote <= 0f) return
        peintCaseDamier.color = jetons.damierCase
        var y = 0f
        var rangee = 0
        while (y < height) {
            var x = 0f
            var colonne = 0
            while (x < width) {
                if ((rangee + colonne) % 2 == 0) {
                    canevas.drawRect(x, y, x + cote, y + cote, peintCaseDamier)
                }
                x += cote
                colonne += 1
            }
            y += cote
            rangee += 1
        }
    }

    private fun dessinerAccueil(
        canevas: Canvas,
        jetons: JetonsCalques,
    ) {
        val libelle = context.getString(R.string.preview_accueil)
        peintTexteEtat.color = jetons.texteDoux
        peintTexteEtat.textAlign = Paint.Align.CENTER
        canevas.drawText(libelle, width / 2f, height / 2f, peintTexteEtat)
        peintTexteEtat.textAlign = Paint.Align.LEFT
    }

    /**
     * La LÉGENDE de planche (PE11i, miroir `.cap` de la maquette —
     * § 3.4) : « DESIGN · PIXEL 4 » — mono 11/500, capitales,
     * interlettrage 0,04 em, `apercuTexteDoux`, 22 dp AU-DESSUS de la
     * planche, alignée à gauche. Dessinée dans l'espace CONTENU (sous
     * le zoom, comme la planche qu'elle nomme).
     */
    private fun dessinerLegendePlanche(
        canevas: Canvas,
        jetons: JetonsCalques,
        decalageX: Int,
        estPlan: Boolean,
    ) {
        if (libelleProfil.isBlank()) return
        val libellePlanche =
            if (estPlan) {
                context.getString(R.string.preview_planche_plan)
            } else {
                context.getString(R.string.preview_planche_design)
            }
        val libelle = (libellePlanche + " " + LIBELLE_LEGENDE + " " + libelleProfil).uppercase(Locale.ROOT)
        peintLegende.textSize = tailleTexteEcran(TAILLE_TEXTE_LEGENDE_DP)
        peintLegende.color = jetons.texteDoux
        canevas.drawText(libelle, decalageX.toFloat(), -tailleEcran(RETRAIT_LEGENDE_DP), peintLegende)
    }

    /** Réajuste zoom et décalage pour que le contenu tienne dans la vue (Les deux : les DEUX planches). */
    private fun ajusterALaVue() {
        val bmp = bitmap ?: return
        echelle =
            CalculsVuePreview.echelleAjustee(
                largeurContenu = largeurContenu(),
                hauteurContenu = bmp.height,
                largeurVue = width,
                hauteurVue = height,
            )
        decalageX = (width - largeurContenu() * echelle) / 2f
        decalageY = (height - bmp.height * echelle) / 2f
        // v0.166.5 : un ajustement délivré = la planche est POSÉE — le
        // prochain resize (la feuille, la rotation) conserve le zoom.
        plancheAjustee = true
    }

    /** Maintient le contenu visible pendant le pan. */
    private fun bornerPan() {
        val bmp = bitmap ?: return
        decalageX = CalculsVuePreview.bornerDecalage(decalageX, width.toFloat(), largeurContenu() * echelle)
        decalageY = CalculsVuePreview.bornerDecalage(decalageY, height.toFloat(), bmp.height * echelle)
    }

    /** Échelle de police (densité × fontScale — remplace scaledDensity déprécié API 34). */
    private fun echellePolice(): Float = resources.displayMetrics.density * resources.configuration.fontScale

    /**
     * Une cote dp ÉCRAN constante, convertie en unités CONTENU
     * (§ 3.5 : densité / zoom — la cote garde sa taille visuelle à
     * l'écran quel que soit le zoom).
     */
    private fun tailleEcran(dp: Float): Float = dp * resources.displayMetrics.density / echelle

    /**
     * Une TAILLE DE TEXTE dp écran constante : densité × fontScale
     * (patron existant — la police reste lisible en grand).
     */
    private fun tailleTexteEcran(dp: Float): Float = dp * echellePolice() / echelle

    public companion object {
        /** Cases du damier, en dp ÉCRAN (PE11i, § 3.4 : 8 dp, avant le zoom). */
        private const val TAILLE_CASE_DAMIER_DP = 8f

        private const val TAILLE_TEXTE_ETAT_DP = 12f

        /** Échelle de repli (interne : assertée par VuePreviewXmlTest). */
        internal const val ECHELLE_REPLI = 0.1f

        /** Largeur du trait de sélection et du squelette plein, en dp écran (§ 3.5). */
        private const val LARGEUR_TRAIT_SURLIGNAGE = 2f

        /** Masque RGB (privé du canal alpha) pour poser l'alpha d'un jeton. */
        private const val MASQUE_COULEUR_RGB = 0x00FFFFFF

        /** Position du canal alpha dans un ARGB int. */
        private const val DECALAGE_ALPHA = 24

        /** Alpha du BORD des frères (§ 3.5 `.sib` : accent à 35 %). */
        private const val ALPHA_FRERE = 0x59

        /** Alpha des contours « 75 % » (§ 3.5 : parent `.par`, zones `.dz`). */
        private const val ALPHA_MASQUE_75 = 0xBF

        /** Alpha opaque complet (bord de la zone visée `.dz.on`). */
        private const val ALPHA_PLEIN = 0xFF

        /** Alpha de la grille (§ 3.5 `.screen.grid` : accent à 18 %). */
        private const val ALPHA_GRILLE = 0x2E

        /** Taille du texte des étiquettes `.lab`, en dp écran (mono 11/500, § 3.5). */
        private const val TAILLE_TEXTE_ETIQUETTE_DP = 11f

        /** Padding VERTICAL des étiquettes `.lab` (§ 3.5 : 3 dp). */
        private const val MARGE_ETIQUETTE_V_DP = 3f

        /** Padding HORIZONTAL des étiquettes `.lab` (§ 3.5 : 6 dp). */
        private const val MARGE_ETIQUETTE_H_DP = 6f

        /** Rayon des étiquettes `.lab` (§ 3.5 : rectangle rayon 4). */
        private const val RAYON_ETIQUETTE_DP = 4f

        /** Poignées CARRÉES `.sel .h` : côté 9 dp, rayon 2, trait 2 dp (§ 3.5). */
        private const val TAILLE_POIGNEE_DP = 9f
        private const val RAYON_COIN_POIGNEE_DP = 2f
        private const val TRAIT_POIGNEE_DP = 2f

        /**
         * La cible TACTILE de saisie d'une poignée (PE11l) : rayon 18 dp
         * écran (36 dp de cible — dérogation ADR 0133 ≥ 24 dp ; le carré
         * VISUEL de 9 dp reste la référence de la maquette, la saisie
         * dépasse : un doigt à 9 dp ne tient pas).
         */
        private const val RAYON_SAISIE_POIGNEE_DP = 18f

        /**
         * Le seuil de DÉRIVE d'un contact sur poignée (PE11l) : 6 dp écran
         * — en dessous, le relâchement est un CONTACT simple, rien ne
         * s'écrit (miroir le `moved > 6` du tap-canvas de la maquette).
         */
        private const val SEUIL_DERIVE_DP = 6f

        /** Légende de planche `.cap` : texte 11 dp, 22 dp au-dessus, interlettrage 0,04 em (§ 3.4). */
        private const val TAILLE_TEXTE_LEGENDE_DP = 11f
        private const val RETRAIT_LEGENDE_DP = 22f
        private const val INTERLETTRE_LEGENDE_EM = 0.04f

        /** Séparateur de la légende (miroir `·` de `.cap`). */
        private const val LIBELLE_LEGENDE = "·"

        /** Les quatre coins d'une poignée (haut-gauche, haut-droit, bas-gauche, bas-droit). */
        private val COINS =
            listOf(
                true to true,
                false to true,
                true to false,
                false to false,
            )

        /** Pointillés du parent — 4 px tracés, 4 px troués, à épaisseur écran constante. */
        private val EFFET_POINTILLES = DashPathEffect(floatArrayOf(4f, 4f), 0f)

        // ── Mode d'affichage PE10d/e (miroir .screen.bp / world gap) ──

        /** L'écart entre les planches Design et Plan, en px contenu (miroir gap 40 px du monde). */
        private const val ECART_PLANCHES = 40

        /** Largeur du trait du squelette PLAN, en dp écran (§ 3.4 : contours pointillés 1 dp). */
        private const val LARGEUR_TRAIT_PLAN = 1f

        /** Trait du PARENT pointillé `.par`, en dp écran (§ 3.5 : 1,5 dp). */
        private const val LARGEUR_TRAIT_PARENT = 1.5f

        /** Trait du BORD des frères `.sib`, en dp écran (§ 3.5 : 1 dp). */
        private const val LARGEUR_TRAIT_FRERE = 1f

        /** Pointillés du squelette — 6 px tracés, 4 px troués. */
        private val EFFET_PLAN = DashPathEffect(floatArrayOf(6f, 4f), 0f)

        // ── Ombre « flottante » PE10a (miroir box-shadow de `.screen`) ──

        /** Rayon maximal de l'ombre, en dp écran (miroir blur 24 px). */
        private const val RAYON_OMBRE_DP = 12f

        /** Descente verticale de l'ombre, en dp écran (miroir dy 6 px). */
        private const val DESCENTE_OMBRE_DP = 3f

        /** Le nombre de halos concentriques (dégradé discret). */
        private const val PASSES_OMBRE = 3

        /** Liseré du contour de l'écran rendu, en dp écran (jeton ecranTrait). */
        private const val TRAIT_LISERE_ECRAN_DP = 1f

        // ── Zones de dépôt PE7b (miroir des jetons de la maquette — PE11i : jetons) ──

        /** Largeur du trait des zones, en dp écran (miroir 1,5 px de `.dz`). */
        private const val LARGEUR_TRAIT_ZONE_DP = 1.5f

        /** Épaisseur de la barre d'insertion, en dp écran (miroir 4 px de `slotRect`). */
        private const val EPAISSEUR_BARRE_DP = 4f

        /** Halo de la barre (miroir box-shadow 3 px de `.slot`). */
        private const val HALO_BARRE_DP = 3f

        /** Décalage de la pill d'insertion sous la barre, en dp écran (miroir 6 px). */
        private const val DECALAGE_PILL_BARRE_DP = 6f

        // ── Repères de plan PE12d (miroir `.cell`, `.gbox`, `relLine` — ADR 0135) ──

        /** Trait de la CELLULE du plan, en dp écran (miroir 2 px de `.cell`). */
        private const val TRAIT_CELLULE_DP = 2f

        /** Rayon des COINS de la cellule, en dp écran (miroir 4 px de `.cell`). */
        private const val RAYON_CELLULE_DP = 4f

        /** Trait du FANTÔME du déposé, en dp écran (miroir 1,5 px de `.gbox`). */
        private const val TRAIT_FANTOME_DP = 1.5f

        /** Rayon des EXTRÉMITÉS du trait de règle, en dp écran (miroir `3 / z` de `relLine`). */
        private const val RAYON_POINT_REGLE_DP = 3f

        // ── Calque Relations PE12e (spec § 8 : trait 1,5 dp, disques 3,5 dp — accent) ──

        /** Trait d'une ligne de relation, en dp écran (miroir `1.5 / z` de `drawRelations`). */
        private const val LARGEUR_TRAIT_RELATION_DP = 1.5f

        /** Rayon des EXTRÉMITÉS d'une relation, en dp écran (miroir `3.5 / z`). */
        private const val RAYON_POINT_RELATION_DP = 3.5f

        /** Décalage de l'étiquette AU-DESSUS du milieu du segment (miroir `11 / z`). */
        private const val DECALAGE_ETIQUETTE_RELATION_DP = 11f

        // ── Calques PE6b/PE6c (miroir des jetons de la maquette) ──

        /** Densité de référence Android (dpi) — facteur des conversions dp. */
        private const val DENSITE_REFERENCE = 160

        /** Pas de la grille, en dp du profil (PE6b — « Grille 8 dp »). */
        private const val PAS_GRILLE_DP = 8

        /** Largeur du trait de grille, en dp écran. */
        private const val LARGEUR_TRAIT_GRILLE = 1f

        /** Largeur du trait des bornes, en dp écran. */
        private const val LARGEUR_TRAIT_BORNE = 1f

        /** Largeur du trait de contrainte (miroir 1,5 px de `drawConstraints`). */
        private const val LARGEUR_TRAIT_CONTRAINTE = 1.5f

        /** Pointillés des contraintes — 5 px tracés, 3 px troués (miroir maquette). */
        private val EFFET_CONTRAINTE = DashPathEffect(floatArrayOf(5f, 3f), 0f)

        /** Rayon des points d'extrémité de contrainte, en dp écran (miroir 3,5 px). */
        private const val RAYON_POINT_CONTRAINTE_DP = 3.5f

        /** Décalage de la pill de valeur depuis la ligne, en dp écran (miroir 11-14 px). */
        private const val DECALAGE_PILL_CONTRAINTE_DP = 12f

        /** Rayon d'une pastille de problème, en dp écran (miroir 18 px de diamètre). */
        private const val RAYON_PIN_DP = 9f

        /** Liseré blanc des pastilles, en dp écran (miroir bordure 2 px). */
        private const val LISE_PIN_DP = 2f

        /** Taille du texte des pastilles, en dp écran (miroir 11 px). */
        private const val TAILLE_TEXTE_PIN_DP = 11f
    }
}

/** Calculs de zoom/pan/sélection de la vue — fonctions pures (testées sans Android). */
internal object CalculsVuePreview {
    /**
     * Coordonnées BITMAP (contenu) d'un point de la vue : zoom/pan
     * INVERSÉS (le hit-test P6 se fait dans le repère du rendu).
     */
    internal fun coordonneesContenu(
        xVue: Float,
        yVue: Float,
        decalageX: Float,
        decalageY: Float,
        echelle: Float,
    ): Pair<Float, Float> =
        if (echelle <= 0f) {
            Pair(0f, 0f)
        } else {
            Pair((xVue - decalageX) / echelle, (yVue - decalageY) / echelle)
        }

    /** Échelle ajustée (fit) : le contenu ENTIER tient dans la vue ; ≥ 0,1. */
    internal fun echelleAjustee(
        largeurContenu: Int,
        hauteurContenu: Int,
        largeurVue: Int,
        hauteurVue: Int,
    ): Float {
        val contenuInvalide = largeurContenu <= 0 || hauteurContenu <= 0
        val vueInvalide = largeurVue <= 0 || hauteurVue <= 0
        if (contenuInvalide || vueInvalide) return 1f
        return min(
            largeurVue.toFloat() / largeurContenu,
            hauteurVue.toFloat() / hauteurContenu,
        ).coerceAtLeast(ECHELLE_MINIMALE)
    }

    /** Échelle bornée (pincement) : 0,25× … 6×. */
    internal fun bornerEchelle(echelle: Float): Float = echelle.coerceIn(ECHELLE_MINIMALE_PINCEMENT, ECHELLE_MAXIMALE)

    /**
     * Le re-mappage PLANCHE (PE10e — miroir `toDesign` qui vise toujours
     * la planche [0]) : un point au-delà de [decalagePlanche] (la planche
     * Plan des « Les deux ») revient au MÊME point de la planche design —
     * l'arbre rendu est identique. `decalagePlanche <= 0` : mode à une
     * planche, aucune conversion.
     */
    internal fun coordonneesPlanche(
        xContenu: Float,
        yContenu: Float,
        decalagePlanche: Float,
    ): Pair<Float, Float> =
        if (decalagePlanche > 0f && xContenu >= decalagePlanche) {
            Pair(xContenu - decalagePlanche, yContenu)
        } else {
            Pair(xContenu, yContenu)
        }

    /**
     * Le décalage d'AFFICHAGE de la planche Plan (PE11l — bug rapporté
     * par le propriétaire : « corriger le rendu plan ») : la maquette
     * pose la planche bp à L'ORIGINE quand elle est SEULE
     * (`S.mode === 'bp' ? [true]` — UNE planche, position 0) et ne la
     * décale que du mode « Les deux » (`[false, true]` : design puis
     * plan). Le fit cadre [largeurContenu] — une planche Plan dessinée
     * au-delà de sa propre largeur sortirait du cadre (écran vide).
     */
    internal fun decalagePlancheAffichage(
        mode: ModeApercu,
        largeurPlanchePx: Int,
        ecartPx: Int,
    ): Int =
        if (mode == ModeApercu.LES_DEUX) {
            largeurPlanchePx + ecartPx
        } else {
            0
        }

    /**
     * Décalage borné : le contenu ne fuit pas hors de la vue — contenu
     * plus PETIT que la vue : centré (décalage fixé) ; plus grand : au
     * moins un bord reste visible.
     */
    internal fun bornerDecalage(
        decalage: Float,
        tailleVue: Float,
        tailleContenu: Float,
    ): Float =
        if (tailleContenu <= tailleVue) {
            (tailleVue - tailleContenu) / 2f
        } else {
            decalage.coerceIn(tailleVue - tailleContenu, 0f)
        }

    private const val ECHELLE_MINIMALE = 0.1f
    private const val ECHELLE_MINIMALE_PINCEMENT = 0.25f
    private const val ECHELLE_MAXIMALE = 6f
}
