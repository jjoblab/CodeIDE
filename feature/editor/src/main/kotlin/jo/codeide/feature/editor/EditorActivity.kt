package jo.codeide.feature.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
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
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeeditor.view.EditorTheme
import jo.codeeditor.view.EditorView
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.IconesFichiers
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import jo.codeide.feature.editor.databinding.DialogueNomFichierBinding
import jo.codeide.feature.editor.databinding.VueOngletFichierBinding
import javax.inject.Inject

/**
 * Espace de travail d'un projet (étapes 13-16, prompt compagnon section 5) :
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
@Suppress("TooManyFunctions", "LargeClass")
@AndroidEntryPoint
class EditorActivity : AppCompatActivity() {
    private val viewModel: EditorViewModel by viewModels()

    /** Navigation inter-features (lien vers l'écran Diagnostic, étape 16). */
    @Inject
    lateinit var navigateur: AppNavigator

    private lateinit var liaison: ActivityEditorBinding

    private lateinit var comportementPanneau: BottomSheetBehavior<*>

    /** Adaptateur de l'arborescence paresseuse du tiroir (étape 14). */
    private lateinit var adaptateurExplorateur: ExplorateurAdapter

    /** Adaptateur du journal applicatif compact du panneau (étape 16). */
    private lateinit var adaptateurJournal: EntreesJournalCompactesAdapter

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
        liaison = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(liaison.root)

        brancherInsets()
        brancherTiroir()
        brancherExplorateur()
        brancherTerminalTiroir()
        brancherOnglets()
        brancherEditeur()
        brancherPanneauInferieur()
        onBackPressedDispatcher.addCallback(this, retourEspace)

        // Langue des libellés du catalogue (étape 18) — re-émise à chaque
        // re-création de l'activité, donc après chaque changement de langue.
        viewModel.onAction(ActionEditor.PreciserLangue(langueCourante()))

        viewModel.etat.collectWithLifecycle(this, Lifecycle.State.STARTED) { etat -> rendre(etat) }
        viewModel.etatTerminal.collectWithLifecycle(this, Lifecycle.State.STARTED) { etat -> rendreCarteTerminal(etat) }
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

    /** Explorateur du tiroir : liste, actualisation, création à la racine,
     * barre basse (étape 14) et menu contextuel des nœuds (étape 17). */
    private fun brancherExplorateur() {
        adaptateurExplorateur =
            ExplorateurAdapter(
                surClic = { noeud ->
                    if (noeud.estDossier) {
                        viewModel.onAction(ActionEditor.BasculerNoeud(noeud.uri))
                    } else {
                        viewModel.onAction(ActionEditor.OuvrirFichier(noeud.uri))
                    }
                },
                surClicLong = { noeud ->
                    menuContextuelNoeud(noeud)
                    true
                },
            )
        liaison.listeExplorateur.layoutManager = LinearLayoutManager(this)
        liaison.listeExplorateur.adapter = adaptateurExplorateur

        // Bouton d'actualisation de l'en-tête : revérifie l'accès au projet
        // puis recharge l'arborescence (prompt compagnon 5.3).
        liaison.boutonActualiser.setOnClickListener {
            viewModel.onAction(ActionEditor.Rafraichir)
        }

        // Création à la racine (étape 17) : le menu contextuel d'un nœud
        // couvre la création dans un dossier, celui-ci couvre la racine.
        liaison.boutonNouveau.setOnClickListener { ancre ->
            val racine =
                viewModel.etat.value.projet
                    ?.location
                    ?.documentUri ?: return@setOnClickListener
            menuCreationRacine(ancre, racine)
        }

        // Barre de navigation basse : « Explorateur » et « Terminal » (T6)
        // sont actives — les destinations désactivées annoncent « Bientôt
        // disponible ». La carte d'aperçu remplace l'explorateur quand la
        // destination Terminal est choisie (section 8 : carte, pas un
        // terminal embarqué).
        //
        // Plantage 3d8ede67 (v0.25.0 sur appareil) : la destination initiale
        // était affectée APRÈS l'enregistrement de l'écouteur — BottomNavigationView
        // distribue alors l'écouteur de façon SYNCHRONE pendant onCreate,
        // qui appelait rendre() avant l'inflation du menu de la toolbar
        // (brancherOnglets, findItem null) et l'initialisation du
        // comportementPanneau (lateinit). La destination est affectée AVANT
        // l'écouteur : aucune distribution n'est possible au branchement, le
        // premier rendu réel vient de la collecte d'état (STARTED), quand
        // tout est branché. Vues dans ce sens, ces deux ordres sont figés
        // par ActivityEditorLayoutTest.
        liaison.barreNavigationTiroir.selectedItemId = R.id.destination_explorateur
        liaison.barreNavigationTiroir.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.destination_explorateur -> {
                    basculerVueTiroir(terminal = false)
                    true
                }

                R.id.destination_terminal -> {
                    basculerVueTiroir(terminal = true)
                    true
                }

                else -> {
                    false
                }
            }
        }
        liaison.barreNavigationTiroir.menu
            .findItem(R.id.destination_recherche)
            .contentDescription =
            getString(R.string.editor_nav_bientot, getString(R.string.editor_nav_recherche))
        liaison.barreNavigationTiroir.menu
            .findItem(R.id.destination_git)
            .contentDescription =
            getString(R.string.editor_nav_bientot, getString(R.string.editor_nav_git))
    }

    /**
     * Carte d'aperçu du terminal (T6, section 8) : toute la carte et son
     * bouton d'agrandissement ouvrent le même écran plein écran que
     * l'accueil ; l'état vide crée la session dans le dossier réel du
     * projet ; bootstrap absent → installation.
     */
    private fun brancherTerminalTiroir() {
        liaison.carteTerminal.setOnClickListener { viewModel.onAction(ActionEditor.OuvrirTerminal) }
        liaison.boutonAgrandirTerminal.setOnClickListener { viewModel.onAction(ActionEditor.OuvrirTerminal) }
        liaison.boutonNouvelleSessionTerminal.setOnClickListener {
            viewModel.onAction(ActionEditor.NouvelleSessionTerminal)
        }
        liaison.boutonInstallerTerminal.setOnClickListener {
            viewModel.onAction(ActionEditor.InstallerOutilsTerminal)
        }
    }

    /**
     * Bascule le contenu du tiroir entre l'explorateur et la carte
     * d'aperçu du terminal (T6). Revenir sur l'explorateur réapplique
     * son rendu — les visibilités appartiennent à [rendre].
     */
    private fun basculerVueTiroir(terminal: Boolean) {
        liaison.carteTerminal.isVisible = terminal
        if (terminal) {
            liaison.listeExplorateur.isVisible = false
            liaison.texteExplorateurVide.isVisible = false
            liaison.progressionTiroir.isVisible = false
            liaison.bandeauAcces.isVisible = false
        } else {
            rendre(viewModel.etat.value)
        }
    }

    /** Rendu de la carte d'aperçu du terminal (T6, section 8) : métadonnées
     * du registre global uniquement — la carte se met à jour en direct. */
    private fun rendreCarteTerminal(etat: EtatTerminalTiroir) {
        liaison.compteurSessionsTerminal.text =
            resources.getQuantityString(
                R.plurals.editor_terminal_sessions_actives,
                etat.sessionsVivantes,
                etat.sessionsVivantes,
            )

        // Bootstrap absent : l'installation d'abord (garde-fou de la
        // section 7, symétrique de l'accueil).
        liaison.texteNonInstalleTerminal.isVisible = !etat.bootstrapInstalle
        liaison.boutonInstallerTerminal.isVisible = !etat.bootstrapInstalle

        // État vide : aucune session du tout (vivantes ou terminées).
        val aucuneSession = etat.bootstrapInstalle && etat.nbSessions == 0
        liaison.texteAucuneSessionTerminal.isVisible = aucuneSession
        liaison.boutonNouvelleSessionTerminal.isVisible = aucuneSession

        // Session active : pastille, libellé (état suffisé), dernière sortie.
        val session = etat.sessionActive
        liaison.libelleSessionTerminal.isVisible = session != null
        liaison.sortieSessionTerminal.isVisible =
            session != null && session.lastOutputPreview.isNotBlank()
        if (session != null) {
            liaison.libelleSessionTerminal.text =
                if (session.isAlive) {
                    session.label
                } else {
                    getString(R.string.editor_terminal_session_terminee, session.label)
                }
            liaison.sortieSessionTerminal.text = session.lastOutputPreview
            val couleurPastille =
                MaterialColors.getColor(
                    liaison.root,
                    if (session.isAlive) {
                        androidx.appcompat.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOutline
                    },
                )
            liaison.pastilleTerminal.backgroundTintList = ColorStateList.valueOf(couleurPastille)
        }

        // Accessibilité : la carte se lit d'un geste (libellé + état).
        val resume =
            session?.label
                ?: getString(
                    if (etat.bootstrapInstalle) {
                        R.string.editor_terminal_aucune_session
                    } else {
                        R.string.editor_terminal_non_installe
                    },
                )
        liaison.carteTerminal.contentDescription =
            getString(R.string.editor_terminal_carte_cd, resume)
    }

    /** Menu contextuel d'un nœud de l'explorateur (étape 17). */
    private fun menuContextuelNoeud(noeud: NoeudExplorateur) {
        val menu = PopupMenu(this, liaison.listeExplorateur)
        if (noeud.estDossier) {
            menu.menu.add(
                android.view.Menu.NONE,
                ID_NOUVEAU_FICHIER,
                android.view.Menu.NONE,
                R.string.editor_menu_nouveau_fichier,
            )
            menu.menu.add(
                android.view.Menu.NONE,
                ID_NOUVEAU_DOSSIER,
                android.view.Menu.NONE,
                R.string.editor_menu_nouveau_dossier,
            )
        }
        menu.menu.add(android.view.Menu.NONE, ID_RENOMMER, android.view.Menu.NONE, R.string.editor_menu_renommer)
        menu.menu.add(android.view.Menu.NONE, ID_SUPPRIMER, android.view.Menu.NONE, R.string.editor_menu_supprimer)
        menu.menu.add(
            android.view.Menu.NONE,
            ID_MENU_ACTUALISER,
            android.view.Menu.NONE,
            R.string.editor_menu_actualiser,
        )
        menu.setOnMenuItemClickListener { item -> surChoixMenuNoeud(noeud, item) }
        menu.show()
    }

    /** Réagit au choix du menu contextuel d'un nœud (étape 17). */
    private fun surChoixMenuNoeud(
        noeud: NoeudExplorateur,
        item: MenuItem,
    ): Boolean {
        when (item.itemId) {
            ID_NOUVEAU_FICHIER -> {
                dialogueNom(
                    titre = getString(R.string.editor_creation_fichier_titre),
                    indication = R.string.editor_nom_fichier_indication,
                    bouton = R.string.editor_nom_bouton_creer,
                    initial = "",
                ) { nom -> viewModel.onAction(ActionEditor.CreerFichier(noeud.uri, nom)) }
            }

            ID_NOUVEAU_DOSSIER -> {
                dialogueNom(
                    titre = getString(R.string.editor_creation_dossier_titre),
                    indication = R.string.editor_nom_dossier_indication,
                    bouton = R.string.editor_nom_bouton_creer,
                    initial = "",
                ) { nom -> viewModel.onAction(ActionEditor.CreerDossier(noeud.uri, nom)) }
            }

            ID_RENOMMER -> {
                dialogueNom(
                    titre = getString(R.string.editor_renommage_titre, noeud.nom),
                    indication = 0,
                    bouton = R.string.editor_nom_bouton_renommer,
                    initial = noeud.nom,
                ) { nom -> viewModel.onAction(ActionEditor.RenommerDocument(noeud.uri, nom)) }
            }

            ID_SUPPRIMER -> {
                dialogueSuppression(noeud)
            }

            ID_MENU_ACTUALISER -> {
                viewModel.onAction(ActionEditor.Rafraichir)
            }
        }
        return true
    }

    /** Menu de création à la racine du projet (étape 17). */
    private fun menuCreationRacine(
        ancre: View,
        racine: String,
    ) {
        val menu = PopupMenu(this, ancre)
        menu.menu.add(
            android.view.Menu.NONE,
            ID_NOUVEAU_FICHIER,
            android.view.Menu.NONE,
            R.string.editor_menu_nouveau_fichier,
        )
        menu.menu.add(
            android.view.Menu.NONE,
            ID_NOUVEAU_DOSSIER,
            android.view.Menu.NONE,
            R.string.editor_menu_nouveau_dossier,
        )
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_NOUVEAU_FICHIER -> {
                    dialogueNom(
                        titre = getString(R.string.editor_creation_fichier_titre),
                        indication = R.string.editor_nom_fichier_indication,
                        bouton = R.string.editor_nom_bouton_creer,
                        initial = "",
                    ) { nom -> viewModel.onAction(ActionEditor.CreerFichier(racine, nom)) }
                }

                ID_NOUVEAU_DOSSIER -> {
                    dialogueNom(
                        titre = getString(R.string.editor_creation_dossier_titre),
                        indication = R.string.editor_nom_dossier_indication,
                        bouton = R.string.editor_nom_bouton_creer,
                        initial = "",
                    ) { nom -> viewModel.onAction(ActionEditor.CreerDossier(racine, nom)) }
                }
            }
            true
        }
        menu.show()
    }

    /**
     * Dialogue de saisie d'un nom de fichier/dossier : validation
     * **avant** envoi (validateur partagé du wizard, raison localisée
     * sous le champ) — l'action ne part que si le nom est valide.
     */
    private fun dialogueNom(
        titre: CharSequence,
        indication: Int,
        bouton: Int,
        initial: String,
        surNom: (String) -> Unit,
    ) {
        val corps = DialogueNomFichierBinding.inflate(layoutInflater)
        val saisie = corps.saisieNomFichier as TextInputEditText
        if (indication != 0) corps.champNomFichier.hint = getString(indication)
        saisie.setText(initial)
        saisie.setSelection(saisie.text?.length ?: 0)
        saisie.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        val dialogue =
            MaterialAlertDialogBuilder(this)
                .setTitle(titre)
                .setView(corps.root)
                .setPositiveButton(bouton, null)
                .setNegativeButton(R.string.editor_fermeture_annuler, null)
                .create()

        // Validation au clic (le dialogue reste ouvert tant que le nom
        // est invalide — jamais d'aller-retour silencieux).
        dialogue.setOnShowListener {
            dialogue.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nom =
                    saisie.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                val raison = viewModel.evaluerNomFichier(nom)
                if (raison == null) {
                    dialogue.dismiss()
                    surNom(nom)
                } else {
                    corps.erreurNomFichier.text = messageNomFichier(raison, nom)
                    corps.erreurNomFichier.isVisible = true
                }
            }
        }
        dialogue.show()
    }

    /** Message localisé de la raison d'un nom invalide (étape 17). */
    private fun messageNomFichier(
        raison: RaisonValidation,
        nom: String,
    ): String =
        when (raison) {
            is RaisonValidation.LongueurNom -> {
                getString(R.string.editor_erreur_nom_longueur)
            }

            is RaisonValidation.CaractereInterditNom -> {
                getString(R.string.editor_erreur_nom_caractere, raison.fautif.toString())
            }

            is RaisonValidation.PointsFictifsNom -> {
                getString(R.string.editor_erreur_nom_points)
            }

            is RaisonValidation.FinNomInterdite -> {
                getString(R.string.editor_erreur_nom_fin)
            }

            is RaisonValidation.NomReserveWindows -> {
                getString(R.string.editor_erreur_nom_reserves, nom)
            }

            else -> {
                getString(R.string.editor_erreur_nom_generique)
            }
        }

    /** Confirmation de suppression d'un document (étape 17). */
    private fun dialogueSuppression(noeud: NoeudExplorateur) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.editor_suppression_titre, noeud.nom))
            .setMessage(
                if (noeud.estDossier) {
                    R.string.editor_suppression_dossier_message
                } else {
                    R.string.editor_suppression_fichier_message
                },
            ).setPositiveButton(R.string.editor_suppression_confirmer) { _, _ ->
                viewModel.onAction(ActionEditor.SupprimerDocument(noeud.uri))
            }.setNegativeButton(R.string.editor_fermeture_annuler, null)
            .show()
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

    /** Rend l'état : titre, tiroir (explorateur/bandeau), onglets, éditeur, panneau. */
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
        rendreTypeProjet(etat)
        rendreOnglets(etat)
        rendreEditeur(etat)
        rendrePanneau(etat)
        majRetourSysteme(ongletsSales = etat.onglets.any { it.isDirty })
    }

    /**
     * Ligne « type de projet » de l'en-tête du tiroir (étape 18) : modèle
     * reconnu depuis `.codeide/project.json`, nom i18n + version ; un
     * dossier importé sans métadonnées est dit tel, un projet créé dont
     * le fichier a disparu affiche « type non reconnu » ; rien pendant la
     * vérification d'accès ou en cas de panne.
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
        liaison.typeProjetTiroir.isVisible = texte != null
        if (texte != null) liaison.typeProjetTiroir.text = texte
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

        // Identifiants du menu contextuel des nœuds de l'explorateur
        // (étape 17 — même approche programmatique).
        const val ID_NOUVEAU_FICHIER = 10
        const val ID_NOUVEAU_DOSSIER = 11
        const val ID_RENOMMER = 12
        const val ID_SUPPRIMER = 13
        const val ID_MENU_ACTUALISER = 14
    }
}
