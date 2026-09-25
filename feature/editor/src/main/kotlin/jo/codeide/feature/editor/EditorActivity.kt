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
import androidx.core.view.children
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import jo.codeeditor.document.Selection
import jo.codeeditor.view.EditorTheme
import jo.codeeditor.view.EditorView
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.IconesFichiers
import jo.codeide.core.ui.applySystemBarsInsets
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
 * - panneau inférieur (étape 16, ADR 0029) : trois états pilotés par
 *   `BottomSheetBehavior` (replié / mi-hauteur / étendu), en-tête à
 *   poignée/titre/badge/actions, onglet **Journal applicatif** compact
 *   fonctionnel (fenêtre mémoire, filtres par niveau, lien vers l'écran
 *   Diagnostic) et onglets **Sortie** et **Problèmes** en stub explicite.
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
class EditorActivity : AppCompatActivity() {
    private val viewModel: EditorViewModel by viewModels()

    /** Navigation inter-features (lien vers l'écran Diagnostic, étape 16). */
    @Inject
    lateinit var navigateur: AppNavigator

    private lateinit var liaison: ActivityEditorBinding

    private lateinit var comportementPanneau: BottomSheetBehavior<*>

    /** Adaptateur du journal applicatif compact du panneau (étape 16). */
    private lateinit var adaptateurJournal: EntreesJournalCompactesAdapter

    /** Console du build (G5, onglet Sortie). */
    private lateinit var adaptateurSortie: SortieAdapter

    /** Diagnostics groupés (G5, onglet Problèmes). */
    private lateinit var adaptateurProblemes: ProblemesAdapter

    /** Taille de la dernière fenêtre de sortie rendue (auto-défilement). */
    private var tailleDerniereFenetreSortie = 0

    /** Thèmes cel mis en cache (clair/sombre, suivant l'application). */
    private var themeClair: EditorTheme? = null
    private var themeSombre: EditorTheme? = null

    /** Sélection programmatique d'onglet : ne pas la renvoyer au ViewModel. */
    private var selectionProgrammatique = false

    /** Idem pour les onglets du panneau inférieur (étape 16). */
    private var selectionProgrammatiquePanneau = false

    /** Mise à jour programmatique des filtres du journal (étape 16). */
    private var majProgrammatiqueFiltres = false

    /** Taille de la dernière fenêtre du journal rendue (suivi direct). */
    private var tailleDerniereFenetreJournal = 0

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
        brancherEditeur()
        brancherPanneauInferieur()
        onBackPressedDispatcher.addCallback(this, retourEspace)

        // Langue des libellés du catalogue (étape 18) — re-émise à chaque
        // re-création de l'activité, donc après chaque changement de langue.
        viewModel.onAction(ActionEditor.PreciserLangue(langueCourante()))

        viewModel.etat.collectWithLifecycle(this, Lifecycle.State.STARTED) { etat -> rendre(etat) }
        viewModel.etatGradle.collectWithLifecycle(this, Lifecycle.State.STARTED) { etat -> rendreTooling(etat) }
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

    /** Applique les insets edge-to-edge : toolbar en haut, tiroir en bas. */
    private fun brancherInsets() {
        liaison.toolbarEditeur.applySystemBarsInsets(top = true, bottom = false)
        liaison.tiroir.applySystemBarsInsets(top = false, bottom = true)
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
     * d'entête commun dans l'activité.
     */
    private fun brancherFragmentsTiroir() {
        val gestionnaire = supportFragmentManager
        if (gestionnaire.findFragmentById(R.id.conteneur_fragments_tiroir) == null) {
            val explorateur = ExplorateurFragment()
            val recherche = RechercheFragment()
            val git = GitFragment()
            val terminal = TerminalTiroirFragment()
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
        gestionnaire.fragments.forEach { fragment ->
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

    /** L'éditeur : rien à brancher — la session arrive par l'état (ADR 0028). */
    private fun brancherEditeur() = Unit

    /**
     * Panneau inférieur (étape 16) : trois états, en-tête (poignée, titre,
     * badge, agrandir, réduire), onglets Console/Problèmes/Journal, filtres
     * du journal compact et lien vers l'écran Diagnostic.
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

        adaptateurJournal = EntreesJournalCompactesAdapter()
        liaison.listeJournal.layoutManager = LinearLayoutManager(this)
        liaison.listeJournal.adapter = adaptateurJournal
        brancherToolingPanneau()

        // Filtres par niveau — même règle que l'écran Diagnostic (étape 12).
        liaison.chipJournalDebug.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.DEBUG))
        }
        liaison.chipJournalInfo.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.INFO))
        }
        liaison.chipJournalWarn.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.WARN))
        }
        liaison.chipJournalError.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))
        }

        // Lien vers le journal complet — l'historique et les exports restent
        // à l'écran Diagnostic (version compacte, prompt compagnon 5.5).
        liaison.boutonJournalComplet.setOnClickListener {
            viewModel.onAction(ActionEditor.OuvrirJournalComplet)
        }
    }

    /**
     * Onglets Sortie et Problèmes (G5, section 6) : console du build
     * (auto-défilement en vol), diagnostics groupés par fichier (saut à
     * la ligne), arrêt du build.
     */
    private fun brancherToolingPanneau() {
        adaptateurSortie = SortieAdapter()
        liaison.listeSortie.layoutManager = LinearLayoutManager(this)
        liaison.listeSortie.adapter = adaptateurSortie
        adaptateurProblemes =
            ProblemesAdapter { fichier, ligne ->
                sauterAuProbleme(fichier, ligne)
            }
        liaison.listeProblemes.layoutManager = LinearLayoutManager(this)
        liaison.listeProblemes.adapter = adaptateurProblemes
        liaison.boutonAnnulerBuild.setOnClickListener {
            viewModel.onAction(ActionEditor.AnnulerBuild)
        }
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
        vue.boutonFermerOnglet.setOnClickListener {
            viewModel.onAction(ActionEditor.FermerOnglet(onglet.uri))
        }
        vue.root.setOnLongClickListener { ancre ->
            menuContextuelOnglet(ancre, onglet, tous)
            true
        }
        return liaison.ongletsFichiers.newTab().apply {
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

    /** L'éditeur : rebranche la vue sur la session de l'onglet actif. */
    private fun rendreEditeur(etat: EtatEditor) {
        val onglet = etat.onglets.getOrNull(etat.indexOngletActif)
        liaison.vueEditeur.isVisible = onglet != null
        if (onglet == null) return

        val session = viewModel.sessionDe(onglet.uri)
        if (session != null && liaison.vueEditeur.getSession() !== session) {
            liaison.vueEditeur.setSession(session)
            liaison.vueEditeur.setFileName(onglet.nom)
        }
        liaison.vueEditeur.setTheme(themeActuel())
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
     * Panneau inférieur (étape 16) : état d'ouverture appliqué au
     * comportement, onglet actif réconcilié, fenêtre du journal rendue
     * (suivi direct par défilement quand elle grandit), filtres, badge
     * et titre de l'en-tête.
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
        liaison.contenuJournal.isVisible = etat.ongletPanneau == OngletPanneau.JOURNAL
        liaison.contenuSortie.isVisible = etat.ongletPanneau == OngletPanneau.CONSOLE
        liaison.contenuProblemes.isVisible = etat.ongletPanneau == OngletPanneau.PROBLEMES

        // Titre de l'en-tête : libellé de l'onglet actif du panneau.
        liaison.titrePanneau.setText(libelleOngletPanneau(etat.ongletPanneau))

        // Journal compact : fenêtre, suivi direct, état vide, badge.
        adaptateurJournal.submitList(etat.entreesJournal)
        if (etat.entreesJournal.size > tailleDerniereFenetreJournal && etat.entreesJournal.isNotEmpty()) {
            liaison.listeJournal.scrollToPosition(etat.entreesJournal.lastIndex)
        }
        tailleDerniereFenetreJournal = etat.entreesJournal.size
        liaison.texteJournalVide.isVisible = etat.entreesJournal.isEmpty()

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

        // Filtres : cochés selon l'état, sans renvoyer l'action (garde).
        majProgrammatiqueFiltres = true
        liaison.chipJournalDebug.isChecked = LogLevel.DEBUG in etat.filtresJournal
        liaison.chipJournalInfo.isChecked = LogLevel.INFO in etat.filtresJournal
        liaison.chipJournalWarn.isChecked = LogLevel.WARN in etat.filtresJournal
        liaison.chipJournalError.isChecked = LogLevel.ERROR in etat.filtresJournal
        majProgrammatiqueFiltres = false
    }

    /** Libellé localisé d'un onglet du panneau inférieur. */
    private fun libelleOngletPanneau(onglet: OngletPanneau): Int =
        when (onglet) {
            OngletPanneau.CONSOLE -> R.string.editor_panneau_console
            OngletPanneau.PROBLEMES -> R.string.editor_panneau_problemes
            OngletPanneau.JOURNAL -> R.string.editor_panneau_journal
        }

    /**
     * Rendu du tooling (G5, §6) : état de synchronisation/build dans
     * l'en-tête de l'onglet Sortie (annulation visible en vol), console
     * avec auto-défilement (le suivi s'arrête quand la liste cesse de
     * grandir — un build fini ne défile plus), diagnostics groupés.
     */
    private fun rendreTooling(etat: EtatGradle) {
        liaison.statutSortie.text = libelleStatutTooling(etat)
        liaison.boutonAnnulerBuild.isVisible = etat.statutBuild == StatutBuild.EN_COURS

        // Console : fenêtre bornée, auto-défilement tant qu'elle grandit.
        adaptateurSortie.submitList(etat.lignes)
        val enVol = etat.statutBuild == StatutBuild.EN_COURS
        if (enVol && etat.lignes.size > tailleDerniereFenetreSortie && etat.lignes.isNotEmpty()) {
            liaison.listeSortie.scrollToPosition(etat.lignes.lastIndex)
        }
        tailleDerniereFenetreSortie = etat.lignes.size
        liaison.texteSortieVide.isVisible = etat.lignes.isEmpty()

        // Problèmes : groupes aplatis (le badge du panneau reste celui du
        // journal, étape 16 — le compte par fichier vit dans les groupes).
        adaptateurProblemes.submitList(etat.groupesProblemes.aplatir())
        liaison.texteProblemesVide.isVisible = etat.problemesTotal == 0
    }

    /** Libellé du statut tooling : synchronisation, puis build, puis repli. */
    private fun libelleStatutTooling(etat: EtatGradle): String =
        when {
            etat.synchronisationEnCours -> {
                getString(R.string.editor_sortie_sync_en_cours)
            }

            etat.synchronisationReussie != null -> {
                getString(R.string.editor_sortie_sync_reussie, dureeLisible(etat.synchronisationReussie.dureeMs))
            }

            etat.messageEchecSync != null -> {
                etat.messageEchecSync
            }

            etat.statutBuild == StatutBuild.EN_COURS -> {
                getString(R.string.editor_sortie_build_en_cours)
            }

            etat.statutBuild == StatutBuild.REUSSI -> {
                getString(R.string.editor_sortie_build_reussi, dureeLisible(etat.dureeBuildMs ?: 0L))
            }

            etat.statutBuild == StatutBuild.ECHOUE -> {
                etat.messageEchecBuild ?: getString(R.string.editor_sortie_build_echoue)
            }

            etat.statutBuild == StatutBuild.ANNULE -> {
                getString(R.string.editor_sortie_build_annule)
            }

            etat.connexion == EtatConnexion.ECHOUEE -> {
                getString(R.string.editor_outil_deconnecte)
            }

            else -> {
                getString(R.string.editor_sortie_vide)
            }
        }

    /** Durée lisible (s, ou ms sous la seconde). */
    private fun dureeLisible(dureeMs: Long): String =
        if (dureeMs >= SEUIL_SECONDE_MS) {
            String.format(java.util.Locale.ROOT, "%.1fs", dureeMs / SECONDE_MS)
        } else {
            String.format(java.util.Locale.ROOT, "%dms", dureeMs)
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
     * Saut au diagnostic (G5, §6) : sélectionne l'onglet du fichier (son
     * chemin relatif est le suffixe du fichier diagnostiqué) puis pose le
     * défilement et le curseur à la ligne — après le re-rendu (post) pour
     * que la vue soit rebranchée sur la bonne session.
     */
    private fun sauterAuProbleme(
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

        /** Seuil de bascule seconde/milliseconde des durées affichées (G5). */
        const val SEUIL_SECONDE_MS = 1_000L

        /** Valeur double du seuil (division de durée). */
        const val SECONDE_MS = 1_000.0

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
