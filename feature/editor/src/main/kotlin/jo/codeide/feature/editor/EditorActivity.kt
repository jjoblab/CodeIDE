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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.ActivityEditorBinding

/**
 * Espace de travail d'un projet (étape 13 — fondations, prompt compagnon
 * section 5) : **trois zones sans logique**.
 *
 * - tiroir de navigation gauche (en-tête : nom du projet, chemin lisible,
 *   « Fermer le projet ») — permanent sur grand écran (ADR 0026) ;
 * - zone centrale : barre d'outils, onglets de fichiers vides et états
 *   vides en attendant l'explorateur (étape 14) et les onglets (étape 15) ;
 * - panneau inférieur replié à trois onglets vides (Console · Problèmes ·
 *   Journal) — le contenu arrive à l'étape 16.
 *
 * Le bouton retour ferme le tiroir s'il est ouvert, sinon quitte. Les
 * actions d'édition arriveront avec les étapes suivantes : rien ici n'a
 * de logique métier.
 */
@AndroidEntryPoint
class EditorActivity : AppCompatActivity() {
    private val viewModel: EditorViewModel by viewModels()

    private lateinit var liaison: ActivityEditorBinding

    private lateinit var comportementPanneau: BottomSheetBehavior<*>

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

    /** Rend l'état : titre, en-tête du tiroir, états vides. */
    private fun rendre(etat: EtatEditor) {
        val projet = etat.projet
        liaison.progression.isVisible = etat.chargement

        liaison.zoneVide.isVisible = !etat.chargement && projet != null
        liaison.zoneIntrouvable.isVisible = !etat.chargement && projet == null
        if (projet == null) return

        liaison.toolbarEditeur.title = projet.name
        liaison.nomProjetTiroir.text = projet.name
        liaison.cheminTiroir.text = projet.location.displayPath
    }

    private companion object {
        /** Plus petit écran considéré « grand » (dp). */
        const val SEUIL_GRAND_ECRAN = 600
    }
}
