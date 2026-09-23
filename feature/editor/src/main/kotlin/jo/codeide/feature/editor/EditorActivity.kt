package jo.codeide.feature.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
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
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import jo.codeeditor.view.EditorTheme
import jo.codeeditor.view.EditorView
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.ui.IconesFichiers
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import jo.codeide.feature.editor.databinding.VueOngletFichierBinding

/**
 * Espace de travail d'un projet (étapes 13-15, prompt compagnon section 5) :
 * **trois zones**.
 *
 * - tiroir de navigation gauche — en-tête (nom, chemin, « Fermer le
 *   projet », bouton **Actualiser**), **explorateur de fichiers paresseux**
 *   (étape 14, ADR 0027) et barre de navigation basse — permanent sur grand
 *   écran (ADR 0026) ;
 * - zone centrale — **onglets de fichiers dynamiques** et éditeur (étape
 *   15, ADR 0028) : un `TabLayout` défilant (icône du langage, nom, point
 *   de modification remplaçant la fermeture tant que l'onglet est sale,
 *   menu contextuel : fermer, fermer les autres, fermer tout, déplacer,
 *   copier le chemin) au-dessus d'**un seul `EditorView`** rebranché sur la
 *   session de l'onglet actif — thème clair/sombre suivant l'application ;
 * - panneau inférieur replié à trois onglets vides (étape 16).
 *
 * Sauvegarde automatique (délai d'inactivité, côté ViewModel) et manuelle
 * (action de la toolbar). Fermeture d'un onglet sale — ou sortie avec des
 * onglets sales — demande Enregistrer / Ne pas enregistrer / Annuler,
 * agrégé pour plusieurs fichiers. Un fichier binaire est proposé à
 * « Ouvrir avec » plutôt qu'affiché illisible.
 *
 * Le bouton retour ferme le tiroir s'il est ouvert, sinon quitte — après
 * confirmation si des onglets sont sales.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — l'activité
 * **rend** les trois zones de l'espace de travail (tiroir, onglets,
 * éditeur, panneau) et applique les effets ; l'éclater par zone
 * casserait la cohérence du cycle de vie unique de l'écran.
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class EditorActivity : AppCompatActivity() {
    private val viewModel: EditorViewModel by viewModels()

    private lateinit var liaison: ActivityEditorBinding

    private lateinit var comportementPanneau: BottomSheetBehavior<*>

    /** Adaptateur de l'arborescence paresseuse du tiroir (étape 14). */
    private lateinit var adaptateurExplorateur: ExplorateurAdapter

    /** Thèmes cel mis en cache (clair/sombre, suivant l'application). */
    private var themeClair: EditorTheme? = null
    private var themeSombre: EditorTheme? = null

    /** Sélection programmatique d'onglet : ne pas la renvoyer au ViewModel. */
    private var selectionProgrammatique = false

    /** Le tiroir est-il ouvert (pilote le retour système) ? */
    private var tiroirOuvert = false

    /** Des onglets sont-ils sales (pilote le retour système) ? */
    private var ongletsSales = false

    /** Retour système : ferme le tiroir ouvert, confirme les onglets sales, sinon quitte. */
    private val retourEspace =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (tiroirOuvert) {
                    liaison.racineEditeur.closeDrawer(liaison.tiroir)
                } else {
                    viewModel.onAction(ActionEditor.Quitter)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        liaison = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(liaison.root)

        brancherInsets()
        brancherTiroir()
        brancherExplorateur()
        brancherOnglets()
        brancherEditeur()
        brancherPanneauInferieur()
        onBackPressedDispatcher.addCallback(this, retourEspace)

        viewModel.etat.collectWithLifecycle(this, Lifecycle.State.STARTED) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(this, Lifecycle.State.STARTED) { effet -> appliquer(effet) }
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
        liaison.boutonFermerProjet.setOnClickListener { viewModel.onAction(ActionEditor.Quitter) }

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

    /** Explorateur du tiroir : liste, actualisation, barre basse (étape 14). */
    private fun brancherExplorateur() {
        adaptateurExplorateur =
            ExplorateurAdapter { noeud ->
                if (noeud.estDossier) {
                    viewModel.onAction(ActionEditor.BasculerNoeud(noeud.uri))
                } else {
                    viewModel.onAction(ActionEditor.OuvrirFichier(noeud.uri))
                }
            }
        liaison.listeExplorateur.layoutManager = LinearLayoutManager(this)
        liaison.listeExplorateur.adapter = adaptateurExplorateur

        // Bouton d'actualisation de l'en-tête : revérifie l'accès au projet
        // puis recharge l'arborescence (prompt compagnon 5.3).
        liaison.boutonActualiser.setOnClickListener {
            viewModel.onAction(ActionEditor.Rafraichir)
        }

        // Barre de navigation basse : seule « Explorateur » est active —
        // les destinations désactivées annoncent « Bientôt disponible ».
        liaison.barreNavigationTiroir.setOnItemSelectedListener { item ->
            item.itemId == R.id.destination_explorateur
        }
        liaison.barreNavigationTiroir.selectedItemId = R.id.destination_explorateur
        liaison.barreNavigationTiroir.menu
            .findItem(R.id.destination_recherche)
            .contentDescription =
            getString(R.string.editor_nav_bientot, getString(R.string.editor_nav_recherche))
        liaison.barreNavigationTiroir.menu
            .findItem(R.id.destination_git)
            .contentDescription =
            getString(R.string.editor_nav_bientot, getString(R.string.editor_nav_git))
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

    /** Panneau inférieur : replié, un appui sur l'en-tête le déplie à mi-hauteur. */
    private fun brancherPanneauInferieur() {
        comportementPanneau = BottomSheetBehavior.from(liaison.panneauInferieur)
        comportementPanneau.state = BottomSheetBehavior.STATE_COLLAPSED

        liaison.entetePanneau.setOnClickListener {
            comportementPanneau.state =
                if (comportementPanneau.state == BottomSheetBehavior.STATE_COLLAPSED) {
                    BottomSheetBehavior.STATE_HALF_EXPANDED
                } else {
                    BottomSheetBehavior.STATE_COLLAPSED
                }
        }
        liaison.boutonFermerPanneau.setOnClickListener {
            comportementPanneau.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    /** Rend l'état : titre, tiroir (explorateur/bandeau), onglets, éditeur. */
    private fun rendre(etat: EtatEditor) {
        val projet = etat.projet
        liaison.progression.isVisible = etat.chargement

        liaison.zoneVide.isVisible = !etat.chargement && projet != null && etat.onglets.isEmpty()
        liaison.zoneIntrouvable.isVisible = !etat.chargement && projet == null
        if (projet != null) {
            liaison.toolbarEditeur.title = projet.name
            liaison.nomProjetTiroir.text = projet.name
            liaison.cheminTiroir.text = projet.location.displayPath
            rendreTiroir(etat)
        }
        rendreOnglets(etat)
        rendreEditeur(etat)
        majRetourSysteme(ongletsSales = etat.onglets.any { it.isDirty })
    }

    /** Contenu du tiroir : vérification, arborescence ou bandeau d'accès. */
    private fun rendreTiroir(etat: EtatEditor) {
        adaptateurExplorateur.submitList(etat.noeuds)

        liaison.boutonActualiser.isEnabled = !etat.verificationAcces

        val acces = etat.acces
        val enVerification = etat.verificationAcces || acces == null
        val panne =
            !enVerification &&
                (etat.erreurRacine || acces == ProjectAccessState.PermissionLost || acces == ProjectAccessState.Missing)
        val arbreVisible = !enVerification && !panne && acces == ProjectAccessState.Available

        liaison.progressionTiroir.isVisible = enVerification
        liaison.bandeauAcces.isVisible = panne
        liaison.listeExplorateur.isVisible = arbreVisible
        liaison.texteExplorateurVide.isVisible = arbreVisible && etat.noeuds.isEmpty()

        if (panne) rendreBandeauAcces(etat)
    }

    /** Bandeau d'accès : variantes permission perdue, introuvable, erreur. */
    private fun rendreBandeauAcces(etat: EtatEditor) {
        when {
            etat.erreurRacine || etat.acces == null -> {
                liaison.titreAcces.setText(R.string.editor_acces_erreur_titre)
                liaison.messageAcces.setText(R.string.editor_acces_erreur_message)
                liaison.boutonResoudre.setText(R.string.editor_acces_reessayer)
                liaison.boutonResoudre.setOnClickListener {
                    viewModel.onAction(ActionEditor.Rafraichir)
                }
            }

            etat.acces == ProjectAccessState.PermissionLost -> {
                liaison.titreAcces.setText(R.string.editor_acces_permission_titre)
                liaison.messageAcces.setText(R.string.editor_acces_permission_message)
                liaison.boutonResoudre.setText(R.string.editor_acces_resoudre)
                // La résolution vit à l'accueil (Relocaliser / Retirer) :
                // refermer l'espace de travail y ramène, accueil sous-jacent.
                liaison.boutonResoudre.setOnClickListener { finish() }
            }

            else -> {
                liaison.titreAcces.setText(R.string.editor_acces_introuvable_titre)
                liaison.messageAcces.setText(R.string.editor_acces_introuvable_message)
                liaison.boutonResoudre.setText(R.string.editor_acces_resoudre)
                liaison.boutonResoudre.setOnClickListener { finish() }
            }
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

    /** Réactive le retour système selon tiroir ouvert et onglets sales. */
    private fun majRetourSysteme(
        tiroirOuvert: Boolean? = null,
        ongletsSales: Boolean? = null,
        tiroirBloque: Boolean = false,
    ) {
        // Sur grand écran le tiroir est permanent : il ne se « ferme » pas.
        if (tiroirOuvert != null) this.tiroirOuvert = tiroirOuvert && !tiroirBloque
        if (ongletsSales != null) this.ongletsSales = ongletsSales
        retourEspace.isEnabled = this.tiroirOuvert || this.ongletsSales
    }

    private companion object {
        /** Plus petit écran considéré « grand » (dp). */
        const val SEUIL_GRAND_ECRAN = 600

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
