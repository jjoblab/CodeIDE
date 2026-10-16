package jo.codeide.feature.preview

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.feature.preview.databinding.ActivityPreviewBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran DÉDIÉ de l'aperçu XML (PE6-0, maquette
 * `docs/mockups/apercu-xml.html` § « Activité dédiée ») : le panneau
 * quitte l'overlay d'`EditorActivity` et s'empile au-dessus — même
 * patron que l'écran du terminal ([AppNavigator.openPreview], activité
 * `exported="false"` déclarée dans le manifest du module).
 *
 * Répartition (maquette § « Ce qui change dans le dépôt ») :
 * - [PreviewViewModel] : moteur (rotation sûre), source du registre,
 *   projet (`res/` FUSE, thème, label) ;
 * - [ControleurPreviewXml] : couture moteur ↔ vue + tap → span ;
 * - CETTE activité : chrome Material (toolbar, puces de réglages,
 *   sélecteur de fichier dans le menu) — le module porte le chrome, le
 *   moteur `:preview:xml` reste zéro-Material (règle du module).
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

        // Couture moteur ↔ vue : le tap sur le rendu pose le SPAN source
        // dans la session (port SourceApercu — l'éditeur retrouvera la
        // sélection à son retour dans l'onglet).
        controleur =
            ControleurPreviewXml(
                vue = liaison.vuePreview,
                moteur = viewModel.moteur,
                journal = journal,
                scope = lifecycleScope,
                auSpanTouche = { debut, fin -> viewModel.definirSelection(debut, fin) },
            )

        // Puces de réglages (miroir de l'ancienne barre de l'overlay —
        // profil, jour/nuit, langue ; feuille de choix en PE6b).
        liaison.boutonProfilApercu.setOnClickListener {
            controleur?.profilSuivant()
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
        majReglages()

        // Piège « mort du processus » : source absente du registre → se
        // terminer et relancer l'éditeur, jamais un panneau vide.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.finir.collect { finir ->
                    if (finir) rouvrirEditeur()
                }
            }
        }

        majTitre()
    }

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

    /** Libellés des puces de réglages : profil, jour/nuit, langue. */
    private fun majReglages() {
        val controleur = controleur ?: return
        liaison.boutonProfilApercu.text = controleur.profil.description()
        liaison.boutonNuitApercu.setText(
            if (controleur.nuit) {
                R.string.preview_nuit
            } else {
                R.string.preview_jour
            },
        )
        liaison.boutonLangueApercu.setText(
            when (controleur.langue) {
                "fr" -> R.string.preview_langue_fr
                "en" -> R.string.preview_langue_en
                else -> R.string.preview_langue_defaut
            },
        )
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

    private companion object {
        private const val TAG: String = "PreviewActivity"
    }
}
