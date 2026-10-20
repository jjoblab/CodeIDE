package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.LangageLsp
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.RangeeParametre
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentLspApercuBinding
import javax.inject.Inject

/**
 * L1 — Vue d'ensemble « Intelligence du code » (lot LSP-3, spec
 * `LSP_SPEC_ECRANS.md` § 3) : trois rangées moteurs (pastille
 * `IndicateurEtat` + sous-titre d'état OBSERVÉS), trois rangées
 * d'analyse, note d'effet, et l'état « sans projet » (message + action
 * unique « Ouvrir un projet » — les réglages restent éditables).
 *
 * La cible tactile ≥ 48 dp et le rôle d'appui sont portés par
 * [RangeeParametre] ; la pastille change de FORME avec l'état (jamais la
 * couleur seule) et son changement est annoncé (région live polie).
 */
@AndroidEntryPoint
class LangagesFragment : BaseFragment<FragmentLspApercuBinding>() {
    private val viewModel: LangagesViewModel by activityViewModels()

    /** Navigation découplée : L2/L3/L4/L5 et l'accueil (ouvrir un projet). */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentLspApercuBinding = FragmentLspApercuBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        binding.rangeeMoteurJava.setSurClic { navigator.openLspLangage(LangageLsp.JAVA.name) }
        binding.rangeeMoteurKotlin.setSurClic { navigator.openLspLangage(LangageLsp.KOTLIN.name) }
        binding.rangeeMoteurXml.setSurClic { navigator.openLspLangage(LangageLsp.XML.name) }
        binding.rangeeIndexCache.setSurClic { navigator.openLspIndex() }
        binding.rangeeEnvironnementAnalyse.setSurClic { navigator.openLspEnvironnement() }
        binding.rangeeEtatDiagnostic.setSurClic { navigator.openLspEtat() }
        binding.vueEtatSansProjet.setAction(getString(R.string.lsp_ouvrir_projet)) { navigator.openHome() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu OBSERVÉ : chaque émission reprojette les trois moteurs. */
    private fun rendre(etat: EtatEcransLsp) {
        val modele = PresentationLsp.apercu(etat.moteurs)
        rendreLigneMoteur(binding.rangeeMoteurJava, getString(R.string.lsp_langage_java), modele.java)
        rendreLigneMoteur(binding.rangeeMoteurKotlin, getString(R.string.lsp_langage_kotlin), modele.kotlin)
        rendreLigneMoteur(binding.rangeeMoteurXml, getString(R.string.lsp_langage_xml), modele.xml)
        binding.vueEtatSansProjet.isVisible = !etat.projetOuvert
    }

    /** Sous-titre composé (morceaux joints par « · ») + pastille + rôle complet. */
    private fun rendreLigneMoteur(
        rangee: RangeeParametre,
        nom: String,
        ligne: LigneMoteur,
    ) {
        rangee.sousTitre = ligne.sousTitres.joinToString(SEPARATEUR) { resoudreTexteLsp(it) }
        val libelle = getString(ligne.etat.libelle)
        rangee.indicateur = ligne.etat.versNiveau()
        rangee.indicateurTexte = libelle
        // Rôle complet annoncé d'une seule voix : « Java : Prêt » (spec § 5).
        rangee.contentDescription = getString(R.string.lsp_cd_moteur, nom, libelle)
    }

    private companion object {
        const val SEPARATEUR = " · "
    }
}
