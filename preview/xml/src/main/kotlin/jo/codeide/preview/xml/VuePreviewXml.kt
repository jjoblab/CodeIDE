package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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
    private var decalageX = 0f
    private var decalageY = 0f
    private var messageErreur: String? = null
    private var enEdition = false
    private var accueilAffiche = true

    /** Vues du dernier rendu (hit-test P6 — vides hors LAYOUT). */
    private var vues: List<InfoVueRendu> = emptyList()

    /** Indice de la vue surlignée (sélection éditeur → preview, -1 aucune). */
    private var indiceSurligne: Int = -1

    /** Densité du profil de rendu (PE6a — conversion px → dp des libellés). */
    private var densiteDpi: Int = 0

    /** Durée du dernier rendu (PE6a — pastille d'état). */
    private var dureeMs: Long = 0

    /** Nombre de vues du dernier rendu (PE6a — pastille d'état). */
    private var nombreVues: Int = 0

    /** Problèmes du dernier rendu (PE6c — pastilles de la maquette). */
    private var problemes: List<ProblemesPreview.ProblemeApercu> = emptyList()

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

    /** Le contenu glissé courant (localState du drag natif — PE7b). */
    private var contenuGlisse: ZonesDepot.ContenuDepot? = null

    /** Appui simple confirmé, en coordonnées BITMAP (contenu, pas écran). */
    public var auTap: ((x: Float, y: Float) -> Unit)? = null

    /**
     * Changement d'échelle (PE6b — boutons de zoom, gestes, ajustement) :
     * l'activité y met à jour le pourcentage affiché dans le HUD.
     */
    public var auChangementEchelle: ((echelle: Float) -> Unit)? = null

    private val peintDamier =
        Paint().apply {
            style = Paint.Style.FILL
            color = COULEUR_DAMIER_A
        }
    private val peintDamierB =
        Paint().apply {
            style = Paint.Style.FILL
            color = COULEUR_DAMIER_B
        }
    private val peintBandeau =
        Paint().apply {
            style = Paint.Style.FILL
            color = COULEUR_BANDEAU
        }
    private val peintTexteBandeau =
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            isAntiAlias = true
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

    /** Frères de la sélection — filigrane (PE6a). */
    private val peintFrere =
        Paint().apply {
            style = Paint.Style.FILL
        }

    /** Poignées d'angle de la sélection — disque plein liseré surface (PE6a). */
    private val peintPoignee =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Fond des étiquettes (nom · dimensions, pastille) — pill semi-opaque (PE6a). */
    private val peintPill =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    /** Texte de l'étiquette de sélection (nom · dimensions). */
    private val peintEtiquette =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
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
                    auTap?.invoke(xContenu, yContenu)
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
        peintTexteEtat.color = couleurTheme(android.R.attr.textColorSecondary)
        contentDescription = contexte.getString(R.string.preview_cd)
        // PE7b : le drag natif d'Android livre ses événements ICI
        // (la palette lance startDragAndDrop, la vue reçoit).
        setOnDragListener(this)
    }

    /** Affiche un rendu valide (remplace le bitmap, réajuste le zoom).
     *  Si la vue n'a pas encore ses dimensions (width/height = 0 — layout
     *  pas encore passé), l'ajustement est reporté au prochain layout via
     *  [onSizeChanged].
     *
     *  PE6a : [densiteDpiProfil] (conversion dp des libellés de calques,
     *  0 = repli px), [dureeRenduMs] et [nbVues] (pastille d'état —
     *  données déjà là, jamais affichées : maquette § « Constat »). */
    public fun afficherRendu(
        bitmap: Bitmap,
        vuesRendues: List<InfoVueRendu> = emptyList(),
        densiteDpiProfil: Int = 0,
        dureeRenduMs: Long = 0,
        nbVues: Int = 0,
    ) {
        this.bitmap = bitmap
        this.vues = vuesRendues
        densiteDpi = densiteDpiProfil
        dureeMs = dureeRenduMs
        nombreVues = nbVues
        problemes = emptyList()
        messageErreur = null
        enEdition = false
        accueilAffiche = false
        indiceSurligne = -1
        if (width > 0 && height > 0) {
            ajusterALaVue()
        } else {
            // La vue n'a pas encore été mesurée — on reporte l'ajustement.
            // onSizeChanged re-appellera ajusterALaVue() + invalidate().
            // En attendant, on met une échelle de repli pour ne pas
            // dessiner à échelle 0 (invisible).
            echelle = ECHELLE_REPLI
            decalageX = 0f
            decalageY = 0f
        }
        notifierEchelle()
        invalidate()
    }

    /** Affiche l'erreur sémantique en bandeau (le bitmap précédent reste). */
    public fun afficherErreur(message: String) {
        messageErreur = message
        enEdition = false
        accueilAffiche = false
        invalidate()
    }

    /** Signale l'édition en cours (XML malformé) — discret. */
    public fun afficherEnEdition() {
        enEdition = true
        invalidate()
    }

    /** Réinitialise (fermeture du panneau, plus de document). */
    public fun reinitialiser() {
        bitmap = null
        vues = emptyList()
        indiceSurligne = -1
        densiteDpi = 0
        dureeMs = 0
        nombreVues = 0
        problemes = emptyList()
        messageErreur = null
        enEdition = false
        accueilAffiche = true
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
            ajusterALaVue()
            notifierEchelle()
            invalidate()
        }
    }

    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        val geste = detecteurGestes.onTouchEvent(evenement)
        val zoom = detecteurZoom.onTouchEvent(evenement)
        // ClickableViewAccessibility : un appui SIMPLE se signale via
        // performClick (le double-tap de réajustement reste au détecteur).
        if (evenement.actionMasked == MotionEvent.ACTION_UP) performClick()
        return zoom || geste || super.onTouchEvent(evenement)
    }

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
                val (x, y) = CalculsVuePreview.coordonneesContenu(xVue, yVue, decalageX, decalageY, echelle)
                cibleDepot = ZonesDepot.cibleSous(vues, x, y)
            }

            DragEvent.ACTION_DRAG_EXITED -> {
                cibleDepot = null
            }

            DragEvent.ACTION_DROP -> {
                val cible = cibleDepot
                if (cible != null) {
                    val (x, y) = CalculsVuePreview.coordonneesContenu(xVue, yVue, decalageX, decalageY, echelle)
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
        val bmp = bitmap
        if (bmp == null) {
            dessinerAccueil(canevas)
            return
        }
        dessinerDamier(canevas, hauteurZoneContenu())
        canevas.save()
        canevas.translate(decalageX, decalageY)
        canevas.scale(echelle, echelle)
        canevas.drawBitmap(bmp, 0f, 0f, null)
        // Calques, du plus large au plus étroit (miroir drawOverlay de la
        // maquette) : grille, bornes, frères, parent, contraintes,
        // box-model, sélection, pastilles de problèmes.
        dessinerGrille(canevas, bmp)
        dessinerBornes(canevas)
        dessinerFreres(canevas)
        dessinerParent(canevas)
        dessinerContraintes(canevas)
        dessinerMargesPadding(canevas)
        dessinerSurlignage(canevas)
        dessinerPastillesProblemes(canevas)
        // Zones de dépôt EN DERNIER (miroir drawDrop de la maquette : les
        // zones recouvrent les calques de sélection masqués au drag).
        dessinerZonesDepot(canevas)
        canevas.restore()
        if (messageErreur != null) dessinerBandeauErreur(canevas)
        if (enEdition) dessinerSignalEdition(canevas)
        dessinerPastille(canevas)
    }

    /** Calque « frères en filigrane » (PE6a) : fill translucide sur les bounds. */
    private fun dessinerFreres(canevas: Canvas) {
        if (!calquesParentFreres || zonesDepotAffichees) return
        if (indiceSurligne !in vues.indices) return
        peintFrere.color = couleurSurlignage
        for (indice in CalquesApercu.indicesFreres(vues, indiceSurligne)) {
            val frere = vues[indice]
            if (frere.droite <= frere.gauche || frere.bas <= frere.haut) continue
            peintFrere.alpha = ALPHA_FRERE
            canevas.drawRect(
                RectF(frere.gauche.toFloat(), frere.haut.toFloat(), frere.droite.toFloat(), frere.bas.toFloat()),
                peintFrere,
            )
        }
    }

    /** Calque « parent en pointillés » (PE6a) : trait à épaisseur écran constante. */
    @Suppress("ReturnCount") // Les gardes (masqué au drag, pas de parent, vue dégénérée) sont des issues DISTINCTES.
    private fun dessinerParent(canevas: Canvas) {
        if (zonesDepotAffichees) return
        val indice = if (calquesParentFreres) CalquesApercu.indiceParent(vues, indiceSurligne) else -1
        val parent = vues.getOrNull(indice) ?: return
        if (parent.droite <= parent.gauche || parent.bas <= parent.haut) return
        peintParent.strokeWidth = LARGEUR_TRAIT_SURLIGNAGE / echelle
        peintParent.color = couleurParent
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
        bmp: Bitmap,
    ) {
        if (!grilleAffichee || densiteDpi <= 0) return
        val pas = PAS_GRILLE_DP * densiteDpi / DENSITE_REFERENCE
        if (pas < 2) return
        peintGrille.color = COULEUR_GRILLE
        peintGrille.strokeWidth = LARGEUR_TRAIT_GRILLE / echelle
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

    /** Calque « Bornes de toutes les vues » (PE6b, miroir `.bnd` de la maquette). */
    private fun dessinerBornes(canevas: Canvas) {
        if (!calquesBornes) return
        peintBorne.color = COULEUR_BORNE
        peintBorne.strokeWidth = LARGEUR_TRAIT_BORNE / echelle
        for (vue in vues) {
            if (vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            canevas.drawRect(
                RectF(vue.gauche.toFloat(), vue.haut.toFloat(), vue.droite.toFloat(), vue.bas.toFloat()),
                peintBorne,
            )
        }
    }

    /** Calque « Contraintes » (PE6c, miroir `drawConstraints` de la maquette). */
    private fun dessinerContraintes(canevas: Canvas) {
        if (!calquesContraintes) return
        peintContrainte.color = COULEUR_CONTRAINTE
        peintContrainte.strokeWidth = LARGEUR_TRAIT_CONTRAINTE / echelle
        peintContrainte.pathEffect = EFFET_CONTRAINTE
        val rayonPoint = RAYON_POINT_CONTRAINTE_DP * echellePolice() / echelle
        for (ligne in CalquesApercu.lignesContraintes(vues, densiteDpi)) {
            canevas.drawLine(
                ligne.x1.toFloat(),
                ligne.y1.toFloat(),
                ligne.x2.toFloat(),
                ligne.y2.toFloat(),
                peintContrainte,
            )
            peintPin.color = COULEUR_CONTRAINTE
            canevas.drawCircle(ligne.x1.toFloat(), ligne.y1.toFloat(), rayonPoint, peintPin)
            canevas.drawCircle(ligne.x2.toFloat(), ligne.y2.toFloat(), rayonPoint, peintPin)
            // Pill « valeur en dp » décalée de la ligne (miroir maquette).
            val milieuX = (ligne.x1 + ligne.x2) / 2f
            val milieuY = (ligne.y1 + ligne.y2) / 2f
            val decalage = DECALAGE_PILL_CONTRAINTE_DP * echellePolice() / echelle
            if (ligne.verticale) {
                dessinerPillValeur(canevas, ligne.valeurDp, milieuX + decalage, milieuY)
            } else {
                dessinerPillValeur(canevas, ligne.valeurDp, milieuX, milieuY - decalage)
            }
        }
    }

    /**
     * Calque « Marges et padding » (PE6c, miroir `drawBoxModel` de la
     * maquette) : bandes ORANGE autour du cadre (marges, contour
     * pointillé) et bandes VERTES à l'intérieur (padding) de la
     * sélection, valeurs en dp du profil aux centres.
     */
    private fun dessinerMargesPadding(canevas: Canvas) {
        if (!calquesMargesPadding || zonesDepotAffichees || indiceSurligne !in vues.indices) return
        val vue = vues[indiceSurligne]
        if (vue.droite <= vue.gauche || vue.bas <= vue.haut) return
        dessinerBandesMarges(canevas, vue)
        dessinerBandesPadding(canevas, vue)
    }

    /** Bandes de MARGE autour du cadre — orange, contour pointillé (miroir `.mg`). */
    private fun dessinerBandesMarges(
        canevas: Canvas,
        vue: InfoVueRendu,
    ) {
        if (uneBandeSignificative(vue.margesHaut, vue.margesGauche, vue.margesDroite, vue.margesBas)) {
            val gauche = vue.gauche.toFloat()
            val haut = vue.haut.toFloat()
            val droite = vue.droite.toFloat()
            val bas = vue.bas.toFloat()
            peintBoxModel.color = COULEUR_MARGE
            peintBoxModel.alpha = ALPHA_BOXMODEL
            peintBoxModelContour.color = COULEUR_MARGE
            peintBoxModelContour.strokeWidth = LARGEUR_TRAIT_BORNE / echelle
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
        vue: InfoVueRendu,
    ) {
        if (uneBandeSignificative(vue.paddingHaut, vue.paddingGauche, vue.paddingDroite, vue.paddingBas)) {
            val gauche = vue.gauche.toFloat()
            val haut = vue.haut.toFloat()
            val droite = vue.droite.toFloat()
            val bas = vue.bas.toFloat()
            peintBoxModel.color = COULEUR_PADDING
            peintBoxModel.alpha = ALPHA_BOXMODEL
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
    private fun dessinerPastillesProblemes(canevas: Canvas) {
        if (!calquesProblemes || zonesDepotAffichees || problemes.isEmpty()) return
        val comptes = LinkedHashMap<Int, Int>()
        val indicesErreur = HashSet<Int>()
        for (probleme in problemes) {
            comptes[probleme.indiceVue] = (comptes[probleme.indiceVue] ?: 0) + 1
            if (probleme.severite == ProblemesPreview.Severite.ERREUR) {
                indicesErreur.add(probleme.indiceVue)
            }
        }
        val rayon = RAYON_PIN_DP * echellePolice() / echelle
        val lise = LISE_PIN_DP * echellePolice() / echelle
        peintEtiquette.textSize = TAILLE_TEXTE_PIN_DP * echellePolice() / echelle
        val demiHauteur = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        for ((indice, compte) in comptes) {
            val vue = vues.getOrNull(indice)
            if (vue == null || vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            val centreX = vue.droite.toFloat()
            val centreY = vue.haut.toFloat()
            // Liseré blanc sous le disque (miroir `.pin` : bordure 2 dp).
            peintPin.color = Color.WHITE
            canevas.drawCircle(centreX, centreY, rayon + lise, peintPin)
            peintPin.color = if (indice in indicesErreur) COULEUR_PIN_ERREUR else COULEUR_PIN_AVERTISSEMENT
            canevas.drawCircle(centreX, centreY, rayon, peintPin)
            val texte = if (compte > 1) compte.toString() else "!"
            peintEtiquette.color = Color.WHITE
            canevas.drawText(
                texte,
                centreX - peintEtiquette.measureText(texte) / 2f,
                centreY - demiHauteur,
                peintEtiquette,
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
    @Suppress("ReturnCount", "MagicNumber")
    private fun dessinerZonesDepot(canevas: Canvas) {
        if (!zonesDepotAffichees || vues.isEmpty()) return
        // 1. Tous les conteneurs : contour pointillé violet (miroir `.dz`, opacity .75).
        peintZone.color = COULEUR_ZONE_DEPOT
        peintZone.alpha = 0xBF
        peintZone.pathEffect = EFFET_POINTILLES
        for (vue in vues) {
            if (!vue.conteneur || vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            peintZone.strokeWidth = LARGEUR_TRAIT_ZONE_DP * echellePolice() / echelle
            canevas.drawRect(
                RectF(vue.gauche.toFloat(), vue.haut.toFloat(), vue.droite.toFloat(), vue.bas.toFloat()),
                peintZone,
            )
        }
        val cible = cibleDepot ?: return
        // 2. Le conteneur visé : fond violet doux + bordure SOLIDE (miroir `.dz.on`).
        val parent = vues.getOrNull(cible.indiceParent) ?: return
        peintZoneCible.color = COULEUR_ZONE_DEPOT_DOUX
        canevas.drawRect(
            RectF(parent.gauche.toFloat(), parent.haut.toFloat(), parent.droite.toFloat(), parent.bas.toFloat()),
            peintZoneCible,
        )
        peintZone.pathEffect = null
        peintZone.alpha = 0xFF
        peintZone.strokeWidth = LARGEUR_TRAIT_ZONE_DP * echellePolice() / echelle
        canevas.drawRect(
            RectF(parent.gauche.toFloat(), parent.haut.toFloat(), parent.droite.toFloat(), parent.bas.toFloat()),
            peintZone,
        )
        // 3. La pill « Dans X » (miroir chip `nw` : au-dessus du conteneur, sinon sous).
        val auDessus = parent.haut - TAILLE_TEXTE_ETIQUETTE_DP * echellePolice() >= 0f
        dessinerPillViolette(
            canevas,
            ZonesDepot.libelleCible(vues, cible),
            parent.gauche.toFloat(),
            if (auDessus) parent.haut.toFloat() else parent.bas.toFloat(),
            auDessus,
        )
        // 4. La BARRE d'insertion (miroir `.slot` : halo doux PUIS barre pleine)
        //    + pill « + Nom · i/n » sous la barre.
        val contenu = contenuGlisse ?: return
        val epaisseur = EPAISSEUR_BARRE_DP * echellePolice() / echelle
        val barre = ZonesDepot.emplacement(vues, cible, densiteDpi, epaisseur) ?: return
        val halo = HALO_BARRE_DP * echellePolice() / echelle
        peintBarreDepot.color = COULEUR_ZONE_DEPOT_DOUX
        val rectHalo = RectF(barre.gauche - halo, barre.haut - halo, barre.droite + halo, barre.bas + halo)
        canevas.drawRect(rectHalo, peintBarreDepot)
        peintBarreDepot.color = COULEUR_ZONE_DEPOT
        canevas.drawRect(RectF(barre.gauche, barre.haut, barre.droite, barre.bas), peintBarreDepot)
        dessinerPillViolette(
            canevas,
            ZonesDepot.libelleInsertion(vues, cible, contenu.nomAffiche),
            barre.gauche,
            barre.bas + DECALAGE_PILL_BARRE_DP * echellePolice() / echelle,
            auDessus = false,
        )
    }

    /** Pill violette (miroir `.lab.nw` : fond violet, texte blanc), ancrée au bord gauche. */
    private fun dessinerPillViolette(
        canevas: Canvas,
        texte: String,
        gaucheX: Float,
        bordY: Float,
        auDessus: Boolean,
    ) {
        if (texte.isEmpty()) return
        peintEtiquette.textSize = TAILLE_TEXTE_ETIQUETTE_DP * echellePolice() / echelle
        val largeurTexte = peintEtiquette.measureText(texte)
        val demiHauteur = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        val marge = MARGE_ETIQUETTE_DP * echellePolice() / echelle
        val centreY = if (auDessus) bordY - demiHauteur * 2f - marge * 2f else bordY + demiHauteur + marge
        peintPill.color = COULEUR_ZONE_DEPOT
        peintPill.alpha = ALPHA_PILL_ETIQUETTE
        canevas.drawRoundRect(
            gaucheX - marge,
            centreY - demiHauteur - marge,
            gaucheX + largeurTexte + marge * 2f,
            centreY + demiHauteur + marge,
            demiHauteur + marge,
            demiHauteur + marge,
            peintPill,
        )
        peintEtiquette.color = couleurSurSelection
        canevas.drawText(texte, gaucheX, centreY - demiHauteur, peintEtiquette)
    }

    /** Pill « valeur » (dp des box-models/contraintes) centrée sur le point. */
    private fun dessinerPillValeur(
        canevas: Canvas,
        valeurDp: Int,
        centreX: Float,
        centreY: Float,
    ) {
        if (valeurDp <= 0) return
        val texte = valeurDp.toString()
        peintEtiquette.textSize = TAILLE_TEXTE_ETIQUETTE_DP * echellePolice() / echelle
        val largeurTexte = peintEtiquette.measureText(texte)
        val demiHauteur = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        val marge = MARGE_ETIQUETTE_DP * echellePolice() / echelle
        peintPill.color = COULEUR_CONTRAINTE
        peintPill.alpha = ALPHA_PILL_ETIQUETTE
        canevas.drawRoundRect(
            centreX - largeurTexte / 2f - marge,
            centreY - demiHauteur - marge,
            centreX + largeurTexte / 2f + marge,
            centreY + demiHauteur + marge,
            demiHauteur + marge,
            demiHauteur + marge,
            peintPill,
        )
        peintEtiquette.color = couleurSurSelection
        canevas.drawText(texte, centreX - largeurTexte / 2f, centreY - demiHauteur, peintEtiquette)
    }

    /**
     * Overlay de sélection (P6, étendu PE6a) : cadre sur les bounds de
     * la vue surlignée, POIGNÉES d'angle, étiquette « nom · dimensions
     * en dp » au-dessus — tout à épaisseur/taille ÉCRAN constante.
     */
    private fun dessinerSurlignage(canevas: Canvas) {
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
        peintSurlignage.strokeWidth = LARGEUR_TRAIT_SURLIGNAGE / echelle
        peintSurlignage.color = couleurSurlignage
        canevas.drawRect(rect, peintSurlignage)
        peintFondSurlignage.color =
            (couleurSurlignage and MASQUE_COULEUR_RGB) or (COULEUR_ALPHA_SURLIGNAGE shl DECALAGE_ALPHA)
        canevas.drawRect(rect, peintFondSurlignage)
        dessinerPoignees(canevas, rect)
        dessinerEtiquette(canevas, rect, vue)
    }

    /** Poignées d'angle (PE6a) : disques pleins au couleur de sélection, liseré surface. */
    private fun dessinerPoignees(
        canevas: Canvas,
        rect: RectF,
    ) {
        val rayon = RAYON_POIGNEE_DP * echellePolice() / echelle
        peintPoignee.color = couleurSurlignage
        for (coin in COINS) {
            val x = if (coin.first) rect.left else rect.right
            val y = if (coin.second) rect.top else rect.bottom
            // Liseré : un disque SURFACE légèrement plus grand sous le plein.
            peintPill.color = couleurSurface
            canevas.drawCircle(x, y, rayon + RAYON_LISE_POIGNEE * echellePolice() / echelle, peintPill)
            canevas.drawCircle(x, y, rayon, peintPoignee)
        }
    }

    /** Étiquette « nom · dimensions en dp » (PE6a) au-dessus du cadre, bornée au contenu. */
    private fun dessinerEtiquette(
        canevas: Canvas,
        rect: RectF,
        vue: InfoVueRendu,
    ) {
        val texte =
            CalquesApercu.libelleSelection(
                vue.libelle,
                CalquesApercu.dimensions(vue.droite - vue.gauche, vue.bas - vue.haut, densiteDpi),
            )
        if (texte.isBlank()) return
        peintEtiquette.textSize = TAILLE_TEXTE_ETIQUETTE_DP * echellePolice() / echelle
        val marge = MARGE_ETIQUETTE_DP * echellePolice() / echelle
        val largeurTexte = peintEtiquette.measureText(texte)
        val demiHauteurTexte = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        // Pill au-dessus du cadre (décalée d'un rayon de poignée), bornée à gauche.
        val xPill = (rect.left - marge).coerceAtLeast(0f)
        val yPill = (rect.top - rayonPoigneeEnContenu() * 2f).coerceAtLeast(0f)
        peintPill.color = couleurSurlignage
        peintPill.alpha = ALPHA_PILL_ETIQUETTE
        canevas.drawRoundRect(
            xPill,
            yPill - demiHauteurTexte - marge,
            xPill + largeurTexte + 2 * marge,
            yPill + demiHauteurTexte + marge,
            (demiHauteurTexte + marge),
            (demiHauteurTexte + marge),
            peintPill,
        )
        peintEtiquette.color = couleurSurSelection
        canevas.drawText(texte, xPill + marge, yPill - demiHauteurTexte, peintEtiquette)
    }

    /** Rayon d'une poignée rapporté à l'espace CONTENU (taille écran constante). */
    private fun rayonPoigneeEnContenu(): Float = RAYON_POIGNEE_DP * echellePolice() / echelle

    /** Pastille d'état (PE6a) : durée du rendu et nombre de vues — coins bas-gauche, écran constant. */
    private fun dessinerPastille(canevas: Canvas) {
        if (nombreVues <= 0 && dureeMs <= 0) return
        val texte =
            resources.getQuantityString(
                R.plurals.preview_pastille_vues,
                nombreVues,
                nombreVues,
                dureeMs,
            )
        val taille = TAILLE_TEXTE_ETIQUETTE_DP * echellePolice()
        peintEtiquette.textSize = taille
        val marge = MARGE_PASTILLE_DP * echellePolice()
        val largeurTexte = peintEtiquette.measureText(texte)
        val demiHauteurTexte = (peintEtiquette.descent() + peintEtiquette.ascent()) / 2f
        val yPill = height - marge - hauteurBandeauSiPresent()
        peintPill.color = couleurSurface
        peintPill.alpha = ALPHA_PILL_PASTILLE
        canevas.drawRoundRect(
            marge,
            yPill - demiHauteurTexte - marge,
            marge + largeurTexte + 2 * marge,
            yPill + demiHauteurTexte + marge,
            demiHauteurTexte + marge,
            demiHauteurTexte + marge,
            peintPill,
        )
        peintEtiquette.color = couleurTheme(android.R.attr.textColorPrimary)
        canevas.drawText(texte, marge + marge, yPill - demiHauteurTexte, peintEtiquette)
    }

    /** La pastille ne glisse pas SOUS le bandeau d'erreur quand il est présent. */
    private fun hauteurBandeauSiPresent(): Float = if (messageErreur == null) 0f else hauteurBandeau()

    /** Hauteur disponible au contenu : le bandeau d'erreur la grignote. */
    private fun hauteurZoneContenu(): Float =
        if (messageErreur == null) {
            height.toFloat()
        } else {
            (height - hauteurBandeau()).coerceAtLeast(1f)
        }

    private fun dessinerDamier(
        canevas: Canvas,
        zoneHauteur: Float,
    ) {
        var y = 0f
        var rangee = 0
        while (y < zoneHauteur) {
            var x = 0f
            var colonne = 0
            while (x < width) {
                val peinture = if ((rangee + colonne) % 2 == 0) peintDamier else peintDamierB
                canevas.drawRect(x, y, x + TAILLE_CASE_DAMIER, min(y + TAILLE_CASE_DAMIER, zoneHauteur), peinture)
                x += TAILLE_CASE_DAMIER
                colonne += 1
            }
            y += TAILLE_CASE_DAMIER
            rangee += 1
        }
    }

    private fun dessinerBandeauErreur(canevas: Canvas) {
        val message = messageErreur ?: return
        peintTexteBandeau.textSize = echellePolice() * TAILLE_TEXTE_BANDEAU_DP
        canevas.drawRect(0f, 0f, width.toFloat(), hauteurBandeau(), peintBandeau)
        val texte = tronquer(message, largeurTexteDisponible())
        canevas.drawText(texte, MARGE_TEXTE, centreVerticalBandeau(), peintTexteBandeau)
    }

    private fun dessinerSignalEdition(canevas: Canvas) {
        val libelle = context.getString(R.string.preview_en_edition)
        val largeurTexte = peintTexteEtat.measureText(libelle)
        canevas.drawText(
            libelle,
            (width - largeurTexte) / 2f,
            height - MARGE_TEXTE,
            peintTexteEtat,
        )
    }

    private fun dessinerAccueil(canevas: Canvas) {
        val libelle = context.getString(R.string.preview_accueil)
        peintTexteEtat.textAlign = Paint.Align.CENTER
        canevas.drawText(libelle, width / 2f, height / 2f, peintTexteEtat)
        peintTexteEtat.textAlign = Paint.Align.LEFT
    }

    /** Réajuste zoom et décalage pour que le bitmap tienne dans la vue. */
    private fun ajusterALaVue() {
        val bmp = bitmap ?: return
        echelle =
            CalculsVuePreview.echelleAjustee(
                largeurContenu = bmp.width,
                hauteurContenu = bmp.height,
                largeurVue = width,
                hauteurVue = height,
            )
        decalageX = (width - bmp.width * echelle) / 2f
        decalageY = (height - bmp.height * echelle) / 2f
    }

    /** Maintient le contenu visible pendant le pan. */
    private fun bornerPan() {
        val bmp = bitmap ?: return
        decalageX = CalculsVuePreview.bornerDecalage(decalageX, width.toFloat(), bmp.width * echelle)
        decalageY = CalculsVuePreview.bornerDecalage(decalageY, height.toFloat(), bmp.height * echelle)
    }

    /** Échelle de police (densité × fontScale — remplace scaledDensity déprécié API 34). */
    private fun echellePolice(): Float = resources.displayMetrics.density * resources.configuration.fontScale

    private fun hauteurBandeau(): Float = echellePolice() * HAUTEUR_BANDEAU_DP

    private fun centreVerticalBandeau(): Float =
        hauteurBandeau() / 2f - (peintTexteBandeau.descent() + peintTexteBandeau.ascent()) / 2f

    private fun largeurTexteDisponible(): Float = width - 2 * MARGE_TEXTE

    private fun tronquer(
        texte: String,
        largeurMax: Float,
    ): String {
        if (peintTexteBandeau.measureText(texte) <= largeurMax) return texte
        var coupure = texte.length
        while (coupure > 1 && tropLarge(texte, coupure, largeurMax)) {
            coupure -= 1
        }
        return texte.substring(0, coupure) + SUFFIXE_TRONQUE
    }

    private fun tropLarge(
        texte: String,
        coupure: Int,
        largeurMax: Float,
    ): Boolean = peintTexteBandeau.measureText(texte.substring(0, coupure) + SUFFIXE_TRONQUE) > largeurMax

    private fun couleurTheme(attribut: Int): Int {
        val valeur = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attribut, valeur, true)) {
            valeur.data
        } else {
            Color.GRAY
        }
    }

    /** Couleur du surlignage : la couleur primaire du thème de l'IDE. */
    private val couleurSurlignage: Int by lazy { couleurTheme(android.R.attr.colorPrimary) }

    /** Couleur du parent pointillé : texte secondaire (PE6a — discret). */
    private val couleurParent: Int by lazy { couleurTheme(android.R.attr.textColorSecondary) }

    /** Couleur des liserés/pill : surface du thème (PE6a). */
    private val couleurSurface: Int by lazy { couleurTheme(android.R.attr.colorBackground) }

    /** Couleur du texte sur pill de sélection : blanc (sur primaire). */
    private val couleurSurSelection: Int = Color.WHITE

    public companion object {
        private const val COULEUR_DAMIER_A = 0xFFE8E8EC.toInt()
        private const val COULEUR_DAMIER_B = 0xFFF6F6F9.toInt()
        private const val COULEUR_BANDEAU = 0xFFB3261E.toInt()
        private const val TAILLE_CASE_DAMIER = 16f
        private const val MARGE_TEXTE = 16f
        private const val TAILLE_TEXTE_ETAT_DP = 12f
        private const val TAILLE_TEXTE_BANDEAU_DP = 13f
        private const val HAUTEUR_BANDEAU_DP = 36f
        private const val SUFFIXE_TRONQUE = "…"
        private const val ECHELLE_REPLI = 0.1f

        /** Largeur du trait de surlignage en dp (épaisseur ÉCRAN constante au zoom). */
        private const val LARGEUR_TRAIT_SURLIGNAGE = 2f

        /** Alpha du fond de surlignage (0-255) — translucide, le rendu reste lisible. */
        private const val COULEUR_ALPHA_SURLIGNAGE = 0x30

        /** Masque RGB (privé du canal alpha) pour teinter le fond de surlignage. */
        private const val MASQUE_COULEUR_RGB = 0x00FFFFFF

        /** Position du canal alpha dans un ARGB int. */
        private const val DECALAGE_ALPHA = 24

        /** Alpha du filigrane des frères (0-255) — discret, le rendu reste lisible. */
        private const val ALPHA_FRERE = 0x18

        /** Alpha du fond de l'étiquette de sélection (0-255). */
        private const val ALPHA_PILL_ETIQUETTE = 0xE6

        /** Alpha du fond de la pastille d'état (0-255). */
        private const val ALPHA_PILL_PASTILLE = 0xB8

        /** Rayon des poignées d'angle, en dp écran (PE6a). */
        private const val RAYON_POIGNEE_DP = 4f

        /** Liseré des poignées, en dp écran (PE6a). */
        private const val RAYON_LISE_POIGNEE = 1.5f

        /** Taille du texte de l'étiquette / pastille, en dp écran (PE6a). */
        private const val TAILLE_TEXTE_ETIQUETTE_DP = 12f

        /** Marge interne des pills, en dp écran (PE6a). */
        private const val MARGE_ETIQUETTE_DP = 4f

        /** Marge de la pastille aux bords de l'écran, en dp écran (PE6a). */
        private const val MARGE_PASTILLE_DP = 8f

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

        // ── Zones de dépôt PE7b (miroir des jetons de la maquette) ──

        /** Couleur VIOLETTE des zones (miroir `--new` #7C3AED). */
        private const val COULEUR_ZONE_DEPOT = 0xFF7C3AED.toInt()

        /** Fond doux du conteneur visé (miroir `--new-soft` rgba(124,58,237,.14)). */
        private const val COULEUR_ZONE_DEPOT_DOUX = 0x247C3AED

        /** Largeur du trait des zones, en dp écran (miroir 1,5 px de `.dz`). */
        private const val LARGEUR_TRAIT_ZONE_DP = 1.5f

        /** Épaisseur de la barre d'insertion, en dp écran (miroir 4 px de `slotRect`). */
        private const val EPAISSEUR_BARRE_DP = 4f

        /** Halo de la barre (miroir box-shadow 3 px de `.slot`). */
        private const val HALO_BARRE_DP = 3f

        /** Décalage de la pill d'insertion sous la barre, en dp écran (miroir 6 px). */
        private const val DECALAGE_PILL_BARRE_DP = 6f

        // ── Calques PE6b/PE6c (miroir des jetons de la maquette) ──

        /** Densité de référence Android (dpi) — facteur des conversions dp. */
        private const val DENSITE_REFERENCE = 160

        /** Pas de la grille, en dp du profil (PE6b — « Grille 8 dp »). */
        private const val PAS_GRILLE_DP = 8

        /** Couleur de la grille (miroir rgba(30,110,200,.18) de la maquette). */
        private const val COULEUR_GRILLE = 0x2E1E6EC8

        /** Largeur du trait de grille, en dp écran. */
        private const val LARGEUR_TRAIT_GRILLE = 1f

        /** Couleur des bornes (miroir rgba(30,110,200,.5) — `.bnd`). */
        private const val COULEUR_BORNE = 0x801E6EC8.toInt()

        /** Largeur du trait des bornes, en dp écran. */
        private const val LARGEUR_TRAIT_BORNE = 1f

        /** Couleur des lignes de contrainte (miroir `--accent` de la maquette). */
        private const val COULEUR_CONTRAINTE = 0xFF1E6EC8.toInt()

        /** Largeur du trait de contrainte (miroir 1,5 px de `drawConstraints`). */
        private const val LARGEUR_TRAIT_CONTRAINTE = 1.5f

        /** Pointillés des contraintes — 5 px tracés, 3 px troués (miroir maquette). */
        private val EFFET_CONTRAINTE = DashPathEffect(floatArrayOf(5f, 3f), 0f)

        /** Rayon des points d'extrémité de contrainte, en dp écran (miroir 3,5 px). */
        private const val RAYON_POINT_CONTRAINTE_DP = 3.5f

        /** Décalage de la pill de valeur depuis la ligne, en dp écran (miroir 11-14 px). */
        private const val DECALAGE_PILL_CONTRAINTE_DP = 12f

        /** Couleur des bandes de MARGE (miroir `--margin` #D9822B de la maquette). */
        private const val COULEUR_MARGE = 0xFFD9822B.toInt()

        /** Couleur des bandes de PADDING (miroir `--padding` #2E8B4B de la maquette). */
        private const val COULEUR_PADDING = 0xFF2E8B4B.toInt()

        /** Alpha des bandes du box-model (miroir 38 % de la maquette). */
        private const val ALPHA_BOXMODEL = 0x61

        /** Couleur des pastilles d'AVERTISSEMENT (miroir `.pin.w` #B8860B). */
        private const val COULEUR_PIN_AVERTISSEMENT = 0xFFB8860B.toInt()

        /** Couleur des pastilles d'ERREUR (miroir `--err` #BA1A1A). */
        private const val COULEUR_PIN_ERREUR = 0xFFBA1A1A.toInt()

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
