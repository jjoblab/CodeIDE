package jo.codeide.feature.preview

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
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
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.ModeApercu
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.RegimeRendu
import jo.codeide.preview.xml.RequeteRendu
import jo.codeide.preview.xml.ZonesDepot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

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
 * - CETTE activité : chrome — BARRE DU HAUT 52 dp en LinearLayout (PE11i,
 *   ADR 0134 D1 : bouton retour, titre mono + sous-titre, PASTILLE de
 *   mode, bouton plus → PopupMenu — plus d'ActionBar), PUCES défilantes
 *   (miroir `.chips` : appareil ▾, portrait/paysage PE6e-f, jour, langue,
 *   police PE6e-f, calques ▾), HUD de zoom, BARRE D'ÉDITION (PE7d,
 *   miroir `.edit` : annuler, rétablir | dupliquer, supprimer), BULLE
 *   + PALETTE + POPUP DE PLACEMENT (PE7c, miroirs `.bubble`/`.pop.pal`/
 *   `.pop.bot`), BANDEAU d'erreur et TOAST en VUES (PE11i, D6) — le
 *   module porte le chrome, le moteur `:preview:xml` reste zéro-Material
 *   (règle du module) et reçoit ses couleurs par JETONS injectés (D7).
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

    /** Le BANDEAU d'erreur/toast en vues (PE11i, D6 — chrome dédié). */
    private val bandeau: BandeauApercu by lazy { BandeauApercu(liaison) }

    /** Le TOAST en vue (PE11i, D6 — remplace tout Toast système). */
    private val toastApercu: ToastApercu by lazy { ToastApercu(liaison.toastApercu) }

    /** Le popover CALQUES (PE11k — extraction chrome pur, miroir `showPop("layers")`). */
    private val popoverCalques: PopoverCalquesApercu by lazy {
        PopoverCalquesApercu(
            contexte = this,
            vueRendu = liaison.vuePreview,
            barreNavigation = { controleur?.barreNavigation ?: true },
            poserBarreNavigation = { coche -> controleur?.definirBarreNavigation(coche) },
        )
    }

    /** Le processus `:preview` est-il ARRÊTÉ (bandeau « Réessayer », PE11i D6). */
    private var regimeArrete = false

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

        // PE11i (ADR 0134 D1) : plus d'ActionBar — la barre du haut est un
        // LinearLayout de 52 dp ; l'inset de barre d'état va à l'EN-TÊTE
        // (fond surface), le menu « Choisir le fichier… » s'ouvre par un
        // PopupMenu ancré au bouton « plus ».
        liaison.entetePreview.applySystemBarsInsets(top = true, bottom = false)
        liaison.boutonRetour.setOnClickListener { navigateur.goBack() }
        liaison.boutonPlus.setOnClickListener { ouvrirMenuPlus() }
        // PE11i (D7) : les JETONS du moteur sont posés AVANT tout tracé.
        poserJetonsMoteur()

        brancherSelectionEtDepot()
        brancherPucesReglages()
        brancherModeApercu()
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

    /**
     * La PASTILLE segmented Design/Plan/Les deux (PE10d/e, miroir
     * `segMode` de la maquette) : le mode vit dans la VUE — le fit se
     * recalcule (Les deux double la largeur) et le rendu suit.
     */
    private fun brancherModeApercu() {
        // PE11i (D2) : ChipGroup à sélection unique OBLIGATOIRE —
        // l'écouteur devient setOnCheckedStateChangeListener.
        liaison.groupeModeApercu.setOnCheckedStateChangeListener { groupe, _ ->
            liaison.vuePreview.modeAffichage =
                when (groupe.checkedChipId) {
                    R.id.mode_plan -> ModeApercu.PLAN
                    R.id.mode_les_deux -> ModeApercu.LES_DEUX
                    else -> ModeApercu.DESIGN
                }
            liaison.vuePreview.ajuster()
        }
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
            // PE10c : la barre d'édition suit l'HISTORIQUE (les icônes
            // annuler/rétablir se désactivent quand la pile est épuisée).
            viewModel.historiqueEdition.collect { majBarreEdition() }
        }
        launch {
            // PE6e-f + sous-titre + PASTILLE d'état (PE10a) : chaque état
            // du moteur met le chrome à jour (profil, orientation, durée,
            // vues — miroir `ttlProfile`/`lblOrient`/`renderStatus`).
            // PE11i (D6) : le BANDEAU suit le même état (vue, plus de
            // dessin moteur).
            viewModel.moteur.etat.collect { etat ->
                majTitre()
                majPastilleEtat(etat)
                majBandeau(etat)
            }
        }
        launch {
            // PE11i (D6) : le régime ISOLÉ / LOCAL_DÉGRÉ porte le bandeau
            // « processus arrêté » (bouton « Réessayer »).
            viewModel.moteur.regime.collect { regime -> majBandeauRegime(regime) }
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
                ) {
                    // PE10 : plus de compaction — la feuille recouvre le
                    // rendu (miroir applySheet) ; les états posés fixent
                    // la glissade (expanded = tout déployé, sinon repli).
                    val feuille = liaison.conteneurFeuille.getChildAt(0) ?: return
                    val delta = (feuille.height - comportement.peekHeight).coerceAtLeast(0).toFloat()
                    majGlissadeFeuille(
                        if (etat == BottomSheetBehavior.STATE_EXPANDED) delta else 0f,
                    )
                }

                override fun onSlide(
                    vue: View,
                    decalage: Float,
                ) {
                    val feuille = liaison.conteneurFeuille.getChildAt(0) ?: return
                    val delta = (feuille.height - comportement.peekHeight).coerceAtLeast(0).toFloat()
                    majGlissadeFeuille(delta * decalage)
                }
            },
        )
        // PE11l (bug rapporté par le propriétaire : « Zoom / Rendu · N vues
        // s'affichent normalement seulement après avoir déployé la
        // feuille ») : la glissade n'était posée que par les CALLBACKS
        // (onStateChanged/onSlide) — au lancement, la feuille est
        // REPLIÉE et AUCUN callback n'a encore tiré : le HUD de zoom et
        // la pastille d'état restaient posés au bord, SOUS l'entête
        // visible (peek). La pose INITIALE (feuille repliée, part
        // déployée nulle) suit le même montage que la maquette
        // (`applySheet` au chargement) : le fragment est déjà monté et
        // le peek posé (executePendingTransactions ci-dessus), les
        // formules [GlissadeFeuilleApercu] font le reste.
        majGlissadeFeuille(0f)
    }

    /**
     * La GLISSADE de la feuille (PE10a — miroir `applySheet` de la
     * maquette : `status/zoom .bottom` suivent la feuille) : le HUD de
     * zoom et la PASTILLE D'ÉTAT glissent au-dessus du bord COURANT de
     * la feuille (peek compris — correctif PE11k : repliée, l'entête
     * recouvrait les deux pièces), la bulle ÉVITE la zone visible.
     */
    private fun majGlissadeFeuille(deployeePx: Float) {
        // Garde : la vue de la feuille doit exister (le fragment est monté).
        liaison.conteneurFeuille.getChildAt(0) ?: return
        val comportement = BottomSheetBehavior.from(liaison.conteneurFeuille)
        // La bulle ne glisse pas SOUS la feuille déployée
        // (miroir le `hi` de `placeBubble` qui suit `S.open`).
        // PE11k : la réserve de la bulle et la translation des pièces
        // basses partent du PEEK — formules pures [GlissadeFeuilleApercu].
        liaison.hudZoom.translationY =
            GlissadeFeuilleApercu.translationHud(comportement.peekHeight, deployeePx.toInt())
        liaison.pastilleEtat.translationY = liaison.hudZoom.translationY
        liaison.bullePalette.reserveBas =
            GlissadeFeuilleApercu.reserveBulle(
                hudPx = liaison.hudZoom.height,
                margePx = resources.getDimensionPixelSize(R.dimen.apercu_hud_marge),
                peekPx = comportement.peekHeight,
                deployeePx = deployeePx.toInt(),
            )
        liaison.bullePalette.reposer()
    }

    /**
     * La PASTILLE D'ÉTAT (PE10a, miroir `renderStatus` de la maquette) :
     * point coloré + « Rendu · X ms · N vues · W×H dp » (rendu valide),
     * ambre « XML en cours de frappe » (miroir `.status.edit`), rouge
     * « Dernier rendu valide conservé » (miroir `.status.err`), masquée
     * sans document (miroir l'état inactif).
     */
    private fun majPastilleEtat(etat: EtatPreviewXml) {
        when (etat) {
            is EtatPreviewXml.RenduValide -> {
                val profil = viewModel.moteur.profil
                val dimensions =
                    getString(
                        R.string.preview_pastille_dimensions,
                        if (viewModel.paysage) profil.hauteurDp else profil.largeurDp,
                        if (viewModel.paysage) profil.largeurDp else profil.hauteurDp,
                    )
                liaison.pastilleEtatTexte.text =
                    resources.getQuantityString(
                        R.plurals.preview_pastille_rendu,
                        etat.nombreVues,
                        etat.nombreVues,
                        etat.dureeMs,
                        dimensions,
                    )
                teinterPastille(R.attr.apercuSucces)
            }

            is EtatPreviewXml.ErreurRendu -> {
                liaison.pastilleEtatTexte.setText(R.string.preview_pastille_erreur)
                teinterPastille(R.attr.apercuErreur)
            }

            EtatPreviewXml.EnEdition -> {
                liaison.pastilleEtatTexte.setText(R.string.preview_pastille_edition)
                // L'ambre de la maquette (`.status.edit` — jeton apercuAvert).
                teinterPastille(R.attr.apercuAvert)
            }

            EtatPreviewXml.Inactif -> {
                liaison.pastilleEtat.isVisible = false
            }
        }
    }

    /** La COULEUR du point de la pastille (jeton sémantique du thème). */
    private fun teinterPastille(attribut: Int) {
        liaison.pastillePoint.backgroundTintList = ColorStateList.valueOf(StyleVolet.couleur(this, attribut))
        liaison.pastilleEtat.isVisible = true
    }

    // ── Bandeau, toast et jetons (PE11i — ADR 0134 D6/D7) ──────────────

    /**
     * Le bandeau suit l'ÉTAT du moteur (PE11i, D6) : l'erreur sémantique
     * avec UNE ligne de message (le dernier rendu reste affiché dessous),
     * boutons « Ligne n » et « Détails » ↔ « Réduire » — le bandeau du
     * processus arrêté PRIME (régime). Le chrome vit dans [BandeauApercu].
     */
    private fun majBandeau(etat: EtatPreviewXml) {
        if (regimeArrete) return
        val erreur = etat as? EtatPreviewXml.ErreurRendu
        if (erreur == null) {
            bandeau.masquer()
            return
        }
        bandeau.montrerErreur(erreur) { ligne -> ouvrirSourceALigne(ligne) }
    }

    /**
     * Le bandeau du PROCESSUS ARRÊTÉ (PE11i, D6) : message honnête du
     * repli local + bouton « Réessayer » (réactivation de l'isolation).
     */
    private fun majBandeauRegime(regime: RegimeRendu) {
        regimeArrete = regime == RegimeRendu.LOCAL_DEGRADE
        if (regimeArrete) {
            bandeau.montrerProcessusArrete(getString(R.string.preview_bandeau_crash)) {
                viewModel.reactiverIsolation()
            }
        } else {
            bandeau.masquer()
        }
    }

    /** « Ligne n » : ouvre l'onglet Source de la feuille, amené à la ligne. */
    private fun ouvrirSourceALigne(ligne: Int) {
        (supportFragmentManager.findFragmentById(R.id.conteneur_feuille) as? FeuilleApercuFragment)
            ?.ouvrirSourceALigne(ligne)
    }

    /**
     * Les JETONS du moteur (PE11i, D7) : résolus du THÈME courant (clair
     * ou nuit) et injectés AVANT le premier tracé, puis à chaque
     * changement de configuration (le thème suit l'application).
     */
    private fun poserJetonsMoteur() {
        liaison.vuePreview.appliquerJetons(StyleVolet.jetonsCalques(this))
    }

    override fun onConfigurationChanged(nouvelleConfiguration: Configuration) {
        super.onConfigurationChanged(nouvelleConfiguration)
        // PE11i (D7) : jour/nuit système → les jetons du moteur se
        // RE-POSENT (l'activité est recréée par défaut ; ceci couvre les
        // changements déclarés au manifeste).
        poserJetonsMoteur()
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
        // PE11l : le relâchement d'une POIGNÉE d'angle redimensionne la
        // sélection (UNE édition groupée des deux axes — la racine n'est
        // pas redimensionnable, l'échec part en toast honnête).
        liaison.vuePreview.auRedimensionner = { largeurDp, hauteurDp ->
            val libelle =
                getString(R.string.preview_dimension_format, largeurDp, hauteurDp)
            if (viewModel.redimensionnerSelection(largeurDp, hauteurDp)) {
                toast(getString(R.string.preview_toast_redimensionne, libelle))
            } else {
                toast(R.string.preview_toast_redimension_ko)
            }
        }
    }

    // ── Palette et popup de placement (PE7c) ─────────────────────────────

    /**
     * Ouvre/FERME la palette (miroir `showPalette` : re-tap = fermer).
     *
     * Correctif PE11j (bug rapporté par le propriétaire : « la bulle ne
     * redevient pas active après avoir choisi [un widget dans] une
     * palette ») : l'état testé est celui du CONTENEUR —
     * [masquerPalette] pose `conteneur_palette` à `GONE` sans toucher au
     * drapeau de visibilité de la popover ENFANTE (l'extension KTX
     * `isVisible` ne regarde QUE le drapeau de la vue) : après la
     * première fermeture, l'ancien test renvoyait TOUJOURS « affichée »
     * et le tap re-masquait au lieu de rouvrir — la bulle paraissait
     * désactivée.
     */
    private fun basculerPalette() {
        if (!liaison.conteneurPalette.isVisible) {
            ouvrirPalette()
        } else {
            masquerPalette()
        }
    }

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
        // PE11j (ADR 0134 D9) : la hauteur de la coque est bornée à
        // min(600 dp, conteneur − marges) AVANT la pose (sans borne la
        // grille déborde de l'écran — le LinearLayout enveloppe son
        // contenu). Repli : pas encore de mise en page (hauteur 0) → la
        // borne repasse au prochain tap.
        if (liaison.conteneurPalette.height > 0) {
            paletteCourante.bornerHauteur(liaison.conteneurPalette.height)
        }
        liaison.conteneurPalette.visibility = View.VISIBLE
        liaison.bullePalette.ouverte = true
    }

    /** Masque la palette (miroir `hidePop` : les expanded tombent) et ferme la bulle. */
    private fun masquerPalette() {
        liaison.conteneurPalette.visibility = View.GONE
        liaison.bullePalette.ouverte = false
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
        // PE9e : le GHOST stylé (pill violette icône + nom — miroir `.ghost`)
        // remplace l'ombre par défaut de la tuile.
        vueTuile.startDragAndDrop(null, GhostPaletteDragShadowBuilder(this, entree), contenu, 0)
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

    /** L'état des QUATRE boutons d'édition (PE10c — miroir `updateEditBar` : historique + sélection). */
    private fun majBarreEdition() {
        val historique = viewModel.historiqueEdition.value
        liaison.boutonAnnuler.isEnabled = historique.peutAnnuler
        liaison.boutonRetablir.isEnabled = historique.peutRetablir
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

    /**
     * Le menu « Choisir le fichier… » (PE11i, D1) : l'ancien menu
     * d'options de l'ActionBar devient un PopupMenu ancré au bouton
     * « plus » (`R.menu.menu_preview`).
     */
    private fun ouvrirMenuPlus() {
        val menu = PopupMenu(this, liaison.boutonPlus)
        menu.menuInflater.inflate(R.menu.menu_preview, menu.menu)
        menu.setOnMenuItemClickListener { element ->
            if (element.itemId == R.id.action_choisir_fichier) {
                ouvrirSelecteurFichier()
                true
            } else {
                false
            }
        }
        menu.show()
    }

    /**
     * Feuille de choix de l'appareil (PE6b, miroir `showPop("device")`
     * de la maquette) : menu ancré à la puce — chaque entrée porte le NOM
     * (13 sp) et le DÉTAIL mono 11 « 393×851 dp · 440 dpi », l'entrée
     * courante est SÉLECTIONNÉE (PE11j : plus de radio — miroir `.opt`,
     * `isSelected` = fond accent doux du sélecteur `apercu_option_fond`).
     */
    private fun ouvrirFeuilleAppareil() {
        if (menuApercu.estOuvert) {
            menuApercu.masquer()
            return
        }
        // PE11i (D2) : l'état « menu ouvert » de la puce = isActivated.
        liaison.boutonAppareilApercu.isActivated = true
        menuApercu.auFermer = { liaison.boutonAppareilApercu.isActivated = false }
        // PE11j : la largeur par défaut suit § 6 (pleine largeur − 10 dp
        // de chaque bord), la hauteur est bornée à 430 dp par le menu.
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
                rangee.libelleAppareil.text = profil.nom
                // Détail mono « 393×851 dp · 440 dpi » — pluriel imposé par
                // lint (PluralsCandidate) : la quantité formelle est la
                // densité, le gabarit ne dépend pas d'elle.
                rangee.detailAppareil.text =
                    resources.getQuantityString(
                        R.plurals.preview_appareil_detail_format,
                        profil.densiteDpi,
                        profil.largeurDp,
                        profil.hauteurDp,
                        profil.densiteDpi,
                    )
                rangee.rangeeAppareil.isSelected = profil == courant
                rangee.rangeeAppareil.setOnClickListener {
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
        // PE11i (D2) : l'état « menu ouvert » de la puce = isActivated.
        liaison.boutonCalquesApercu.isActivated = true
        menuApercu.auFermer = { liaison.boutonCalquesApercu.isActivated = false }
        menuApercu.montrer(R.layout.popover_menu_calques, liaison.boutonCalquesApercu) { vue ->
            popoverCalques.lier(PopoverMenuCalquesBinding.bind(vue))
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
        // PE11i (D1) : le titre et le sous-titre sont des TextView du bloc
        // titre (plus d'ActionBar) ; la LÉGENDE de planche suit le profil.
        liaison.titreApercu.text = nom.ifBlank { getString(R.string.preview_titre) }
        liaison.sousTitreApercu.text =
            getString(
                R.string.preview_sous_titre_format,
                dossierSource(),
                controleur?.profil?.nom ?: "",
            )
        liaison.vuePreview.libelleProfil = controleur?.profil?.nom ?: ""
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
        toastApercu.montrer(getString(resId))
    }

    private fun toast(texte: String) {
        toastApercu.montrer(texte)
    }

    private fun toast(
        resId: Int,
        argument: String,
    ) {
        toastApercu.montrer(getString(resId, argument))
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

    private companion object {
        private const val TAG: String = "PreviewActivity"

        /** Facteur des boutons de zoom (miroir maquette `zoomAt(…, 1.25)`). */
        private const val FACTEUR_ZOOM: Float = 1.25f

        /** Conversion échelle → pourcentage affiché (2,56 = 256 %). */
        private const val POURCENTAGE: Int = 100
    }
}
