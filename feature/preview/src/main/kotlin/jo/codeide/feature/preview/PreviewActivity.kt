package jo.codeide.feature.preview

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
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
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.ZonesDepot
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Écran DÉDIÉ de l'aperçu XML (PE6-0, maquette
 * `docs/mockups/apercu-xml.html` § « Activité dédiée » ; chrome PE6b
 * § « Chrome Material dans la nouvelle activité ») : le panneau quitte
 * l'overlay d'`EditorActivity` et s'empile au-dessus — même patron que
 * l'écran du terminal ([AppNavigator.openPreview], activité
 * `exported="false"` déclarée dans le manifest du module).
 *
 * Répartition (maquette § « Ce qui change dans le dépôt ») :
 * - [PreviewViewModel] : moteur (rotation sûre), source du registre,
 *   projet (`res/` FUSE, thème, label) ;
 * - [ControleurPreviewXml] : couture moteur ↔ vue + tap → span ;
 * - CETTE activité : chrome Material — toolbar, PUCES défilantes
 *   (feuille de choix de l'appareil, jour/nuit, langue, popover
 *   Calques), HUD de zoom (+ / 100 % / ajuster / −) ; le module porte
 *   le chrome, le moteur `:preview:xml` reste zéro-Material (règle du
 *   module).
 *
 * PE6b (maquette § « Réglages » : « Puces défilantes, feuille de choix
 * pour l'appareil ») : la puce Appareil ouvre un menu RADIO du catalogue
 * étendu (tablette et petit écran inclus) — plus de cycle à l'aveugle ;
 * la puce Calques ouvre le popover à COCHES des calques (parent/frères,
 * marges et padding, contraintes, problèmes, bornes, grille 8 dp) ;
 * le HUD de zoom expose [VuePreviewXml.zoomer]/[VuePreviewXml.ajuster].
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
 */
@Suppress("TooManyFunctions") // Chrome de l'écran : chaque menu a SA méthode (règle 16 — même patron que l'éditeur).
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

        // Puces de réglages (PE6b, miroir `.chips` de la maquette).
        liaison.boutonAppareilApercu.setOnClickListener { ouvrirFeuilleAppareil() }
        liaison.boutonNuitApercu.setOnClickListener {
            controleur?.basculerNuit()
            majReglages()
        }
        liaison.boutonLangueApercu.setOnClickListener {
            controleur?.basculerLangue()
            majReglages()
        }
        liaison.boutonCalquesApercu.setOnClickListener { ouvrirPopoverCalques() }
        majReglages()

        // HUD de zoom (PE6b, miroir `.zoom` de la maquette) : les boutons
        // exposent zoomer/ajuster de la VUE (spécification PE6b).
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

        // Piège « mort du processus » : source absente du registre → se
        // terminer et relancer l'éditeur, jamais un panneau vide.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.finir.collect { finir ->
                    if (finir) rouvrirEditeur()
                }
            }
        }

        monterFeuille()
        majTitre()
    }

    /**
     * La feuille inférieure (PE6d) : le fragment porte son contenu —
     * l'activité pose le BottomSheetBehavior et le HUD de zoom REMONTE
     * au déploiement (miroir `applySheet` : `status/zoom .bottom` suive
     * la feuille).
     */
    private fun monterFeuille() {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.conteneur_feuille, FeuilleApercuFragment())
            .commit()
        supportFragmentManager.executePendingTransactions()
        val feuille = liaison.conteneurFeuille.getChildAt(0) ?: return
        val comportement = BottomSheetBehavior.from(feuille)
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
                    val delta = (feuille.height - comportement.peekHeight).coerceAtLeast(0).toFloat()
                    liaison.hudZoom.translationY = -delta * decalage
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.indiceSelectionne.collect { indice ->
                    liaison.vuePreview.surlignerVue(indice)
                }
            }
        }
        liaison.vuePreview.auDepot = { contenu, x, y ->
            val ok = viewModel.appliquerDepot(contenu, x, y)
            Toast
                .makeText(
                    this,
                    if (ok) {
                        getString(R.string.preview_toast_depot_ok, contenu.nomAffiche, nomConteneur())
                    } else {
                        getString(R.string.preview_toast_depot_ko)
                    },
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    /** Le conteneur visé du DERNIER dépôt réussi (libellé du toast, miroir `par.cls`). */
    private fun nomConteneur(): String = viewModel.dernierParentDepot ?: ""

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
        val popup = PopupMenu(this, liaison.boutonAppareilApercu)
        val catalogue = ProfilPeripherique.CATALOGUE
        val courant = controleur?.profil
        catalogue.forEachIndexed { indice, profil ->
            val item = popup.menu.add(GROUPE_APPAREIL, indice, indice, profil.description())
            item.isCheckable = true
            item.isChecked = profil == courant
        }
        popup.menu.setGroupCheckable(GROUPE_APPAREIL, true, true)
        popup.setOnMenuItemClickListener { item ->
            catalogue.getOrNull(item.itemId)?.let { profil ->
                controleur?.definirProfil(profil)
                majReglages()
            }
            true
        }
        popup.show()
    }

    /**
     * Popover Calques (PE6b, miroir `showPop("layers")` de la maquette) :
     * menu à COCHES ancré à la puce — chaque entrée bascule un calque de
     * la vue (états relus à l'ouverture, jamais mémorisés en double).
     */
    private fun ouvrirPopoverCalques() {
        val vue = liaison.vuePreview
        val calques =
            listOf(
                EntreeCalque(R.string.preview_calque_parent_freres, { vue.calquesParentFreres }, {
                    vue.calquesParentFreres =
                        it
                }),
                EntreeCalque(R.string.preview_calque_marges_padding, { vue.calquesMargesPadding }, {
                    vue.calquesMargesPadding =
                        it
                }),
                EntreeCalque(R.string.preview_calque_contraintes, { vue.calquesContraintes }, {
                    vue.calquesContraintes =
                        it
                }),
                EntreeCalque(
                    R.string.preview_calque_problemes,
                    { vue.calquesProblemes },
                    { vue.calquesProblemes = it },
                ),
                EntreeCalque(R.string.preview_calque_bornes, { vue.calquesBornes }, { vue.calquesBornes = it }),
                EntreeCalque(R.string.preview_calque_grille, { vue.grilleAffichee }, { vue.grilleAffichee = it }),
            )
        val popup = PopupMenu(this, liaison.boutonCalquesApercu)
        calques.forEachIndexed { indice, calque ->
            val item = popup.menu.add(GROUPE_CALQUES, indice, indice, calque.titreRes)
            item.isCheckable = true
            item.isChecked = calque.estActif()
        }
        popup.setOnMenuItemClickListener { item ->
            calques.getOrNull(item.itemId)?.let { calque ->
                calque.poser(!calque.estActif())
            }
            true
        }
        popup.show()
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

    /** Libellés des puces de réglages : appareil, jour/nuit, langue (PE6b). */
    private fun majReglages() {
        val controleur = controleur ?: return
        liaison.boutonAppareilApercu.text = getString(R.string.preview_appareil_format, controleur.profil.nom)
        liaison.boutonNuitApercu.setText(
            if (controleur.nuit) {
                R.string.preview_nuit
            } else {
                R.string.preview_jour
            },
        )
        liaison.boutonNuitApercu.isChecked = controleur.nuit
        liaison.boutonLangueApercu.setText(
            when (controleur.langue) {
                "fr" -> R.string.preview_langue_fr
                "en" -> R.string.preview_langue_en
                else -> R.string.preview_langue_defaut
            },
        )
    }

    /** Le bouton « 100 % » affiche l'échelle RÉELLE (PE6b — miroir `zPct`). */
    private fun majPourcentageZoom(echelle: Float) {
        liaison.boutonZoomPct.text = getString(R.string.preview_zoom_pct, (echelle * POURCENTAGE).roundToInt())
    }

    /** Titre de la toolbar : le nom du fichier affiché (sélecteur via le menu). */
    private fun majTitre() {
        val nom = viewModel.nomFichierCourant()
        supportActionBar?.title = nom.ifBlank { getString(R.string.preview_titre) }
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

        /** Identifiant du groupe radio du menu Appareil. */
        private const val GROUPE_APPAREIL: Int = 1

        /** Identifiant du groupe à coches du menu Calques. */
        private const val GROUPE_CALQUES: Int = 2
    }
}
