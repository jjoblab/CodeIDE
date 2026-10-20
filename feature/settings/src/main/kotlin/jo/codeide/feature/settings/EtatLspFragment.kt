package jo.codeide.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.LogRedactor
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.Retour
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentLspEtatBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * L5 — État et diagnostic (lot LSP-3, spec § 3) : résumé par moteur
 * (état, comptes, dernier passage à l'heure absolue), dernières erreurs
 * (≤ 20, messages RÉ-EXPURGÉS au rendu — le modèle d'indexation n'est
 * pas journalisé par LogRedactor) et « Copier le diagnostic LSP » vers
 * le presse-papiers, retour par [Retour.snackbar] (modèle « Copier le
 * diagnostic » de l'Environnement).
 */
@AndroidEntryPoint
class EtatLspFragment : BaseFragment<FragmentLspEtatBinding>() {
    private val viewModel: LangagesViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentLspEtatBinding = FragmentLspEtatBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }
        binding.boutonCopierDiagnostic.setOnClickListener { copierDiagnostic() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu OBSERVÉ : résumé des trois moteurs + erreurs récentes. */
    private fun rendre(etat: EtatEcransLsp) {
        val modele = PresentationLsp.etat(etat.moteurs)
        rendreResume(binding.rangeeResumeJava, getString(R.string.lsp_langage_java), modele.java)
        rendreResume(binding.rangeeResumeKotlin, getString(R.string.lsp_langage_kotlin), modele.kotlin)
        rendreResume(binding.rangeeResumeXml, getString(R.string.lsp_langage_xml), modele.xml)

        binding.erreursRecentes.removeAllViews()
        val lignes = modele.erreursRecentes.take(ERREURS_AFFICHEES)
        lignes.forEach { erreur ->
            val ligne =
                layoutInflater.inflate(
                    R.layout.ligne_lsp_erreur,
                    binding.erreursRecentes,
                    false,
                ) as MaterialTextView
            val heure = erreur.horodatageMs?.let(::heureDePassage) ?: HEURE_INCONNUE
            ligne.text =
                getString(
                    R.string.lsp_erreur_ligne,
                    heure,
                    LogRedactor.redact(erreur.message),
                )
            binding.erreursRecentes.addView(ligne)
        }
        binding.vueEtatAucuneErreur.isVisible = lignes.isEmpty()
        binding.erreursRecentes.isVisible = lignes.isNotEmpty()
    }

    /** Résumé d'un moteur : sous-titre composé + dernier passage absolu. */
    private fun rendreResume(
        rangee: jo.codeide.core.ui.RangeeParametre,
        nom: String,
        ligne: LigneMoteur,
    ) {
        val morceaux = ligne.sousTitres.map { resoudreTexteLsp(it) }.toMutableList()
        ligne.dernierPassageMs?.let { horodatage ->
            morceaux.add(getString(R.string.lsp_dernier_passage_heure, heureDePassage(horodatage)))
        }
        rangee.sousTitre = morceaux.joinToString(SEPARATEUR)
        val libelle = getString(ligne.etat.libelle)
        rangee.indicateur = ligne.etat.versNiveau()
        rangee.indicateurTexte = libelle
        rangee.contentDescription = getString(R.string.lsp_cd_moteur, nom, libelle)
    }

    /** Copie le diagnostic EXPURGÉ + retour Snackbar (jamais de Toast). */
    private fun copierDiagnostic() {
        viewLifecycleOwner.lifecycleScope.launch {
            val corps = viewModel.diagnostic()
            val pressePapiers =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@launch
            pressePapiers.setPrimaryClip(
                ClipData.newPlainText(
                    getString(R.string.lsp_copier_diagnostic),
                    getString(R.string.lsp_diagnostic_entete) + "\n" + corps,
                ),
            )
            Retour.snackbar(binding.root, getString(R.string.lsp_diagnostic_copie))
        }
    }

    private companion object {
        /** Erreurs affichées (spec § 3 L5 : « ≤ 20 »). */
        const val ERREURS_AFFICHEES = 20
        const val SEPARATEUR = " · "
        const val HEURE_INCONNUE = "—"
    }
}
