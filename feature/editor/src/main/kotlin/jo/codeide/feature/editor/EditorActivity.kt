package jo.codeide.feature.editor

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.ActivityEditorBinding

/**
 * Espace de travail d'un projet (étapes 13-14, prompt compagnon section 5) :
 * **trois zones**.
 *
 * - tiroir de navigation gauche — en-tête (nom du projet, chemin lisible,
 *   « Fermer le projet », bouton **Actualiser** : revérifie l'état d'accès
 *   et recharge l'arborescence) puis **explorateur de fichiers paresseux**
 *   (étape 14) et barre de navigation basse (Explorateur active, Recherche
 *   et Git visibles mais désactivées) — permanent sur grand écran
 *   (ADR 0026) ;
 * - zone centrale : barre d'outils, onglets de fichiers vides en attente
 *   de l'étape 15, états vides ;
 * - panneau inférieur replié à trois onglets vides (Console · Problèmes ·
 *   Journal) — le contenu arrive à l'étape 16.
 *
 * Les erreurs d'accès du projet (permission perdue, dossier introuvable,
 * stockage injoignable) remplacent l'arborescence par un **bandeau de
 * résolution** : retour à l'accueil, où vivent Relocaliser et Retirer, ou
 * réessai direct pour les erreurs passagères.
 *
 * Le bouton retour ferme le tiroir s'il est ouvert, sinon quitte.
 */
@AndroidEntryPoint
class EditorActivity : AppCompatActivity() {
    private val viewModel: EditorViewModel by viewModels()

    private lateinit var liaison: ActivityEditorBinding

    private lateinit var comportementPanneau: BottomSheetBehavior<*>

    /** Adaptateur de l'arborescence paresseuse du tiroir (étape 14). */
    private lateinit var adaptateurExplorateur: ExplorateurAdapter

    /** Retour système : ferme le tiroir ouvert, sinon quitte (défaut). */
    private val retourTiroir =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                liaison.racineEditeur.closeDrawer(liaison.tiroir)
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
        brancherPanneauInferieur()
        onBackPressedDispatcher.addCallback(this, retourTiroir)

        viewModel.etat.collectWithLifecycle(this, Lifecycle.State.STARTED) { etat -> rendre(etat) }
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
        liaison.boutonFermerProjet.setOnClickListener { finish() }

        liaison.racineEditeur.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerStateChanged(nouvelEtat: Int) {
                    retourTiroir.isEnabled =
                        nouvelEtat == DrawerLayout.STATE_IDLE &&
                        liaison.racineEditeur.isDrawerOpen(liaison.tiroir)
                }

                override fun onDrawerOpened(vueTiroir: View) {
                    retourTiroir.isEnabled = true
                }

                override fun onDrawerClosed(vueTiroir: View) {
                    retourTiroir.isEnabled = false
                }
            },
        )

        if (resources.configuration.smallestScreenWidthDp >= SEUIL_GRAND_ECRAN) {
            // Grand écran : tiroir permanent façon IDE de bureau (ADR 0026) —
            // verrouillé ouvert, plus de geste de bord ni de bouton ☰.
            liaison.racineEditeur.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            liaison.toolbarEditeur.navigationIcon = null
            retourTiroir.isEnabled = false
        }
    }

    /** Explorateur du tiroir : liste, actualisation, barre basse (étape 14). */
    private fun brancherExplorateur() {
        adaptateurExplorateur =
            ExplorateurAdapter { noeud ->
                viewModel.onAction(ActionEditor.BasculerNoeud(noeud.uri))
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

    /** Rend l'état : titre, en-tête du tiroir, états vides, explorateur. */
    private fun rendre(etat: EtatEditor) {
        val projet = etat.projet
        liaison.progression.isVisible = etat.chargement

        liaison.zoneVide.isVisible = !etat.chargement && projet != null
        liaison.zoneIntrouvable.isVisible = !etat.chargement && projet == null
        if (projet == null) return

        liaison.toolbarEditeur.title = projet.name
        liaison.nomProjetTiroir.text = projet.name
        liaison.cheminTiroir.text = projet.location.displayPath
        rendreTiroir(etat)
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

    private companion object {
        /** Plus petit écran considéré « grand » (dp). */
        const val SEUIL_GRAND_ECRAN = 600
    }
}
