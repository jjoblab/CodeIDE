package jo.codeide.feature.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import jo.codeeditor.document.Selection
import jo.codeeditor.session.EditorSession
import jo.codeeditor.view.EditorTheme
import jo.codeeditor.view.EditorView
import jo.codeeditor.view.SymbolBarView
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.ControleurTerminalTiroir
import jo.codeide.core.ui.FabriqueFragmentTerminalTiroir
import jo.codeide.core.ui.IconesFichiers
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.applySystemBarsInsetsMargins
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import jo.codeide.feature.editor.databinding.VueOngletFichierBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * Espace de travail d'un projet (étapes 13-16, prompt compagnon section 5) :
 * **trois zones**.
 *
 * - tiroir de navigation gauche — **architecture à fragments** (étape 31,
 *   ADR 0052) : Fichiers/Recherche/Git/Terminal, chacun son entête, rail
 *   de fragments commun, poignée ⋮ de redimensionnement — permanent sur
 *   grand écran (ADR 0026) ;
 * - zone centrale — **onglets de fichiers dynamiques** et éditeur (étape
 *   15, ADR 0028) : un `TabLayout` défilant (icône du langage, nom, point
 *   de modification remplaçant la fermeture tant que l'onglet est sale,
 *   menu contextuel : fermer, fermer les autres, fermer tout, déplacer,
 *   copier le chemin) au-dessus d'**un seul `EditorView`** rebranché sur la
 *   session de l'onglet actif — thème clair/sombre suivant l'application ;
 * - panneau inférieur (étape 16, ADR 0029 ; v0.32.4, ADR 0055) : trois
 *   états pilotés par `BottomSheetBehavior`, en-tête à
 *   poignée/titre/badge/actions, onglets Console/Problèmes/Journal dont
 *   le contenu vit dans des FRAGMENTS montrés/cachés (plus de vues
 *   empilées) ; barre de symboles — la SymbolBarView de la
 *   bibliothèque code-editor — sous l'en-tête, visible quand l'IME est
 *   ouvert, collée au clavier (peek élargi à en-tête + barre) ;
 *
 * Sauvegarde automatique (délai d'inactivité, côté ViewModel) et manuelle
 * (action de la toolbar). Fermeture d'un onglet sale — ou sortie avec des
 * onglets sales — demande Enregistrer / Ne pas enregistrer / Annuler,
 * agrégé pour plusieurs fichiers. Un fichier binaire est proposé à
 * « Ouvrir avec » plutôt qu'affiché illisible.
 *
 * Le bouton retour réduit le panneau étendu, ferme le tiroir s'il est
 * ouvert, sinon quitte — après confirmation si des onglets sont sales.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions et LargeClass —
 * l'activité **rend** les trois zones de l'espace de travail (tiroir,
 * onglets, éditeur, panneau) et applique les effets ; l'éclater par zone
 * casserait la cohérence du cycle de vie unique de l'écran.
 */
@Suppress("TooManyFunctions", "LargeClass", "CyclomaticComplexMethod", "ReturnCount", "MagicNumber")
@AndroidEntryPoint
class EditorActivity :
    AppCompatActivity(),
    ControleurTerminalTiroir,
    ControleurPanneauEditeur {
    private val viewModel: EditorViewModel by viewModels()

    /** Navigation inter-features (lien vers l'écran Diagnostic, étape 16). */
    @Inject
    lateinit var navigateur: AppNavigator

    /** Fabrique du fragment Terminal du tiroir (v0.32.2, ADR 0053) :
     * le rendu réel des sessions vit dans `feature:terminal` — la
     * fabrique seule traverse la frontière (les features ne se
     * référencent pas). */
    @Inject
    lateinit var fabriqueTerminalTiroir: FabriqueFragmentTerminalTiroir

    private lateinit var liaison: ActivityEditorBinding

    private lateinit var comportementPanneau: BottomSheetBehavior<*>

    /** Thèmes cel mis en cache (clair/sombre, suivant l'application). */
    private var themeClair: EditorTheme? = null
    private var themeSombre: EditorTheme? = null

    /** Sélection programmatique d'onglet : ne pas la renvoyer au ViewModel. */
    private var selectionProgrammatique = false

    /** Idem pour les onglets du panneau inférieur (étape 16). */
    private var selectionProgrammatiquePanneau = false

    /** Le tiroir est-il ouvert (pilote le retour système) ? */
    private var tiroirOuvert = false

    /** Destination courante du rail de fragments (§ 14). */
    private var destinationCourante: Int = R.id.destination_explorateur

    /** Largeur visible du tiroir en pixels (§ 13 — 63 % par défaut, mémorisée
     *  par instance sauvegardée). */
    private var largeurTiroirPx: Int = 0

    /** Masquage automatique du snackbar (4 600 ms, § 15). */
    private var travailSnackbar: Job? = null

    /** Le panneau inférieur est-il étendu (pilote le retour système) ? */
    private var panneauEtendu = false

    /** Clavier visible selon les insets IME (API 30+) ? */
    private var clavierParInsets = false

    /** Clavier visible selon le rétrécissement du root (adjustResize) ? */
    private var clavierParHauteur = false

    /** Le clavier virtuel est-il visible (pilote la barre de symboles) ?
     *  OU des deux détecteurs (v0.32.4) : les insets seuls se taisent
     *  sur certains appareils en mode resize hérité, la hauteur seule
     *  se trompe sur les écrans partagés — ensemble ils couvrent tout. */
    private val clavierVisible: Boolean get() = clavierParInsets || clavierParHauteur

    /** Tâche de débounce du fil d'Ariane (200 ms, BreadCrumbBar). */
    private var travailFil: Job? = null

    /** Plus grande hauteur du root vue (détection IME API < 30). */
    private var hauteurRacineMax = 0

    /** Des onglets sont-ils sales (pilote le retour système) ? */
    private var ongletsSales = false

    /** Retour système : réduit le panneau étendu, ferme le tiroir ouvert,
     * confirme les onglets sales, sinon quitte. */
    private val retourEspace =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    panneauEtendu -> {
                        viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.MI_HAUTEUR))
                    }

                    tiroirOuvert -> {
                        liaison.racineEditeur.closeDrawer(liaison.tiroir)
                    }

                    else -> {
                        viewModel.onAction(ActionEditor.Quitter)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.let {
            largeurTiroirPx = it.getInt(CLE_LARGEUR_TIROIR, 0)
            destinationCourante = it.getInt(CLE_DESTINATION_TIROIR, R.id.destination_explorateur)
        }
        liaison = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(liaison.root)

        brancherInsets()
        brancherTiroir()
        brancherFragmentsTiroir()
        brancherPoignee()
        brancherOnglets()
        brancherVueVide()
        brancherFilAriane()
        brancherEditeur()
        brancherPanneauInferieur()
        brancherBarreSymboles()
        onBackPressedDispatcher.addCallback(this, retourEspace)

        // Langue des libellés du catalogue (étape 18) — re-émise à chaque
        // re-création de l'activité, donc après chaque changement de langue.
        viewModel.onAction(ActionEditor.PreciserLangue(langueCourante()))

        viewModel.etat.collectWithLifecycle(this, Lifecycle.State.STARTED) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(this, Lifecycle.State.STARTED) { effet -> appliquer(effet) }
    }

    /**
     * Langue des libellés du catalogue (étape 18) : configuration effective
     * de l'activité (langue par application, ADR 0013), bornée aux langues
     * connues du moteur — toute autre langue de système replie sur la
     * valeur par défaut.
     */
    private fun langueCourante(): String {
        val langue = resources.configuration.locales[0]?.language ?: return TemplateOptions.LANGUE_DEFAUT
        return if (TemplateOptions.langueValide(langue)) langue else TemplateOptions.LANGUE_DEFAUT
    }

    /** Applique les insets edge-to-edge : toolbar paddingée en haut, tiroir
     *  sous la barre de statut (MARGE haute — v0.32.1) et AU-DESSUS de la
     *  barre de navigation (MARGE basse — v0.32.2 : retour d'appareil réel,
     *  « le tiroir chevauche la navbar » — il ne peint plus rien derrière
     *  l'une ni l'autre, le rail du tiroir s'arrête au-dessus des gestes). */
    private fun brancherInsets() {
        liaison.toolbarEditeur.applySystemBarsInsets(top = true, bottom = false)
        liaison.tiroir.applySystemBarsInsetsMargins()
    }

    /** Ouvre/ferme le tiroir ; sur grand écran il reste ancré (ADR 0026). */
    private fun brancherTiroir() {
        liaison.toolbarEditeur.setNavigationOnClickListener {
            liaison.racineEditeur.openDrawer(liaison.tiroir)
        }

        liaison.racineEditeur.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerStateChanged(nouvelEtat: Int) {
                    majRetourSysteme(
                        tiroirOuvert =
                            nouvelEtat == DrawerLayout.STATE_IDLE &&
                                liaison.racineEditeur.isDrawerOpen(liaison.tiroir),
                    )
                }

                override fun onDrawerOpened(vueTiroir: View) {
                    majRetourSysteme(tiroirOuvert = true)
                }

                override fun onDrawerClosed(vueTiroir: View) {
                    majRetourSysteme(tiroirOuvert = false)
                }
            },
        )

        if (resources.configuration.smallestScreenWidthDp >= SEUIL_GRAND_ECRAN) {
            // Grand écran : tiroir permanent façon IDE de bureau (ADR 0026) —
            // verrouillé ouvert, plus de geste de bord ni de bouton ☰.
            liaison.racineEditeur.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            liaison.toolbarEditeur.navigationIcon = null
            majRetourSysteme(tiroirOuvert = true, tiroirBloque = true)
        }
    }

    /**
     * Tiroir à fragments (étape 31, ADR 0052) : les quatre destinations
     * (Fichiers, Recherche, Git, Terminal) sont ajoutées UNE fois au
     * `FragmentContainerView` puis montrées/cachées — l'état de
     * défilement de l'arbre et les plis survivent aux changements de
     * destination. Chaque fragment porte son propre entête (§ 4) : plus
     * d'entête commun dans l'activité. Le fragment Terminal vient de la
     * fabrique Hilt (v0.32.2, ADR 0053) : sa classe vit dans
     * `feature:terminal` (rendu réel des sessions, split view).
     */
    private fun brancherFragmentsTiroir() {
        val gestionnaire = supportFragmentManager
        if (gestionnaire.findFragmentById(R.id.conteneur_fragments_tiroir) == null) {
            val explorateur = ExplorateurFragment()
            val recherche = RechercheFragment()
            val git = GitFragment()
            val terminal = fabriqueTerminalTiroir.creer()
            gestionnaire
                .beginTransaction()
                .add(R.id.conteneur_fragments_tiroir, explorateur, TAG_EXPLORATEUR)
                .add(R.id.conteneur_fragments_tiroir, recherche, TAG_RECHERCHE)
                .hide(recherche)
                .add(R.id.conteneur_fragments_tiroir, git, TAG_GIT)
                .hide(git)
                .add(R.id.conteneur_fragments_tiroir, terminal, TAG_TERMINAL)
                .hide(terminal)
                .commit()
        }
        construireRail()
        selectionnerDestination(destinationCourante)
    }

    // ------------------------------------------------------------------
    // Contrôleur du Terminal du tiroir (v0.32.2, ADR 0053) : les
    // commandes que le fragment Terminal (feature:terminal) adresse à
    // son hôte — elles réclament l'état de l'espace de travail.
    // ------------------------------------------------------------------

    /** Plein écran : répertoire suggéré = dossier réel du projet (FUSE). */
    override fun ouvrirEcranTerminal() {
        viewModel.onAction(ActionEditor.OuvrirTerminal)
    }

    /** Crée une session dans le dossier du projet, SANS navigation :
     * elle apparaît dans le tiroir (liste ou split), l'utilisateur
     * choisit ensuite de l'agrandir dans le tiroir ou de l'ouvrir en
     * plein écran. Bootstrap absent : l'installation s'ouvre. */
    override fun creerSessionProjet() {
        viewModel.onAction(ActionEditor.CreerSessionTerminal)
    }

    /** Ouvre l'écran d'installation du bootstrap natif. */
    override fun ouvrirInstallationBootstrap() {
        viewModel.onAction(ActionEditor.InstallerOutilsTerminal)
    }

    /** Construit le rail de fragments (§ 14) : quatre destinations
     * (icône 20 dp + libellé 10 sp), état actif = encoche + accent. */
    private fun construireRail() {
        val rail = liaison.railFragments
        if (rail.isNotEmpty()) return
        val destinations =
            listOf(
                Triple(
                    R.id.destination_explorateur,
                    jo.codeide.core.ui.R.drawable.ic_explorateur,
                    R.string.editor_nav_explorateur,
                ),
                Triple(
                    R.id.destination_recherche,
                    jo.codeide.core.ui.R.drawable.ic_recherche,
                    R.string.editor_nav_recherche,
                ),
                Triple(R.id.destination_git, jo.codeide.core.ui.R.drawable.ic_git, R.string.editor_nav_git),
                Triple(
                    R.id.destination_terminal,
                    jo.codeide.core.ui.R.drawable.ic_terminal,
                    R.string.editor_nav_terminal,
                ),
            )
        val dp = resources.displayMetrics.density
        destinations.forEach { (identifiant, icone, libelle) ->
            val item =
                layoutInflater.inflate(R.layout.item_rail_fragment, rail, false) as android.widget.LinearLayout
            item.id = identifiant
            item.minimumHeight = (HAUTEUR_RAIL_DP * dp).toInt()
            item.setOnClickListener { selectionnerDestination(identifiant) }
            item.findViewById<android.widget.ImageView>(R.id.icone_rail).apply {
                setImageResource(icone)
                tag = identifiant
            }
            item.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.libelle_rail).apply {
                setText(libelle)
                tag = identifiant
            }
            item.findViewById<View>(R.id.encoche_rail).tag = identifiant
            rail.addView(item)
        }
    }

    /** Montre le fragment de [destination] et marque le rail (§ 14). */
    private fun selectionnerDestination(destination: Int) {
        destinationCourante = destination
        val gestionnaire = supportFragmentManager
        val cible =
            gestionnaire.findFragmentByTag(
                when (destination) {
                    R.id.destination_recherche -> TAG_RECHERCHE
                    R.id.destination_git -> TAG_GIT
                    R.id.destination_terminal -> TAG_TERMINAL
                    else -> TAG_EXPLORATEUR
                },
            ) ?: return
        val transaction = gestionnaire.beginTransaction()
        // Ciblé PAR TAG (v0.32.4) : les fragments du PANNEAU inférieur
        // vivent dans le même manager — un forEach global les cacherait
        // à chaque changement de destination du tiroir.
        listOf(TAG_EXPLORATEUR, TAG_RECHERCHE, TAG_GIT, TAG_TERMINAL)
            .mapNotNull { tag -> gestionnaire.findFragmentByTag(tag) }
            .forEach { fragment ->
                if (fragment === cible) transaction.show(fragment) else transaction.hide(fragment)
            }
        transaction.commit()

        // État actif du rail : encoche + teinte accent de l'icône et du
        // libellé (§ 14).
        val accent =
            androidx.core.content.ContextCompat
                .getColor(this, R.color.explorateur_accent)
        val inactif =
            androidx.core.content.ContextCompat
                .getColor(this, R.color.explorateur_texte_3)
        liaison.railFragments.children.forEach { item ->
            val actif = item.id == destination
            item.findViewById<View>(R.id.encoche_rail).isVisible = actif
            item.findViewById<android.widget.ImageView>(R.id.icone_rail).setColorFilter(if (actif) accent else inactif)
            item
                .findViewById<com.google.android.material.textview.MaterialTextView>(R.id.libelle_rail)
                .setTextColor(if (actif) accent else inactif)
        }
    }

    /**
     * Poignée ⋮ de redimensionnement du tiroir (§ 13) : glissement
     * horizontal, bornes 45-98 % de l'écran, aimants 55/69/85/98 %
     * (tolérance ±12 dp, appliqués au relâchement), pastille de taille
     * pendant le glissement puis fondu 380 ms après le relâchement.
     * La largeur est mémorisée par instance sauvegardée (§ 19).
     *
     * Animation « pendant » (v0.32.2, retour d'appareil réel — la
     * maquette `.poignee.pendant` transposée) : l'écouteur tactile
     * consommant TOUT, l'état pressé du sélecteur ne s'active jamais de
     * lui-même — il est posé et retiré à la main. Pendant le glissement :
     * fond actif + bordure accent-fort (sélecteur `state_pressed`),
     * points ⋮ accent, et **grossissement 1.08** animé sur 150 ms — le
     * pivot au centre de la poignée, à cheval sur le rebord du tiroir
     * (moitié dedans, moitié dehors, § 13). Au relâchement, retour
     * symétrique.
     */
    private fun brancherPoignee() {
        val dp = resources.displayMetrics.density
        appliquerLargeurTiroir(initialiserSiNecessaire = true)
        // Exemption ClickableViewAccessibility : performClick() est bien
        // appelé au ACTION_UP (le glissement n'est pas un clic ordinaire).
        @Suppress("ClickableViewAccessibility")
        liaison.poigneeTiroir.setOnTouchListener(
            object : View.OnTouchListener {
                private var abscisseDepart = 0f
                private var largeurDepart = 0

                override fun onTouch(
                    vue: View,
                    evenement: MotionEvent,
                ): Boolean {
                    when (evenement.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            abscisseDepart = evenement.rawX
                            largeurDepart = largeurTiroirPx
                            liaison.racineEditeur.requestDisallowInterceptTouchEvent(true)
                            animerPoigneePendant(vue as ImageView, pendant = true)
                            liaison.pastilleTailleTiroir.isVisible = true
                            liaison.pastilleTailleTiroir.alpha = 1f
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val cible = largeurDepart + (evenement.rawX - abscisseDepart).toInt()
                            appliquerLargeurTiroir(cible, animer = false)
                            montrerTailleEnPourcent()
                            return true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            liaison.racineEditeur.requestDisallowInterceptTouchEvent(false)
                            vue.performClick()
                            animerPoigneePendant(vue as ImageView, pendant = false)
                            viserAimants(dp)
                            liaison.pastilleTailleTiroir
                                .animate()
                                .alpha(0f)
                                .setStartDelay(DELAI_PASTILLE_MS)
                                .setDuration(DUREE_FONDU_PASTILLE_MS)
                                .withEndAction { liaison.pastilleTailleTiroir.isVisible = false }
                            return true
                        }
                    }
                    return false
                }
            },
        )
    }

    /** Pose (ou retire) l'état « pendant le glissement » de la poignée :
     * sélecteur pressé (fond actif + bordure accent-fort), points ⋮
     * accent, grossissement 1.08 animé (150 ms) — retour symétrique au
     * relâchement (maquette : `transition ... .15s`, `scale(1.08)`). */
    private fun animerPoigneePendant(
        poignee: ImageView,
        pendant: Boolean,
    ) {
        // L'état pressé active la variante du sélecteur de fond
        // (fond #2C3644 nuit / #C9D6E4 jour + bordure accent-fort).
        poignee.isPressed = pendant
        // Points ⋮ : la teinte de l'ImageView teinte les trois disques
        // (seuls éléments du src) — accent pendant, couleur propre sinon.
        poignee.imageTintList =
            if (pendant) {
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.explorateur_accent))
            } else {
                null
            }
        poignee
            .animate()
            .scaleX(if (pendant) ECHELLE_POIGNEE_PENDANTE else 1f)
            .scaleY(if (pendant) ECHELLE_POIGNEE_PENDANTE else 1f)
            .setDuration(DUREE_ANIMATION_POIGNEE_MS)
            .start()
    }

    /** Largeur visible du tiroir : fraction de l'écran bornée 45-98 %
     *  (§ 13) ; le débord de la poignée (13 dp) s'ajoute à la vue. */
    private fun appliquerLargeurTiroir(
        largeurVisible: Int? = null,
        animer: Boolean = true,
        initialiserSiNecessaire: Boolean = false,
    ) {
        val ecran = resources.displayMetrics.widthPixels
        val debord = (DEBORD_POIGNEE_DP * resources.displayMetrics.density).toInt()
        if (largeurTiroirPx == 0) {
            val fraction = FRACTION_TIROIR_DEFAUT
            largeurTiroirPx = (fraction * ecran).toInt()
        }
        if (largeurVisible != null) {
            largeurTiroirPx =
                largeurVisible.coerceIn((FRACTION_MIN * ecran).toInt(), (FRACTION_MAX * ecran).toInt())
        }
        if (initialiserSiNecessaire && largeurVisible == null && largeurTiroirPx != 0) {
            // première pose : largeur par défaut déjà calculée
        }
        val parametres = liaison.tiroir.layoutParams
        val cible = largeurTiroirPx + debord
        if (animer && parametres.width != cible) {
            val depart = parametres.width
            android.animation.ValueAnimator
                .ofInt(depart, cible)
                .apply {
                    duration = DUREE_LARGEUR_MS
                    addUpdateListener { animateur ->
                        parametres.width = animateur.animatedValue as Int
                        liaison.tiroir.layoutParams = parametres
                    }
                }.start()
        } else {
            parametres.width = cible
            liaison.tiroir.layoutParams = parametres
        }
    }

    /** Aimants de largeur (§ 13 : 55/69/85/98 %, tolérance ±12 dp). */
    private fun viserAimants(dp: Float) {
        val ecran = resources.displayMetrics.widthPixels
        val cible =
            AIMANTS_TIROIR
                .map { it to (it * ecran).toInt() }
                .filter { abs(it.second - largeurTiroirPx) <= TOLERANCE_AIMANT_DP * dp }
                .minByOrNull { abs(it.second - largeurTiroirPx) }
                ?.second ?: largeurTiroirPx
        appliquerLargeurTiroir(cible, animer = true)
        montrerTailleEnPourcent()
    }

    /** Pastille de taille : « NN % » (§ 13). */
    private fun montrerTailleEnPourcent() {
        val ecran = resources.displayMetrics.widthPixels
        liaison.pastilleTailleTiroir.text = getString(R.string.poignee_taille, (100f * largeurTiroirPx / ecran).toInt())
    }

    /** Onglets de fichiers : sélection, fermeture, menu contextuel (étape 15). */
    private fun brancherOnglets() {
        liaison.toolbarEditeur.inflateMenu(R.menu.menu_editeur)
        liaison.toolbarEditeur.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_enregistrer -> {
                    viewModel.onAction(ActionEditor.Enregistrer)
                    true
                }

                R.id.action_synchroniser -> {
                    viewModel.onAction(ActionEditor.Synchroniser)
                    true
                }

                R.id.action_executer -> {
                    viewModel.onAction(ActionEditor.OuvrirSelecteurTaches)
                    true
                }

                R.id.action_fermer_projet -> {
                    viewModel.onAction(ActionEditor.Quitter)
                    true
                }

                else -> {
                    false
                }
            }
        }

        liaison.ongletsFichiers.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (!selectionProgrammatique) {
                        viewModel.onAction(ActionEditor.SelectionnerOnglet(tab.position))
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
    }

    /** L'éditeur : le fil d'Ariane suit le caret (ADR 0054/0055) — re-calcul
     *  débounce 200 ms après chaque déplacement (même constante que la
     *  BreadcrumbBar de la bibliothèque). */
    private fun brancherFilAriane() {
        liaison.vueEditeur.addOnSelectionChangedListener { _, _, _ ->
            travailFil?.cancel()
            travailFil =
                lifecycleScope.launch {
                    delay(DEBOUNCE_FIL_ARIANE_MS)
                    rafraichirFilAriane()
                }
        }
    }

    /** Recalcule le fil d'Ariane de l'onglet actif (caret courant). */
    private fun rafraichirFilAriane() {
        val etat = viewModel.etat.value
        val onglet = etat.onglets.getOrNull(etat.indexOngletActif) ?: return
        majFilAriane(onglet)
    }

    /** Compose et pose les segments (v0.32.4) : dossier / fichier /
     *  symboles englobants — rendus par la BreadcrumbBar de la
     *  bibliothèque via setSegments() (API « usage manuel » : bind()
     *  écraserait les segments via son propre SymbolProvider, qui ne
     *  connaît ni le chemin relatif ni le scanner maison). Les très
     *  gros documents renoncent aux symboles (scan O(n) trop coûteux
     *  à chaque arrêt du caret). */
    private fun majFilAriane(onglet: EditorTabState) {
        val session = viewModel.sessionDe(onglet.uri) ?: return
        val segments = mutableListOf<String>()
        onglet.cheminRelatif.split('/').dropLast(1).forEach { dossier ->
            if (dossier.isNotBlank()) segments += dossier
        }
        segments += onglet.nom
        if (!session.document.isLarge) {
            SymbolesEnglobants.englobants(session.text, session.selection.start).forEach { symbole ->
                segments += symbole
            }
        }
        liaison.filArianeEditeur.setSegments(segments.toTypedArray())
    }

    /** État vide (v0.32.3) : deux actions directes — ouvrir le tiroir
     *  des fichiers (le geste cherché) ou le terminal. */
    private fun brancherVueVide() {
        liaison.boutonVideExplorer.setOnClickListener {
            if (liaison.racineEditeur.getDrawerLockMode(liaison.tiroir) !=
                DrawerLayout.LOCK_MODE_LOCKED_OPEN
            ) {
                liaison.racineEditeur.openDrawer(liaison.tiroir)
            }
        }
        liaison.boutonVideTerminal.setOnClickListener {
            viewModel.onAction(ActionEditor.OuvrirTerminal)
        }
    }

    /** Barre de symboles au-dessus du clavier (ADR 0054/0055) : la VRAIE
     *  SymbolBarView de la bibliothèque (cel-ui) — touches épinglées
     *  mappées sur les commandes de session, symboles insérés par
     *  `typeChar` (fermeture automatique des paires conservée). */
    private fun brancherBarreSymboles() {
        liaison.barreSymboles.setOnSymbolTap(
            object : SymbolBarView.OnSymbolTap {
                override fun onSymbol(symbol: String) {
                    sessionActive()?.typeChar(symbol.first())
                }

                override fun onAction(actionId: String) {
                    val session = sessionActive() ?: return
                    when (actionId) {
                        ACTION_TAB -> session.indent()
                        ACTION_COMMENT -> session.toggleLineComment()
                        ACTION_MOVE_UP -> session.moveLineUp()
                        ACTION_MOVE_DOWN -> session.moveLineDown()
                        ACTION_DUPLICATE -> session.duplicateSelection()
                    }
                }
            },
        )
        observerClavier()
    }

    /** Session de l'onglet actif, ou `null` (aucun fichier ouvert). */
    private fun sessionActive(): EditorSession? {
        val etat = viewModel.etat.value
        val onglet = etat.onglets.getOrNull(etat.indexOngletActif) ?: return null
        return viewModel.sessionDe(onglet.uri)
    }

    /** Visibilité de l'IME (v0.32.4) : DEUX détecteurs convergent —
     *  insets natifs (API 30+ ; silencieux sur certains appareils) et
     *  rétrécissement du root (adjustResize, toutes API — le root
     *  rétrécit quand le clavier prend sa place). */
    private fun observerClavier() {
        ViewCompat.setOnApplyWindowInsetsListener(liaison.racineEditeur) { _, insets ->
            clavierParInsets = insets.isVisible(WindowInsetsCompat.Type.ime())
            rafraichirBarreSymboles()
            insets
        }
        liaison.racineEditeur.viewTreeObserver.addOnGlobalLayoutListener {
            val hauteur = liaison.racineEditeur.height
            if (hauteur > hauteurRacineMax) hauteurRacineMax = hauteur
            val seuil =
                (liaison.racineEditeur.resources.displayMetrics.heightPixels * FRACTION_SEUIL_IME).toInt()
            clavierParHauteur = hauteurRacineMax - hauteur > seuil
            rafraichirBarreSymboles()
        }
    }

    /** La barre n'apparaît que si l'IME est ouvert ET qu'un fichier est
     *  édité (v0.32.4) : le peek du panneau s'élargit alors à en-tête +
     *  barre — le panneau replié est posé sur le haut du clavier
     *  (adjustResize), la barre paraît COLLÉE au clavier. Retour
     *  v0.32.3 : avec le peek de repos (48 dp, en-tête seul), la barre
     *  placée sous l'en-tête restait hors écran. */
    private fun rafraichirBarreSymboles() {
        val montrer = clavierVisible && sessionActive() != null
        liaison.barreSymboles.isVisible = montrer
        val peekReposPx = resources.getDimensionPixelSize(R.dimen.editor_panneau_replie)
        comportementPanneau.peekHeight =
            if (montrer) {
                peekReposPx + (HAUTEUR_BARRE_SYMBOLES_DP * resources.displayMetrics.density).toInt()
            } else {
                peekReposPx
            }
        if (montrer && comportementPanneau.state != BottomSheetBehavior.STATE_COLLAPSED) {
            comportementPanneau.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    /** L'éditeur : rien à brancher — la session arrive par l'état (ADR 0028). */
    private fun brancherEditeur() = Unit

    /**
     * Panneau inférieur (étape 16 ; v0.32.4, ADR 0055) : trois états,
     * en-tête (poignée, titre, badge, agrandir, réduire), onglets
     * Console/Problèmes/Journal — le contenu vit dans trois fragments
     * (Journal/Sortie/Problèmes) ajoutés une fois puis montrés/cachés.
     */
    private fun brancherPanneauInferieur() {
        comportementPanneau = BottomSheetBehavior.from(liaison.panneauInferieur)
        comportementPanneau.state = BottomSheetBehavior.STATE_COLLAPSED
        comportementPanneau.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(
                    vue: View,
                    nouvelEtat: Int,
                ) {
                    // Seules les transitions **stabilisées** remontent au
                    // ViewModel (DRAGGING/SETTLING sont transitoires) — le
                    // rejou d'un état déjà courant est sans effet (UDF).
                    when (nouvelEtat) {
                        BottomSheetBehavior.STATE_COLLAPSED -> {
                            viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.REPLIE))
                        }

                        BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                            viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.MI_HAUTEUR))
                        }

                        BottomSheetBehavior.STATE_EXPANDED -> {
                            viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.ETENDU))
                        }

                        else -> {
                            Unit
                        }
                    }
                }

                override fun onSlide(
                    vue: View,
                    glissement: Float,
                ) = Unit
            },
        )

        // En-tête : appui = replié <-> mi-hauteur (prompt compagnon 5.5).
        liaison.entetePanneau.setOnClickListener {
            val cible =
                if (comportementPanneau.state == BottomSheetBehavior.STATE_COLLAPSED) {
                    EtatPanneau.MI_HAUTEUR
                } else {
                    EtatPanneau.REPLIE
                }
            viewModel.onAction(ActionEditor.ChangerEtatPanneau(cible))
        }

        // Agrandir : replié -> mi-hauteur -> étendu, puis redescend.
        liaison.boutonAgrandirPanneau.setOnClickListener {
            val cible =
                when (comportementPanneau.state) {
                    BottomSheetBehavior.STATE_COLLAPSED -> EtatPanneau.MI_HAUTEUR
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> EtatPanneau.ETENDU
                    else -> EtatPanneau.MI_HAUTEUR
                }
            viewModel.onAction(ActionEditor.ChangerEtatPanneau(cible))
        }

        liaison.boutonFermerPanneau.setOnClickListener {
            viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.REPLIE))
        }

        // Fragments du contenu (v0.32.4, ADR 0055) : ajoutés UNE fois
        // au conteneur — la réconciliation d'onglet les montre/cache (le
        // Journal et la Sortie collectent leur état eux-mêmes, l'état de
        // défilement survit aux changements d'onglet).
        val gestionnaire = supportFragmentManager
        if (gestionnaire.findFragmentById(R.id.conteneur_fragments_panneau) == null) {
            val console = PanneauConsoleFragment()
            val problemes = PanneauProblemesFragment()
            val journal = PanneauJournalFragment()
            gestionnaire
                .beginTransaction()
                .add(R.id.conteneur_fragments_panneau, console, TAG_PANNEAU_CONSOLE)
                .add(R.id.conteneur_fragments_panneau, problemes, TAG_PANNEAU_PROBLEMES)
                .hide(problemes)
                .add(R.id.conteneur_fragments_panneau, journal, TAG_PANNEAU_JOURNAL)
                .hide(journal)
                .commit()
        }

        // Onglets du panneau : l'ordre du layout fixe la correspondance.
        liaison.ongletsPanneau.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (!selectionProgrammatiquePanneau) {
                        OngletPanneau.entries.getOrNull(tab.position)?.let {
                            viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(it))
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
    }

    /** Rend l'état : titre (type en sous-titre), onglets, éditeur, panneau,
     *  snackbar de l'explorateur. */
    private fun rendre(etat: EtatEditor) {
        val projet = etat.projet
        liaison.progression.isVisible = etat.chargement

        liaison.zoneVide.isVisible = !etat.chargement && projet != null && etat.onglets.isEmpty()
        liaison.zoneIntrouvable.isVisible = !etat.chargement && projet == null
        if (projet != null) {
            liaison.toolbarEditeur.title = projet.name
        }
        rendreTypeProjet(etat)
        rendreOnglets(etat)
        rendreEditeur(etat)
        rendrePanneau(etat)
        rendreSnackbarArbre(etat.notification)
        majRetourSysteme(ongletsSales = etat.onglets.any { it.isDirty })
    }

    /**
     * Type de projet (étape 18) en sous-titre de la toolbar : modèle
     * reconnu depuis `.codeide/project.json`, nom i18n + version ; un
     * dossier importé sans métadonnées est dit tel, un projet créé dont
     * le fichier a disparu affiche « type non reconnu » ; rien pendant la
     * vérification d'accès ou en cas de panne. (L'entête v1 du tiroir
     * disparaît avec l'architecture à fragments, étape 31.)
     */
    private fun rendreTypeProjet(etat: EtatEditor) {
        val type = etat.typeProjet
        val acces = etat.acces
        val enVerification = etat.verificationAcces || acces == null
        val texte: String? =
            when {
                type != null && type.versionModele != null -> {
                    getString(R.string.editor_type_projet_modele_version, type.nomModele, type.versionModele)
                }

                type != null -> {
                    getString(R.string.editor_type_projet_modele, type.nomModele)
                }

                etat.projet == null || enVerification || acces != ProjectAccessState.Available -> {
                    null
                }

                etat.projet?.templateId == TemplateId.IMPORTED -> {
                    getString(R.string.editor_type_projet_importe)
                }

                else -> {
                    getString(R.string.editor_type_projet_inconnu)
                }
            }
        liaison.toolbarEditeur.subtitle = texte
    }

    /**
     * Snackbar maison de l'explorateur (étape 31, § 15) : centré au-dessus
     * du rail du tiroir, message + chemin en seconde ligne, action
     * « Annuler » pour une suppression en attente, masquage automatique
     * après 4 600 ms — l'expiration remonte au ViewModel
     * ([ActionEditor.MasquerNotification]).
     */
    private fun rendreSnackbarArbre(notification: NotificationArbre?) {
        if (notification == null) {
            masquerSnackbar()
            return
        }
        if (liaison.zoneSnackbarTiroir.isEmpty()) {
            layoutInflater.inflate(R.layout.vue_snackbar_arbre, liaison.zoneSnackbarTiroir)
        }
        val vue = liaison.zoneSnackbarTiroir.getChildAt(0)
        vue
            .findViewById<com.google.android.material.textview.MaterialTextView>(R.id.texte_snackbar_arbre)
            .text = texteNotification(notification)
        val vueChemin =
            vue.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.chemin_snackbar_arbre)
        vueChemin.isVisible = notification.chemin != null
        notification.chemin?.let { vueChemin.text = it }
        vue
            .findViewById<com.google.android.material.textview.MaterialTextView>(R.id.action_snackbar_arbre)
            .apply {
                isVisible = notification.annulable
                setOnClickListener {
                    viewModel.onAction(ActionEditor.AnnulerSuppression)
                }
            }
        vue.alpha = 0f
        vue.translationY = 18 * resources.displayMetrics.density
        vue.isVisible = true
        vue
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()

        // Masquage automatique (4 600 ms, § 15) — remplacé à chaque
        // nouvelle notification.
        travailSnackbar?.cancel()
        travailSnackbar =
            lifecycleScope.launch {
                delay(4600)
                viewModel.onAction(ActionEditor.MasquerNotification)
            }
    }

    /** Masque le snackbar (plus de notification). */
    private fun masquerSnackbar() {
        travailSnackbar?.cancel()
        travailSnackbar = null
        if (liaison.zoneSnackbarTiroir.isNotEmpty()) {
            liaison.zoneSnackbarTiroir.removeAllViews()
        }
    }

    /** Message localisé d'une notification de l'explorateur (§ 15). */
    private fun texteNotification(notification: NotificationArbre): String =
        when (notification.type) {
            TypeNotificationArbre.ASTUCE -> {
                getString(R.string.notif_astuce)
            }

            TypeNotificationArbre.BASCULE_PRIVE -> {
                getString(R.string.notif_bascule_prive)
            }

            TypeNotificationArbre.BASCULE_PROJET -> {
                getString(R.string.notif_bascule_projet)
            }

            TypeNotificationArbre.CREE -> {
                getString(R.string.notif_cree, notification.nom)
            }

            TypeNotificationArbre.RENOMME -> {
                getString(R.string.notif_renomme, notification.nom, notification.nomSecondaire)
            }

            TypeNotificationArbre.SUPPRIME -> {
                getString(R.string.notif_supprime, notification.nom)
            }

            TypeNotificationArbre.COPIE -> {
                getString(R.string.notif_copie)
            }

            TypeNotificationArbre.COUPE -> {
                getString(R.string.notif_coupe)
            }

            TypeNotificationArbre.VIDE -> {
                getString(R.string.notif_vide)
            }

            TypeNotificationArbre.COLLE -> {
                getString(R.string.notif_colle, notification.nom, notification.nomSecondaire)
            }

            TypeNotificationArbre.DEPLACE -> {
                getString(R.string.notif_deplace, notification.nom, notification.nomSecondaire)
            }

            TypeNotificationArbre.COLLE_IMPOSSIBLE -> {
                getString(R.string.notif_colle_impossible)
            }

            TypeNotificationArbre.DEJA_PRESENT -> {
                getString(R.string.notif_deja_present)
            }

            TypeNotificationArbre.DESTINATION_INTROUVABLE -> {
                getString(R.string.notif_destination_introuvable)
            }

            TypeNotificationArbre.DEJA_A_CET_ENDROIT -> {
                getString(R.string.notif_deja_cet_endroit)
            }

            TypeNotificationArbre.DEPLACEMENT_DANS_SOURCE -> {
                getString(R.string.notif_deplacement_dans_source)
            }

            TypeNotificationArbre.NOM_INVALIDE -> {
                getString(R.string.notif_nom_invalide)
            }
        }

    /** Onglets : réconciliation de la barre avec l'état (ajouts, retraits). */
    private fun rendreOnglets(etat: EtatEditor) {
        val barre = liaison.ongletsFichiers
        val onglets = etat.onglets
        barre.isVisible = onglets.isNotEmpty()

        val reconnaissance =
            onglets.joinToString("|") { "${it.uri};${it.isDirty};${it.sauvegardeEnCours}" }
        if (barre.tag != reconnaissance) {
            barre.tag = reconnaissance
            selectionProgrammatique = true
            barre.removeAllTabs()
            onglets.forEach { onglet -> barre.addTab(creerOnglet(onglet, onglets)) }
            val cible = etat.indexOngletActif
            if (cible in 0 until barre.tabCount) barre.selectTab(barre.getTabAt(cible))
            selectionProgrammatique = false
        } else if (etat.indexOngletActif in 0 until barre.tabCount &&
            barre.selectedTabPosition != etat.indexOngletActif
        ) {
            selectionProgrammatique = true
            barre.selectTab(barre.getTabAt(etat.indexOngletActif))
            selectionProgrammatique = false
        }

        // Sauvegarde en vol : l'action d'enregistrement reste muette.
        liaison.toolbarEditeur.menu
            .findItem(R.id.action_enregistrer)
            .setEnabled(etat.onglets.isNotEmpty())
    }

    /** Crée l'onglet graphique (vue personnalisée) pour [onglet]. */
    private fun creerOnglet(
        onglet: EditorTabState,
        tous: List<EditorTabState>,
    ): TabLayout.Tab {
        val vue = VueOngletFichierBinding.inflate(layoutInflater, liaison.ongletsFichiers, false)
        vue.iconeOnglet.setImageResource(IconesFichiers.pourNom(onglet.nom))
        vue.nomOnglet.text = onglet.nom
        majVueOnglet(vue, onglet)
        val ongletBarre = liaison.ongletsFichiers.newTab()
        // Leçon ADR 0051 (terminal v0.31.7) : la vue racine porte un
        // écouteur d'appui long — une telle vue CONSOMME aussi les taps
        // simples sans agir, le TabLayout ne voit jamais le geste et
        // l'onglet ne change pas. La racine AGIT désormais sur son propre
        // tap : elle sélectionne l'onglet dans la barre.
        vue.root.setOnClickListener {
            liaison.ongletsFichiers.selectTab(ongletBarre)
        }
        vue.boutonFermerOnglet.setOnClickListener {
            viewModel.onAction(ActionEditor.FermerOnglet(onglet.uri))
        }
        vue.root.setOnLongClickListener { ancre ->
            menuContextuelOnglet(ancre, onglet, tous)
            true
        }
        return ongletBarre.apply {
            customView = vue.root
            view.tag = vue
            // Accessibilité (étape 17) : l'onglet annoncé par son nom et
            // son état de modification, pas par ses vues internes.
            view.contentDescription =
                if (onglet.isDirty) {
                    getString(R.string.editor_onglet_sale_cd, onglet.nom)
                } else {
                    onglet.nom
                }
        }
    }

    /** Point de modification vs fermeture, selon l'état de l'onglet. */
    private fun majVueOnglet(
        vue: VueOngletFichierBinding,
        onglet: EditorTabState,
    ) {
        vue.pointSalissure.isVisible = onglet.isDirty
        vue.boutonFermerOnglet.isVisible = !onglet.isDirty
    }

    /** Menu contextuel d'un onglet (section 5.4). */
    private fun menuContextuelOnglet(
        ancre: View,
        onglet: EditorTabState,
        tous: List<EditorTabState>,
    ) {
        val menu = PopupMenu(this, ancre)
        menu.menu.add(android.view.Menu.NONE, ID_FERMER, android.view.Menu.NONE, R.string.editor_menu_fermer)
        menu.menu.add(
            android.view.Menu.NONE,
            ID_FERMER_AUTRES,
            android.view.Menu.NONE,
            R.string.editor_menu_fermer_autres,
        )
        menu.menu.add(android.view.Menu.NONE, ID_FERMER_TOUT, android.view.Menu.NONE, R.string.editor_menu_fermer_tout)
        menu.menu.add(
            android.view.Menu.NONE,
            ID_DEPLACER_GAUCHE,
            android.view.Menu.NONE,
            R.string.editor_menu_deplacer_gauche,
        )
        menu.menu.add(
            android.view.Menu.NONE,
            ID_DEPLACER_DROITE,
            android.view.Menu.NONE,
            R.string.editor_menu_deplacer_droite,
        )
        menu.menu.add(
            android.view.Menu.NONE,
            ID_COPIER_CHEMIN,
            android.view.Menu.NONE,
            R.string.editor_menu_copier_chemin,
        )
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_FERMER -> viewModel.onAction(ActionEditor.FermerOnglet(onglet.uri))
                ID_FERMER_AUTRES -> viewModel.onAction(ActionEditor.FermerAutresOnglets(onglet.uri))
                ID_FERMER_TOUT -> viewModel.onAction(ActionEditor.FermerTousOnglets)
                ID_DEPLACER_GAUCHE -> viewModel.onAction(ActionEditor.DeplacerOnglet(onglet.uri, -1))
                ID_DEPLACER_DROITE -> viewModel.onAction(ActionEditor.DeplacerOnglet(onglet.uri, +1))
                ID_COPIER_CHEMIN -> viewModel.copierChemin(onglet.uri)
            }
            true
        }
        // Le déplacement n'a de sens qu'avec un voisin de ce côté.
        val position = tous.indexOfFirst { it.uri == onglet.uri }
        menu.menu.findItem(ID_DEPLACER_GAUCHE).setEnabled(position > 0)
        menu.menu.findItem(ID_DEPLACER_DROITE).setEnabled(position < tous.lastIndex)
        menu.show()
    }

    /** L'éditeur : rebranche la vue sur la session de l'onglet actif,
     *  rafraîchit le fil d'Ariane (BreadcrumbBar de la bibliothèque) et
     *  la barre de symboles (v0.32.4). */
    private fun rendreEditeur(etat: EtatEditor) {
        val onglet = etat.onglets.getOrNull(etat.indexOngletActif)
        liaison.vueEditeur.isVisible = onglet != null
        liaison.filArianeEditeur.isVisible = onglet != null
        rafraichirBarreSymboles()
        if (onglet == null) {
            liaison.filArianeEditeur.setSegments(emptyArray())
            return
        }

        val session = viewModel.sessionDe(onglet.uri)
        if (session != null && liaison.vueEditeur.getSession() !== session) {
            liaison.vueEditeur.setSession(session)
            liaison.vueEditeur.setFileName(onglet.nom)
        }
        liaison.vueEditeur.setTheme(themeActuel())
        majFilAriane(onglet)
    }

    /** Thème cel correspondant au mode clair/sombre de l'application. */
    private fun themeActuel(): EditorTheme {
        val nuit =
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (nuit) {
            if (themeSombre == null) themeSombre = EditorTheme.dark()
            return themeSombre!!
        }
        if (themeClair == null) themeClair = EditorTheme.light()
        return themeClair!!
    }

    /**
     * Panneau inférieur (étape 16 ; v0.32.4, ADR 0055) : état d'ouverture
     * appliqué au comportement, onglet actif réconcilié (barre ET
     * fragment montré/caché), badge et titre de l'en-tête — le rendu du
     * contenu vit dans les fragments (fenêtre, filtres, statuts).
     */
    private fun rendrePanneau(etat: EtatEditor) {
        // État d'ouverture — l'état du comportement peut diverger pendant
        // un glissement ; seule la valeur stabilisée est appliquée.
        val cibleComportement =
            when (etat.etatPanneau) {
                EtatPanneau.REPLIE -> BottomSheetBehavior.STATE_COLLAPSED
                EtatPanneau.MI_HAUTEUR -> BottomSheetBehavior.STATE_HALF_EXPANDED
                EtatPanneau.ETENDU -> BottomSheetBehavior.STATE_EXPANDED
            }
        if (comportementPanneau.state != cibleComportement) {
            comportementPanneau.state = cibleComportement
        }
        panneauEtendu = etat.etatPanneau == EtatPanneau.ETENDU
        majRetourSysteme()

        // Onglet actif : réconciliation silencieuse de la barre.
        val indexOnglet = OngletPanneau.entries.indexOf(etat.ongletPanneau)
        if (liaison.ongletsPanneau.selectedTabPosition != indexOnglet) {
            val onglet = liaison.ongletsPanneau.getTabAt(indexOnglet)
            if (onglet != null) {
                selectionProgrammatiquePanneau = true
                liaison.ongletsPanneau.selectTab(onglet)
                selectionProgrammatiquePanneau = false
            }
        }

        // Contenu : fragment de l'onglet montré, les autres cachés —
        // ciblé PAR TAG (jamais via les fragments du manager : ceux du
        // tiroir ne doivent pas être cachés, et réciproquement).
        montrerFragmentPanneau(etat.ongletPanneau)

        // Titre de l'en-tête : libellé de l'onglet actif du panneau.
        liaison.titrePanneau.setText(libelleOngletPanneau(etat.ongletPanneau))

        // Badge (compte du journal — le rendu de la fenêtre vit dans
        // PanneauJournalFragment).
        liaison.badgePanneau.isVisible = etat.ongletPanneau == OngletPanneau.JOURNAL && etat.entreesJournal.isNotEmpty()
        if (liaison.badgePanneau.isVisible) {
            // Formatage explicite indépendant de la locale (SetTextI18n).
            liaison.badgePanneau.text = String.format(java.util.Locale.ROOT, "%d", etat.entreesJournal.size)
            liaison.badgePanneau.contentDescription =
                resources.getQuantityString(
                    R.plurals.editor_panneau_badge_cd,
                    etat.entreesJournal.size,
                    etat.entreesJournal.size,
                )
        }
    }

    /** Montre le fragment de l'[onglet] du panneau et cache les deux
     *  autres (ciblés par tag — voir [rendrePanneau]). */
    private fun montrerFragmentPanneau(onglet: OngletPanneau) {
        val cible = supportFragmentManager.findFragmentByTag(tagOngletPanneau(onglet)) ?: return
        if (cible.isVisible) return
        val transaction = supportFragmentManager.beginTransaction()
        fragmentsPanneau().forEach { fragment ->
            if (fragment === cible) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commit()
    }

    /** Fragments du panneau inférieur, par tag. */
    private fun fragmentsPanneau(): List<androidx.fragment.app.Fragment> =
        listOf(TAG_PANNEAU_CONSOLE, TAG_PANNEAU_PROBLEMES, TAG_PANNEAU_JOURNAL)
            .mapNotNull { tag -> supportFragmentManager.findFragmentByTag(tag) }

    /** Tag du fragment de l'onglet du panneau (ordre du layout). */
    private fun tagOngletPanneau(onglet: OngletPanneau): String =
        when (onglet) {
            OngletPanneau.CONSOLE -> TAG_PANNEAU_CONSOLE
            OngletPanneau.PROBLEMES -> TAG_PANNEAU_PROBLEMES
            OngletPanneau.JOURNAL -> TAG_PANNEAU_JOURNAL
        }

    /** Libellé localisé d'un onglet du panneau inférieur. */
    private fun libelleOngletPanneau(onglet: OngletPanneau): Int =
        when (onglet) {
            OngletPanneau.CONSOLE -> R.string.editor_panneau_console
            OngletPanneau.PROBLEMES -> R.string.editor_panneau_problemes
            OngletPanneau.JOURNAL -> R.string.editor_panneau_journal
        }

    /**
     * Sélecteur de tâches (G5, §6) : l'appui lance l'exécution de la tâche
     * choisie — le dialogue se ferme, la sortie arrive dans l'onglet Sortie.
     */
    private fun dialogueSelecteurTaches(taches: List<InfoTache>) {
        val libelles = taches.map { tache -> tache.nomAffiche }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_executer_titre)
            .setItems(libelles) { _, indice ->
                viewModel.onAction(ActionEditor.ExecuterTaches(listOf(taches[indice].chemin)))
            }.setNegativeButton(R.string.editor_fermeture_annuler, null)
            .show()
    }

    /**
     * Saut au diagnostic (G5, §6 ; contrat [ControleurPanneauEditeur] :
     * appelé par PanneauProblemesFragment) : sélectionne l'onglet du
     * fichier (son chemin relatif est le suffixe du fichier diagnostiqué)
     * puis pose le défilement et le curseur à la ligne — après le
     * re-rendu (post) pour que la vue soit rebranchée sur la bonne
     * session.
     */
    override fun sauterAuProbleme(
        fichier: String,
        ligne: Int,
    ) {
        val onglets = viewModel.etat.value.onglets
        val index = onglets.indexOfFirst { onglet -> fichier.endsWith(onglet.cheminRelatif) }
        if (index < 0) return
        viewModel.onAction(ActionEditor.SelectionnerOnglet(index))
        liaison.racineEditeur.post {
            val session = viewModel.sessionSuivieDe(onglets[index].uri)?.session ?: return@post
            val document = session.document
            val ligneBornee = (ligne - 1).coerceIn(0, document.lineCount() - 1)
            liaison.vueEditeur.scrollToLine(ligneBornee)
            val offset = document.lineStart(ligneBornee).coerceAtMost(document.length())
            session.setSelection(Selection.cursor(offset))
        }
    }

    /** Application des effets ponctuels. */
    private fun appliquer(effet: EffetEditor) {
        when (effet) {
            is EffetEditor.OuvrirAvec -> {
                ouvrirAvec(effet.uri)
            }

            EffetEditor.FichierOuvert -> {
                // Retour d'appareil réel v0.32.3 : ouvrir un fichier depuis
                // l'explorateur referme le tiroir — l'utilisateur a fini de
                // parcourir. Le grand écran garde son tiroir ancré (ADR 0026).
                if (liaison.racineEditeur.getDrawerLockMode(liaison.tiroir) !=
                    DrawerLayout.LOCK_MODE_LOCKED_OPEN
                ) {
                    liaison.racineEditeur.closeDrawer(liaison.tiroir)
                }
            }

            is EffetEditor.ConfirmerFermeture -> {
                dialogueFermeture(effet)
            }

            is EffetEditor.CopierChemin -> {
                val pressePapiers = getSystemService(android.content.ClipboardManager::class.java) ?: return
                pressePapiers.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        getString(R.string.editor_menu_copier_chemin),
                        effet.chemin,
                    ),
                )
                Snackbar.make(liaison.racineEditeur, R.string.editor_chemin_copie, Snackbar.LENGTH_SHORT).show()
            }

            EffetEditor.Quitter -> {
                finish()
            }

            EffetEditor.ErreurOuverture -> {
                Snackbar.make(liaison.racineEditeur, R.string.editor_erreur_ouverture, Snackbar.LENGTH_SHORT).show()
            }

            EffetEditor.ErreurEnregistrement -> {
                Snackbar
                    .make(liaison.racineEditeur, R.string.editor_erreur_enregistrement, Snackbar.LENGTH_LONG)
                    .show()
            }

            EffetEditor.OuvrirJournalComplet -> {
                navigateur.openDiagnostics()
            }

            EffetEditor.OuvrirInstallationTerminal -> {
                navigateur.openBootstrapInstall()
            }

            is EffetEditor.OuvrirTerminal -> {
                // T6, section 8 : même écran plein écran que l'accueil, même
                // liste de sessions — le répertoire suggéré vient du dossier
                // réel du projet (pont FUSE du domaine).
                navigateur.openTerminal(effet.cheminTravail)
            }

            is EffetEditor.OuvrirSelecteurTaches -> {
                dialogueSelecteurTaches(effet.taches)
            }

            EffetEditor.ErreurActionFichier -> {
                Snackbar
                    .make(liaison.racineEditeur, R.string.editor_action_fichier_echouee, Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    /** Fichier binaire : le proposer au système (« Ouvrir avec »). */
    private fun ouvrirAvec(uri: String) {
        val intention =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri.toUri(), "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intention)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.editor_ouvrir_avec_aucune, Toast.LENGTH_SHORT).show()
        }
    }

    /** Enregistrer / Ne pas enregistrer / Annuler (agrégé pour plusieurs). */
    private fun dialogueFermeture(effet: EffetEditor.ConfirmerFermeture) {
        val noms =
            effet.uris.mapNotNull { uri ->
                viewModel.etat.value.onglets
                    .firstOrNull { it.uri == uri }
                    ?.nom
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_fermeture_titre)
            .setMessage(
                if (noms.size == 1) {
                    getString(R.string.editor_fermeture_message, noms.first())
                } else {
                    resources.getQuantityString(R.plurals.editor_fermeture_tout_message, noms.size, noms.size)
                },
            ).setPositiveButton(
                if (noms.size ==
                    1
                ) {
                    R.string.editor_fermeture_enregistrer
                } else {
                    R.string.editor_fermeture_enregistrer_tout
                },
            ) { _, _ ->
                viewModel.onAction(ActionEditor.EnregistrerPuisFermer(effet.uris, effet.quitter))
            }.setNegativeButton(R.string.editor_fermeture_ne_pas_enregistrer) { _, _ ->
                viewModel.onAction(ActionEditor.FermerSansEnregistrer(effet.uris, effet.quitter))
            }.setNeutralButton(R.string.editor_fermeture_annuler, null)
            .show()
    }

    /** Réactive le retour système selon panneau étendu, tiroir ouvert et onglets sales. */
    private fun majRetourSysteme(
        tiroirOuvert: Boolean? = null,
        ongletsSales: Boolean? = null,
        tiroirBloque: Boolean = false,
    ) {
        // Sur grand écran le tiroir est permanent : il ne se « ferme » pas.
        if (tiroirOuvert != null) this.tiroirOuvert = tiroirOuvert && !tiroirBloque
        if (ongletsSales != null) this.ongletsSales = ongletsSales
        retourEspace.isEnabled = panneauEtendu || this.tiroirOuvert || this.ongletsSales
    }

    override fun onSaveInstanceState(etat: Bundle) {
        super.onSaveInstanceState(etat)
        etat.putInt(CLE_LARGEUR_TIROIR, largeurTiroirPx)
        etat.putInt(CLE_DESTINATION_TIROIR, destinationCourante)
    }

    private companion object {
        /** Clés de sauvegarde du tiroir (largeur mémorisée par session,
         * destination du rail, § 13 / § 19). */
        const val CLE_LARGEUR_TIROIR = "largeur_tiroir_px"
        const val CLE_DESTINATION_TIROIR = "destination_tiroir"

        /** Plus petit écran considéré « grand » (dp). */
        const val SEUIL_GRAND_ECRAN = 600

        /** Étiquettes des fragments du tiroir (§ 14 — restauration). */
        const val TAG_EXPLORATEUR = "tiroir_explorateur"
        const val TAG_RECHERCHE = "tiroir_recherche"
        const val TAG_GIT = "tiroir_git"
        const val TAG_TERMINAL = "tiroir_terminal"

        /** Bornes de largeur du tiroir (§ 13 : 45 % ↔ 98 %). */
        const val FRACTION_MIN = 0.45f
        const val FRACTION_MAX = 0.98f

        /** Largeur par défaut (§ 2 : 262 sur 414 dans la maquette). */
        const val FRACTION_TIROIR_DEFAUT = 0.63f

        /** Aimants de largeur (§ 13 : 55 / 69 / 85 / 98 %). */
        val AIMANTS_TIROIR = listOf(0.55f, 0.69f, 0.85f, 0.98f)

        /** Hauteur du rail de fragments (§ 14 : 66 dp). */
        const val HAUTEUR_RAIL_DP = 66

        /** Débord de la poignée hors du tiroir (§ 13 : 13 dp). */
        const val DEBORD_POIGNEE_DP = 13

        /** Durée d'animation de la largeur hors glissement (§ 13 : 0,18 s). */
        const val DUREE_LARGEUR_MS = 180L

        /** Tolérance des aimants (§ 13 : ±12 dp). */
        const val TOLERANCE_AIMANT_DP = 12

        /** Fondu de la pastille de taille après relâchement (§ 13 : 380 ms). */
        const val DELAI_PASTILLE_MS = 380L

        /** Durée du fondu de la pastille (§ 13 : 120 ms). */
        const val DUREE_FONDU_PASTILLE_MS = 120L

        /** Grossissement de la poignée pendant le glissement (v0.32.2,
         * maquette `.poignee.pendant` : `scale(1.08)`). */
        const val ECHELLE_POIGNEE_PENDANTE = 1.08f

        /** Durée des transitions de la poignée (maquette : `.15s`). */
        const val DUREE_ANIMATION_POIGNEE_MS = 150L

        /** Débounce du fil d'Ariane (BreadCrumbBar de la bibliothèque : 200 ms). */
        const val DEBOUNCE_FIL_ARIANE_MS = 200L

        /** Tags des fragments du panneau inférieur (v0.32.4, ADR 0055) —
         *  le ciblage par tag est le garde-fou contre les show/hide qui
         *  se traverseraient avec les fragments du tiroir. */
        const val TAG_PANNEAU_CONSOLE = "panneau_console"
        const val TAG_PANNEAU_PROBLEMES = "panneau_problemes"
        const val TAG_PANNEAU_JOURNAL = "panneau_journal"

        /** Hauteur de la barre de symboles (constante interne de la
         *  SymbolBarView de la bibliothèque : 38 dp — le peek de l'IME
         *  en dépend). */
        const val HAUTEUR_BARRE_SYMBOLES_DP = 38

        /** Seuil de détection de l'IME par la hauteur du root :
         *  un clavier occupe largement plus de 15 % de l'écran, une marge
         *  d'insets jamais ça. */
        const val FRACTION_SEUIL_IME = 0.15f

        /** Actions des touches épinglées de la barre de symboles. */
        const val ACTION_TAB = "tab"
        const val ACTION_COMMENT = "comment"
        const val ACTION_MOVE_UP = "move_up"
        const val ACTION_MOVE_DOWN = "move_down"
        const val ACTION_DUPLICATE = "duplicate"

        // Identifiants du menu contextuel d'onglet (pas de ressources
        // menu XML : un PopupMenu programmatique garde les libellés
        // dans les chaînes localisées et l'ordre sous les yeux).
        const val ID_FERMER = 1
        const val ID_FERMER_AUTRES = 2
        const val ID_FERMER_TOUT = 3
        const val ID_DEPLACER_GAUCHE = 4
        const val ID_DEPLACER_DROITE = 5
        const val ID_COPIER_CHEMIN = 6
    }
}
