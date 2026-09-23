package jo.codeide.feature.diagnostics

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.feature.diagnostics.databinding.FragmentDiagnosticBinding
import jo.codeide.feature.diagnostics.debug.MenuDebug
import javax.inject.Inject

/**
 * Écran Diagnostic (étape 12) : visionneuse des journaux applicatifs et des
 * rapports de plantage.
 *
 * Accès : Paramètres › Avancé › Diagnostic. Deux onglets (`TabLayout` +
 * `ViewPager2`) plus une section « Informations » (version, appareil non
 * identifiant, identifiant de session) ; le menu debug (build debug
 * uniquement) vit désormais ici, jamais sur les écrans de production.
 */
@AndroidEntryPoint
class DiagnosticFragment : BaseFragment<FragmentDiagnosticBinding>() {
    @Inject
    lateinit var navigator: AppNavigator

    @Inject
    lateinit var journal: AppLogger

    @Inject
    lateinit var fichiers: FileSystem

    /** Identité de build — section « Informations ». */
    @Inject
    lateinit var infosBuild: CrashAppInfo

    /** Photographie non identifiante de l'appareil — section « Informations ». */
    @Inject
    lateinit var appareil: DeviceInfo

    /** Sélecteur d'arborescence SAF — essais du menu debug (S1-S5). */
    private val lanceurArbre =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            MenuDebug.dossierChoisi(this, journal, fichiers, uri)
        }

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentDiagnosticBinding = FragmentDiagnosticBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la toolbar passe sous la barre d'état, les
        // onglets et la barre basse restent au-dessus de la barre de
        // navigation et du clavier (recherche).
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)

        binding.toolbarDiagnostic.setNavigationOnClickListener { navigator.goBack() }

        remplirInformations()

        binding.pagerDiagnostic.adapter = PagesDiagnosticAdapter(this)
        TabLayoutMediator(
            binding.ongletsDiagnostic,
            binding.pagerDiagnostic,
        ) { onglet, position ->
            onglet.text =
                when (position) {
                    POSITION_JOURNAUX -> getString(R.string.diagnostics_onglet_journaux)
                    else -> getString(R.string.diagnostics_onglet_plantages)
                }
        }.attach()

        // Menu debug (build debug uniquement) : la version release installe
        // un no-op de même signature — l'écran ne connaît pas la variante.
        MenuDebug.installer(
            fragment = this,
            conteneur = binding.conteneurMenuDebug,
            logger = journal,
            fichiers = fichiers,
            version = infosBuild.versionName,
            choisirArbre = { lanceurArbre.launch(null) },
        )
    }

    /** Remplit la section « Informations » : version, appareil, session. */
    private fun remplirInformations() {
        binding.texteVersion.text =
            getString(R.string.diagnostics_infos_version, infosBuild.versionName, infosBuild.versionCode)
        binding.texteAppareil.text =
            getString(
                R.string.diagnostics_infos_appareil,
                appareil.manufacturer,
                appareil.model,
                appareil.androidVersion,
                appareil.apiLevel,
            )
        binding.texteSession.text = getString(R.string.diagnostics_infos_session, journal.sessionId)
    }

    /** Deux pages : Journaux et Plantages. */
    private class PagesDiagnosticAdapter(
        hote: Fragment,
    ) : FragmentStateAdapter(hote) {
        override fun getItemCount(): Int = NOMBRE_ONGLETS

        override fun createFragment(position: Int): Fragment =
            when (position) {
                POSITION_JOURNAUX -> JournalFragment()
                else -> PlantagesFragment()
            }
    }

    private companion object {
        /** Position de l'onglet Journaux. */
        const val POSITION_JOURNAUX = 0

        /** Nombre d'onglets de l'écran. */
        const val NOMBRE_ONGLETS = 2
    }
}
