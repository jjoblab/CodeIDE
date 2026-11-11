package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.LangageLsp
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.RangeeParametre
import jo.codeide.core.ui.TypeRangee
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentLspLangageBinding
import javax.inject.Inject

/**
 * L2 — Langage (UN seul écran pour Java/Kotlin/XML, spec § 3) : le
 * langage arrive en ARGUMENT de navigation (identifiant de l'énumération
 * — jamais une comparaison de chaîne, ADR 0117). La carte Fonctions est
 * GÉNÉRÉE depuis les capacités annoncées (table du frère via le port
 * statique) en ORDRE FIXE — lecture seule tant que les interrupteurs
 * côté client n'ont pas d'effet (décision LSP-2 : filtres différés au
 * chantier code-editor, jamais un contrôle sans effet) ; les filtres
 * gravité/réactivité portent la puce « Bientôt ».
 */
@AndroidEntryPoint
class LangageLspFragment : BaseFragment<FragmentLspLangageBinding>() {
    private val viewModel: LangagesViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Langage reçu du graphe (identifiant de LangageLsp). */
    private lateinit var langage: LangageLsp

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentLspLangageBinding = FragmentLspLangageBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Identifiant d'énumération reçu du graphe (patron BientotFragment :
        // valueOf — jamais de comparaison de libellé affichable).
        langage = LangageLsp.valueOf(requireArguments().getString(CLE_LANGAGE) ?: LangageLsp.JAVA.name)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        construireFonctions()

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rangées de fonctions : STATIQUES par langage (annoncées ∩ ordre fixe). */
    private fun construireFonctions() {
        val modele =
            PresentationLsp.langage(
                langage,
                jo.codeide.core.domain.MoteursLsp.INACTIFS,
                viewModel.capacitesAnnoncees(langage),
            )
        binding.settingsToolbar.title = getString(modele.titre)
        binding.rangeeNomMoteur.setIcone(modele.iconeMoteurRes)
        binding.rangeeNomMoteur.titre = getString(modele.nomMoteur)
        binding.rangeeNomMoteur.sousTitre = getString(modele.descriptionMoteur)
        // Kotlin/XML : puce « Pack langue — bientôt » (décision D6 différée).
        binding.rangeeNomMoteur.bientot = modele.packLangueBientot

        binding.rangeesFonctions.removeAllViews()
        modele.fonctions.forEach { fonction ->
            val rangee = RangeeParametre(requireContext())
            rangee.layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            rangee.titre = getString(fonction.titre)
            rangee.sousTitre = getString(fonction.description)
            rangee.type = TypeRangee.AUCUN
            binding.rangeesFonctions.addView(rangee)
        }
    }

    /** Rendu OBSERVÉ : seule la rangée État suit l'état des moteurs. */
    private fun rendre(etat: EtatEcransLsp) {
        val modele =
            PresentationLsp.langage(
                langage,
                etat.moteurs,
                viewModel.capacitesAnnoncees(langage),
            )
        val libelle = getString(modele.etat.etat.libelle)
        binding.rangeeEtatMoteur.sousTitre =
            modele.etat.sousTitres.joinToString(SEPARATEUR) { resoudreTexteLsp(it) }
        binding.rangeeEtatMoteur.indicateur = modele.etat.etat.versNiveau()
        binding.rangeeEtatMoteur.indicateurTexte = libelle
        binding.rangeeEtatMoteur.contentDescription =
            getString(R.string.lsp_cd_moteur, getString(modele.titre), libelle)
    }

    private companion object {
        /** Clé de l'argument de navigation (identifiant du langage). */
        const val CLE_LANGAGE = "langage"
        const val SEPARATEUR = " · "
    }
}
