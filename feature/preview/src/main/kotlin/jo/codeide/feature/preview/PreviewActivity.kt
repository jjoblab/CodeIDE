package jo.codeide.feature.preview

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.feature.preview.databinding.ActivityPreviewBinding
import jo.codeide.feature.preview.databinding.PopoverMenuAppareilBinding
import jo.codeide.feature.preview.databinding.PopoverMenuCalquesBinding
import jo.codeide.preview.xml.CataloguePalette
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.RequeteRendu
import jo.codeide.preview.xml.VuePreviewXml
import jo.codeide.preview.xml.ZonesDepot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import jo.codeide.core.ui.R as RUi

/**
 * Écran DÉDIÉ de l'aperçu XML (PE6-0, maquette
 * `docs/mockups/apercu-xml.html` § « Activité dédiée » ; chrome PE6b ;
 * feuille PE6d) : le panneau quitte l'overlay d'`EditorActivity` et
 * s'empile au-dessus — même patron que l'écran du terminal
 * ([AppNavigator.openPreview], activité `exported="false"` déclarée dans
 * le manifest du module).
 *
 * Répartition (maquette § « Ce qui change dans le dépôt ») :
 * - [PreviewViewModel] : moteur (rotation sûre), source du registre,
 *   projet (`res/` FUSE, thème, label), réglages persistés (PE7c) ;
 * - [ControleurPreviewXml] : couture moteur ↔ vue + tap → span ;
 * - CETTE activité : chrome Material — toolbar (SOUS-TITRE « res/layout
 *   · rendu en X », miroir `.ttl span`), PUCES défilantes (miroir
 *   `.chips` : appareil ▾, portrait/paysage PE6e-f, jour, langue,
 *   police PE6e-f, calques ▾), HUD de zoom, BARRE D'ÉDITION (PE7d,
 *   miroir `.edit` : annuler, rétablir | dupliquer, supprimer), BULLE
 *   + PALETTE + POPUP DE PLACEMENT (PE7c, miroirs `.bubble`/`.pop.pal`/
 *   `.pop.bot`) — le module porte le chrome, le moteur `:preview:xml`
 *   reste zéro-Material (règle du module).
 *
 * PE7c — PALETTE COMME VUE : la palette est une VUE de l'activité (le
 * popover de l'explorateur, jamais une fenêtre de dialogue — le dépôt
 * doit passer) ; le glisser d'une tuile lance le drag NATIF
 * (`startDragAndDrop` — le moteur d'Android livre les événements à la
 * vue d'aperçu, PE7b) puis la palette SE MASQUE (miroir
 * `.phone.dragging .bubble`) ; le placement par popup pose le repère EN
 * DIRECT sur l'aperçu (miroir `S.dropView`) via la même machine PE7b.
 *
 * PE7d — ÉDITION : chaque action de la barre produit une ÉDITION DE
 * TEXTE unique ([PreviewViewModel] → port `SourceApercu.remplacer`,
 * « une seule entrée dans l'historique de l'éditeur »), le rendu repart
 * immédiatement.
 *
 * Pièges traités (maquette § « Pièges à traiter ») :
 * - **mort du processus** : le registre est vide → l'écran se termine
 *   et relance l'éditeur ([PreviewViewModel.finir]) ;
 * - **rotation** : le moteur survit dans le ViewModel ;
 * - **changement de fichier** : le menu « Choisir le fichier… » liste
 *   les documents ouverts previewables ([PreviewViewModel.sourcesPreviewables]) ;
 * - **sens inverse de la sélection** : l'éditeur n'est plus visible —
 *   le tap pose le span dans la session (l'éditeur le retrouve), le
 *   calque surligne immédiatement (retour visuel direct).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chrome de
 * l'écran : chaque menu/popup a SA méthode (même patron que l'éditeur).
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
public class PreviewActivity : AppCompatActivity() {
    private val viewModel: PreviewViewModel by viewModels()

    /** Journal applicatif (règle 11 : chemins seuls, rien de personnel). */
    @Inject
    lateinit var journal: AppLogger

    /** Dispatchers injectés (règle 5) — le moteur y rend sur fil dédié. */
    @Inject
    lateinit var dispatchers: DispatcherProvider

    /** Navigation inter-écrans : retour + relance de l'éditeur (processus mort). */
    @Inject
    lateinit var navigateur: AppNavigator

    private lateinit var liaison: ActivityPreviewBinding

    /** Couture moteur ↔ vue (créée à la [onCreate], coupée au [onDestroy]). */
    private var controleur: ControleurPreviewXml? = null

    /** La palette (PE7c) — créée paresseusement, une seule fois. */
    private var palette: PalettePopover? = null

    /** Le popup de placement (PE7c) — créée paresseusement, une seule fois. */
    private var popupPlacement: PopupPlacement? = null

    /** Les POPOVERS MENUS translucides (PE9c) — calques, appareil. */
    private val menuApercu: PopoverMenuApercu by lazy { PopoverMenuApercu(liaison.root) }

    /** Le widget en placement par popup (miroir `S.place.w`). */
    private var widgetEnPlacement: ZonesDepot.ContenuDepot? = null

    /** La référence du placement (miroir `S.place.ref`). */
    private var indiceReferencePlacement: Int = 0

    /** La position du placement (miroir `S.place.pos`). */
    private var positionPlacement: PlacementPopup.Position = PlacementPopup.Position.DEDANS

    /** La cible dérivée (miroir `placeTarget`) — le repère suit. */
    private var ciblePlacement: ZonesDepot.CibleDepot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        liaison = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(liaison.root)

        liaison.toolbarPreview.applySystemBarsInsets(top = true, bottom = false)
        setSupportActionBar(liaison.toolbarPreview)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        liaison.toolbarPreview.setNavigationOnClickListener { navigateur.goBack() }

        brancherSelectionEtDepot()
        brancherPucesReglages()
        brancherHudZoom()
        brancherBarreEdition()
        brancherBullePalette()

        // Piège « mort du processus » : source absente du registre → se
        // terminer et relancer l'éditeur, jamais un panneau vide.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                brancherCollections()
            }
        }
        monterFeuille()
        majTitre()
        majBarreEdition()
    }

    /** Les puces de réglages (PE6b + PE6e-f, miroir `.chips` de la maquette). */
    private fun brancherPucesReglages() {
        liaison.boutonAppareilApercu.setOnClickListener { ouvrirFeuilleAppareil() }
        liaison.boutonOrientationApercu.setOnClickListener {
            viewModel.definirPaysage(!viewModel.paysage)
            majReglages()
        }
        liaison.boutonNuitApercu.setOnClickListener {
            controleur?.basculerNuit()
            majReglages()
        }
        liaison.boutonLangueApercu.setOnClickListener {
            controleur?.basculerLangue()
            majReglages()
        }
        liaison.boutonPoliceApercu.setOnClickListener {
            viewModel.cyclerEchellePolice()
            majReglages()
        }
        liaison.boutonCalquesApercu.setOnClickListener { ouvrirPopoverCalques() }
        majReglages()
    }

    /** Le HUD de zoom (PE6b, miroir `.zoom` de la maquette). */
    private fun brancherHudZoom() {
        // Les boutons exposent zoomer/ajuster de la VUE (spécification PE6b).
        liaison.boutonZoomAvant.setOnClickListener { liaison.vuePreview.zoomer(FACTEUR_ZOOM) }
        liaison.boutonZoomArriere.setOnClickListener { liaison.vuePreview.zoomer(1f / FACTEUR_ZOOM) }
        liaison.boutonZoomPct.setOnClickListener {
            val echelle = liaison.vuePreview.echelleCourante
            if (echelle > 0f) liaison.vuePreview.zoomer(1f / echelle)
        }
        liaison.boutonZoomAjuster.setOnClickListener { liaison.vuePreview.ajuster() }
        // Le % affiché suit TOUT changement d'échelle (gestes compris).
        liaison.vuePreview.auChangementEchelle = { echelle -> majPourcentageZoom(echelle) }
        majPourcentageZoom(liaison.vuePreview.echelleCourante)
    }

    /** La barre d'édition (PE7d, miroir `.edit` de la maquette). */
    private fun brancherBarreEdition() {
        liaison.boutonAnnuler.setOnClickListener {
            if (!viewModel.annuler()) toast(R.string.preview_edition_rien_annuler_toast)
        }
        liaison.boutonRetablir.setOnClickListener {
            if (!viewModel.retablir()) toast(R.string.preview_edition_rien_retablir_toast)
        }
        liaison.boutonDupliquer.setOnClickListener { dupliquer() }
        liaison.boutonSupprimer.setOnClickListener { supprimer() }
    }

    /** La bulle de palette (PE7c) : tap → palette, glisser → aimantation + persistance. */
    private fun brancherBullePalette() {
        // Le tap ouvre/ferme la palette, le glisser re-aimante et PERSISTE
        // (miroir `bindBubble`).
        liaison.bullePalette.auTap = { basculerPalette() }
        liaison.bullePalette.auDeplace = { aDroite, y -> viewModel.poserBulle(aDroite, y) }
    }

    /** Les collections STARTED (miroirs `load()`/`render*` de la maquette). */
    private fun CoroutineScope.brancherCollections() {
        launch {
            viewModel.finir.collect { finir ->
                if (finir) rouvrirEditeur()
            }
        }
        launch {
            viewModel.indiceSelectionne.collect { indice ->
                liaison.vuePreview.surlignerVue(indice)
                majBarreEdition()
            }
        }
        launch {
            // PE7c : les réglages persistés (bulle + mode) pilotent
            // la vue (miroir `load()` au démarrage + `placeBubble`).
            viewModel.reglagesApercu.collect { reglages ->
                liaison.bullePalette.aDroite = reglages.bulleADroite
                liaison.bullePalette.yNormalise = reglages.bulleY
                liaison.bullePalette.fixe = reglages.bulleFixe
                liaison.bullePalette.reposer()
                palette?.definirBulleFixe(reglages.bulleFixe)
                palette?.definirMode(reglages.paletteParPopup, notifier = false)
            }
        }
        launch {
            // Les RÉCENTS de la palette (miroir `recents`).
            viewModel.recents.collect { recents ->
                palette?.definirRecents(recents)
            }
        }
        launch {
            // PE6e-f + sous-titre : chaque rendu met le titre à jour
            // (profil, orientation — miroir `ttlProfile`/`lblOrient`).
            viewModel.moteur.etat.collect { majTitre() }
        }
    }

    /**
     * La feuille inférieure (PE6d) : le fragment porte son contenu —
     * l'activité pose le BottomSheetBehavior, le HUD de zoom REMONTE au
     * déploiement (miroir `applySheet` : `status/zoom .bottom` suive la
     * feuille) et la bulle ÉVITE la zone déployée.
     */
    private fun monterFeuille() {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.conteneur_feuille, FeuilleApercuFragment())
            .commit()
        supportFragmentManager.executePendingTransactions()
        // Le comportement vit sur le CONTENEUR (enfant direct du
        // CoordinatorLayout, app:layout_behavior du XML) — PAS sur la
        // vue du fragment (enfant du conteneur : from() planterait).
        val comportement = BottomSheetBehavior.from(liaison.conteneurFeuille)
        comportement.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(
                    vue: View,
                    etat: Int,
                ) = Unit

                override fun onSlide(
                    vue: View,
                    decalage: Float,
                ) {
                    val feuille = liaison.conteneurFeuille.getChildAt(0) ?: return
                    val delta = (feuille.height - comportement.peekHeight).coerceAtLeast(0).toFloat()
                    liaison.hudZoom.translationY = -delta * decalage
                    // La bulle ne glisse pas SOUS la feuille déployée
                    // (miroir le `hi` de `placeBubble` qui suit `S.open`).
                    liaison.bullePalette.reserveBas =
                        (liaison.hudZoom.height + dp(RETRAIT_HUD_DP)) + (delta * decalage).toInt()
                    liaison.bullePalette.reposer()
                }
            },
        )
    }

    /**
     * La couture SÉLECTION/DÉPÔT (PE6d + PE7b) : le contrôleur remonte
     * l'indice de la vue touchée, la sélection partagée pilote le
     * SURLIGNAGE (la source de vérité est le StateFlow — tap, feuille et
     * dépôt s'y alignent) et le dépôt de la palette écrit par le
     * ViewModel ([PreviewViewModel.appliquerDepot] — InsertionXml → port
     * `SourceApercu.remplacer`, le rendu repart par le chemin normal).
     */
    private fun brancherSelectionEtDepot() {
        controleur =
            ControleurPreviewXml(
                vue = liaison.vuePreview,
                moteur = viewModel.moteur,
                journal = journal,
                scope = lifecycleScope,
                auSelection = { indice -> viewModel.selectionner(indice) },
            )
        liaison.vuePreview.auDepot = { contenu, x, y ->
            val ok = viewModel.appliquerDepot(contenu, x, y)
            if (ok) {
                toast(getString(R.string.preview_toast_depot_ok, contenu.nomAffiche, nomConteneur()))
            } else {
                toast(getString(R.string.preview_toast_depot_ko))
            }
        }
    }

    // ── Palette et popup de placement (PE7c) ─────────────────────────────

    /** Ouvre/FERME la palette (miroir `showPalette` : re-tap = fermer). */
    private fun basculerPalette() {
        val paletteCourante = palette
        if (paletteCourante == null || !estAffichee(paletteCourante)) {
            ouvrirPalette()
        } else {
            masquerPalette()
        }
    }

    /** La palette est-elle à l'écran ? (extension KTX — lint UseKtx) */
    private fun estAffichee(vue: View): Boolean = vue.isVisible

    /** Construit (une fois) et OUVERT la palette (miroir `showPalette`). */
    private fun ouvrirPalette() {
        masquerPlacement()
        val paletteCourante = palette ?: PalettePopover(context = this).also { palette = it }
        if (paletteCourante.parent == null) {
            liaison.conteneurPalette.addView(paletteCourante)
        }
        paletteCourante.auFermer = { masquerPalette() }
        paletteCourante.auBulleFixe = { fixe ->
            viewModel.poserBulleFixe(fixe)
            toast(if (fixe) R.string.preview_bulle_fixee_toast else R.string.preview_bulle_flottante_toast)
        }
        paletteCourante.auModeChange = { parPopup -> viewModel.poserModePalette(parPopup) }
        paletteCourante.auTapIndique = { entree -> toast(R.string.preview_palette_toast_drag) }
        paletteCourante.auTuilePlace = { entree -> ouvrirPlacement(entree) }
        paletteCourante.auGlisser = { entree, vueTuile -> lancerDrag(entree, vueTuile) }
        liaison.conteneurPalette.visibility = View.VISIBLE
    }

    /** Masque la palette (miroir `hidePop` : les expanded tombent). */
    private fun masquerPalette() {
        liaison.conteneurPalette.visibility = View.GONE
    }

    /**
     * Le glisser d'une tuile (PE7c — miroir `beginDrag` : « la palette
     * lance `View.startDragAndDrop` puis se masque ») : le contenu
     * voyage en localState (contrat PE7b — [ZonesDepot.ContenuDepot]),
     * l'ombre porte la tuile ; la palette SE MASQUE le temps du drag.
     */
    private fun lancerDrag(
        entree: CataloguePalette.EntreePalette,
        vueTuile: View,
    ) {
        val contenu = ZonesDepot.ContenuDepot(entree.extraitXml, entree.nomAffiche, entree.id)
        masquerPalette()
        vueTuile.startDragAndDrop(null, View.DragShadowBuilder(vueTuile), contenu, 0)
    }

    /**
     * Ouvre le POPUP de placement (miroir `showPlace`) : la référence
     * part de la SÉLECTION (ou de la racine), la position par DÉFAUT suit
     * [PlacementPopup.positionInitiale], le repère EN DIRECT s'allume
     * (miroir `S.dropView = {w, target}`).
     */
    private fun ouvrirPlacement(entree: CataloguePalette.EntreePalette) {
        val vues = viewModel.dernierRendu?.vues
        if (vues.isNullOrEmpty()) {
            toast(R.string.preview_edition_impossible_toast)
            return
        }
        masquerPalette()
        widgetEnPlacement = ZonesDepot.ContenuDepot(entree.extraitXml, entree.nomAffiche, entree.id)
        val popupCourante = popupPlacement ?: PopupPlacement(context = this).also { popupPlacement = it }
        if (popupCourante.parent == null) {
            liaison.conteneurPlacement.addView(popupCourante)
        }
        popupCourante.auFermer = { masquerPlacement() }
        popupCourante.auReference = { indice -> majPlacement(reference = indice) }
        popupCourante.auPosition = { position -> majPlacement(position = position) }
        popupCourante.auConfirmer = { confirmerPlacement() }
        liaison.conteneurPlacement.visibility = View.VISIBLE

        indiceReferencePlacement = viewModel.indiceSelectionne.value.takeIf { it in vues.indices } ?: 0
        positionPlacement =
            PlacementPopup.positionInitiale(vues, indiceReferencePlacement) ?: PlacementPopup.Position.APRES
        majPlacement()
    }

    /**
     * RE-MARCHE le placement (miroir `drawPlace`) : la position tombe
     * dans les AUTORISÉES de la référence (miroir `normPos`), la cible se
     * dérive ([PlacementPopup.cible]), le popup se redessine et le
     * REPÈRE EN DIRECT suit la cible (zones de dépôt PE7b — un calque).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde est
     * une issue DISTINCTE du refus (X4 : pas de rendu, popup fermé,
     * widget absent).
     */
    @Suppress("ReturnCount")
    private fun majPlacement(
        reference: Int? = null,
        position: PlacementPopup.Position? = null,
    ) {
        val vues = viewModel.dernierRendu?.vues ?: return
        val popupCourante = popupPlacement ?: return
        val widget = widgetEnPlacement ?: return
        if (reference != null) indiceReferencePlacement = reference
        if (position != null) positionPlacement = position
        // Position non autorisée pour la nouvelle référence → repli
        // déterministe (miroir `normPos` : feuille → after, racine → in).
        val autorisees = PlacementPopup.positionsAutorisees(vues, indiceReferencePlacement)
        if (positionPlacement !in autorisees) {
            positionPlacement =
                PlacementPopup.positionInitiale(vues, indiceReferencePlacement)
                    ?: PlacementPopup.Position.APRES
        }
        ciblePlacement = PlacementPopup.cible(vues, indiceReferencePlacement, positionPlacement)
        popupCourante.definirTitre(widget.nomAffiche)
        popupCourante.afficher(vues, indiceReferencePlacement, positionPlacement, ciblePlacement)
        liaison.vuePreview.montrerApercuPlacement(widget)
        liaison.vuePreview.cibleDepot = ciblePlacement
    }

    /** « Placer ici » (miroir `ok.onclick` → `insertAt`) — le toast suit le dépôt. */
    private fun confirmerPlacement() {
        val widget = widgetEnPlacement
        val cible = ciblePlacement
        if (widget == null || cible == null) return
        val ok = viewModel.appliquerPlacement(widget, cible)
        toast(
            if (ok) {
                getString(R.string.preview_toast_depot_ok, widget.nomAffiche, nomConteneur())
            } else {
                getString(R.string.preview_toast_depot_ko)
            },
        )
        masquerPlacement()
    }

    /** Ferme le popup de placement et ÉTEINT le repère (miroir `hidePop`). */
    private fun masquerPlacement() {
        liaison.conteneurPlacement.visibility = View.GONE
        widgetEnPlacement = null
        ciblePlacement = null
        liaison.vuePreview.masquerApercuPlacement()
    }

    // ── Barre d'édition (PE7d) ───────────────────────────────────────────

    /** Duplique la vue sélectionnée (miroir `dupSel` — toast au libellé). */
    private fun dupliquer() {
        val indice = viewModel.indiceSelectionne.value
        if (!viewModel.dupliquerSelection()) {
            toast(R.string.preview_edition_impossible_toast)
            return
        }
        toast(R.string.preview_edition_dupliquee_toast, viewModel.libelleVue(indice))
    }

    /** Supprime la vue sélectionnée (miroir `delSel`). */
    private fun supprimer() {
        val libelle = viewModel.libelleVue(viewModel.indiceSelectionne.value)
        if (!viewModel.supprimerSelection()) {
            toast(R.string.preview_edition_impossible_toast)
            return
        }
        toast(R.string.preview_edition_supprimee_toast, libelle)
        majBarreEdition()
    }

    /** L'état des boutons dupliquer/supprimer (miroir `updateEditBar`). */
    private fun majBarreEdition() {
        val editable = viewModel.selectionEditable()
        liaison.boutonDupliquer.isEnabled = editable
        liaison.boutonSupprimer.isEnabled = editable
    }

    // ── Chrome PE6b + PE6e-f ─────────────────────────────────────────────

    override fun onDestroy() {
        controleur?.fermer()
        controleur = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_preview, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_choisir_fichier -> {
                ouvrirSelecteurFichier()
                true
            }

            else -> {
                super.onOptionsItemSelected(item)
            }
        }

    /**
     * Feuille de choix de l'appareil (PE6b, miroir `showPop("device")`
     * de la maquette) : menu RADIO ancré à la puce — chaque entrée porte
     * la description courte du profil (nom · WxHdp · dpi), l'entrée
     * courante est cochée.
     */
    private fun ouvrirFeuilleAppareil() {
        if (menuApercu.estOuvert) {
            menuApercu.masquer()
            return
        }
        menuApercu.montrer(R.layout.popover_menu_appareil, liaison.boutonAppareilApercu) { vue ->
            val popover = PopoverMenuAppareilBinding.bind(vue)
            // L'ordre des rangées du XML suit ProfilPeripherique.CATALOGUE.
            val ranges =
                listOf(
                    popover.appareilPixel4,
                    popover.appareilPixel7Pro,
                    popover.appareilGalaxyS24,
                    popover.appareilTablette,
                    popover.appareilPetit,
                )
            val courant = controleur?.profil
            ProfilPeripherique.CATALOGUE.forEachIndexed { indice, profil ->
                val rangee = ranges[indice]
                rangee.libelleAppareil.text = profil.description()
                rangee.radioAppareil.isChecked = profil == courant
                rangee.rangeeAppareil.setOnClickListener {
                    rangee.radioAppareil.isChecked = true
                    controleur?.definirProfil(profil)
                    menuApercu.masquer()
                    majReglages()
                    majTitre()
                }
            }
        }
    }

    /**
     * Popover Calques (PE6b, miroir `showPop("layers")` de la maquette) :
     * menu à COCHES ancré à la puce — chaque entrée bascule un calque de
     * la vue (états relus à l'ouverture, jamais mémorisés en double).
     */
    private fun ouvrirPopoverCalques() {
        if (menuApercu.estOuvert) {
            menuApercu.masquer()
            return
        }
        menuApercu.montrer(R.layout.popover_menu_calques, liaison.boutonCalquesApercu) { vue ->
            lierCalques(PopoverMenuCalquesBinding.bind(vue))
        }
    }

    /** Les SIX entrées du menu Calques (titre, état lu à la volée, pose). */
    private fun entreesCalques(vueRendu: VuePreviewXml): List<EntreeCalque> =
        listOf(
            EntreeCalque(R.string.preview_calque_parent_freres, { vueRendu.calquesParentFreres }, {
                vueRendu.calquesParentFreres =
                    it
            }),
            EntreeCalque(R.string.preview_calque_marges_padding, { vueRendu.calquesMargesPadding }, {
                vueRendu.calquesMargesPadding =
                    it
            }),
            EntreeCalque(R.string.preview_calque_contraintes, { vueRendu.calquesContraintes }, {
                vueRendu.calquesContraintes =
                    it
            }),
            EntreeCalque(
                R.string.preview_calque_problemes,
                { vueRendu.calquesProblemes },
                { vueRendu.calquesProblemes = it },
            ),
            EntreeCalque(R.string.preview_calque_bornes, { vueRendu.calquesBornes }, { vueRendu.calquesBornes = it }),
            EntreeCalque(R.string.preview_calque_grille, { vueRendu.grilleAffichee }, { vueRendu.grilleAffichee = it }),
        )

    /**
     * Lie les SIX calques aux rangées du popover (PE9c) — l'accent
     * COLORÉ de chaque calque est un rôle sémantique du thème (canaux
     * tooling, erreur, succès, info), la rangée bascule le commutateur.
     */
    private fun lierCalques(popover: PopoverMenuCalquesBinding) {
        val calques = entreesCalques(liaison.vuePreview)
        val accents =
            listOf(
                RUi.attr.colorCanalSync,
                RUi.attr.colorCanalTaches,
                RUi.attr.colorCanalBuild,
                androidx.appcompat.R.attr.colorError,
                RUi.attr.colorSucces,
                RUi.attr.colorInfo,
            )
        val ranges =
            listOf(
                popover.calqueParentFreres,
                popover.calqueMargesPadding,
                popover.calqueContraintes,
                popover.calqueProblemes,
                popover.calqueBornes,
                popover.calqueGrille,
            )
        calques.forEachIndexed { indice, calque ->
            val rangee = ranges[indice]
            rangee.pastilleCalque.backgroundTintList =
                ColorStateList.valueOf(StyleVolet.couleur(this, accents[indice]))
            rangee.libelleCalque.setText(calque.titreRes)
            rangee.basculeCalque.isChecked = calque.estActif()
            rangee.rangeeCalque.setOnClickListener { rangee.basculeCalque.toggle() }
            rangee.basculeCalque.setOnCheckedChangeListener { _, coche ->
                calque.poser(coche)
            }
        }
    }

    /**
     * Piège « changement de fichier » : le titre devient un sélecteur —
     * le menu liste les documents ouverts previewables (registre des
     * sources, classification conservatrice).
     */
    private fun ouvrirSelecteurFichier() {
        val sources = viewModel.sourcesPreviewables()
        if (sources.isEmpty()) {
            journal.w(TAG) { "sélecteur ouvert : aucun document previewable ouvert" }
            return
        }
        val libelles = sources.map { source -> source.cheminRelatif }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preview_choisir_titre)
            .setItems(libelles.toTypedArray()) { _, indice ->
                val source = sources.getOrNull(indice) ?: return@setItems
                viewModel.ouvrirFichier(source.uri)
                majTitre()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Libellés des puces de réglages (miroir `updateChips` de la maquette). */
    private fun majReglages() {
        val controleur = controleur ?: return
        liaison.boutonAppareilApercu.text = getString(R.string.preview_appareil_format, controleur.profil.nom)
        liaison.boutonOrientationApercu.setText(
            if (viewModel.paysage) R.string.preview_paysage else R.string.preview_portrait,
        )
        liaison.boutonOrientationApercu.isChecked = viewModel.paysage
        liaison.boutonNuitApercu.setText(
            if (controleur.nuit) {
                R.string.preview_nuit
            } else {
                R.string.preview_jour
            },
        )
        liaison.boutonNuitApercu.isChecked = controleur.nuit
        val langueAffichee =
            when (controleur.langue) {
                "fr" -> getString(R.string.preview_langue_fr)
                "en" -> getString(R.string.preview_langue_en)
                else -> getString(R.string.preview_langue_defaut)
            }
        // Miroir `lblLang` : « Langue : défaut / FR / EN » (préfixe).
        liaison.boutonLangueApercu.text = getString(R.string.preview_langue_format, langueAffichee)
        // Miroir `lblFont` : « Police ×1,0 » (virgule décimale FR).
        val echelle = viewModel.echellePolice
        val echelleFormatee = String.format(Locale.FRANCE, "%.1f", echelle)
        liaison.boutonPoliceApercu.text = getString(R.string.preview_police_format, echelleFormatee)
        liaison.boutonPoliceApercu.isChecked = echelle != RequeteRendu.ECHELLE_POLICE_DEFAUT
    }

    /** Le bouton « 100 % » affiche l'échelle RÉELLE (PE6b — miroir `zPct`). */
    private fun majPourcentageZoom(echelle: Float) {
        liaison.boutonZoomPct.text = getString(R.string.preview_zoom_pct, (echelle * POURCENTAGE).roundToInt())
    }

    /**
     * Le titre + le SOUS-TITRE de la toolbar (miroir `.ttl` de la
     * maquette : le fichier en gras mono, « res/layout · rendu en Pixel
     * 4 » dessous) — audit design : le sous-titre MANQUAIT au code.
     */
    private fun majTitre() {
        val nom = viewModel.nomFichierCourant()
        supportActionBar?.title = nom.ifBlank { getString(R.string.preview_titre) }
        supportActionBar?.subtitle =
            getString(
                R.string.preview_sous_titre_format,
                dossierSource(),
                controleur?.profil?.nom ?: "",
            )
        majReglages()
    }

    /** Le dossier source affiché (miroir « res/layout » — le dossier de la ressource). */
    private fun dossierSource(): String {
        val chemin = viewModel.source?.cheminRelatif ?: return ""
        val dossier = chemin.substringBeforeLast('/')
        val apresRes = dossier.substringAfter("/res/", "")
        return if (apresRes.isNotEmpty()) "res/$apresRes" else dossier
    }

    /** Le conteneur visé du DERNIER dépôt réussi (libellé du toast, miroir `par.cls`). */
    private fun nomConteneur(): String = viewModel.dernierParentDepot ?: ""

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(texte: String) {
        Toast.makeText(this, texte, Toast.LENGTH_SHORT).show()
    }

    private fun toast(
        resId: Int,
        argument: String,
    ) {
        Toast.makeText(this, getString(resId, argument), Toast.LENGTH_SHORT).show()
    }

    /**
     * Le registre est vide (processus mort, Android ne recrée que
     * l'activité du dessus) : relance de l'éditeur puis fin — l'éditeur
     * recharge ses onglets, l'utilisateur rouvre l'aperçu d'un geste.
     */
    private fun rouvrirEditeur() {
        if (isFinishing) return
        journal.w(TAG) { "registre vide — relance de l'éditeur (projectId=${viewModel.projectId})" }
        if (viewModel.projectId.isNotBlank()) {
            navigateur.openEditor(viewModel.projectId)
        }
        finish()
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    /** UNE entrée du popover Calques : titre, état lu à la volée, pose. */
    private data class EntreeCalque(
        val titreRes: Int,
        val estActif: () -> Boolean,
        val poser: (Boolean) -> Unit,
    )

    private companion object {
        private const val TAG: String = "PreviewActivity"

        /** Facteur des boutons de zoom (miroir maquette `zoomAt(…, 1.25)`). */
        private const val FACTEUR_ZOOM: Float = 1.25f

        /** Conversion échelle → pourcentage affiché (2,56 = 256 %). */
        private const val POURCENTAGE: Int = 100

        /** Retrait autour du HUD de zoom, en dp (réserve de la bulle). */
        private const val RETRAIT_HUD_DP: Int = 16
    }
}
