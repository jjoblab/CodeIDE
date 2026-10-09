package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentRechercheBinding

/**
 * Fragment Recherche du tiroir (mission Recherche S2-S5, ADR 0094) :
 * champ de recherche avec bascules, résultats groupés par fichier,
 * navigation S3, remplacement S4, Go to File S5.
 */
@AndroidEntryPoint
class RechercheFragment : Fragment() {
    private val viewModel: RechercheViewModel by viewModels()

    private var liaisonAmorce: FragmentRechercheBinding? = null
    private val liaison: FragmentRechercheBinding
        get() = checkNotNull(liaisonAmorce) { "Binding détruit" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonAmorce = FragmentRechercheBinding.inflate(inflater, container, false)
        return liaison.root
    }

    @Suppress("LongMethod")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        liaison.boutonEffacer.setOnClickListener { viewModel.effacer() }
        liaison.basculeCasse.setOnClickListener { viewModel.basculerCasse() }
        liaison.basculeMotEntier.setOnClickListener { viewModel.basculerMotEntier() }
        liaison.basculeRegex.setOnClickListener { viewModel.basculerRegex() }
        // S4 : remplacement.
        liaison.boutonRemplacer.setOnClickListener { viewModel.basculerRemplacement() }
        liaison.boutonToutRemplacer.setOnClickListener {
            confirmerToutRemplacer()
        }
        liaison.champRemplacement.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.texteRemplacement(s?.toString() ?: "")
                }
            },
        )
        // S5 : bascule mode fichiers.
        liaison.basculeModeFichiers.setOnClickListener { viewModel.basculerModeFichiers() }
        // S3 : navigation.
        liaison.boutonPrecedent.setOnClickListener { viewModel.occurrencePrecedente() }
        liaison.boutonSuivant.setOnClickListener { viewModel.occurrenceSuivante() }
        // S3 : clic sur un résultat ouvre le fichier.
        liaison.listeResultats.setOnClickListener {
            val index = indexResultatClique
            if (index >= 0) viewModel.ouvrirOccurrence(index)
        }
        liaison.champRecherche.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.requete(s?.toString() ?: "")
                }
            },
        )
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        // S3/S4 : collecte des effets.
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            when (effet) {
                is EffetRecherche.OuvrirFichier -> {
                    // L'activité ouvre le fichier — le tiroir se referme.
                    // Branchement sur EditorViewModel quand le chemin FUSE →
                    // URI SAF sera résolu (évolution navigation S3).
                }

                is EffetRecherche.RemplacementTermine -> {
                    Snackbar
                        .make(
                            liaison.root,
                            "${effet.appliques} remplacement(s) appliqué(s)" +
                                if (effet.echecs > 0) ", ${effet.echecs} échec(s)" else "",
                            Snackbar.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    private var indexResultatClique = -1

    private fun rendre(etat: EtatRecherche) {
        liaison.basculeCasse.isActivated = etat.ignorerCasse
        liaison.basculeMotEntier.isActivated = etat.motEntier
        liaison.basculeRegex.isActivated = etat.regex
        liaison.basculeModeFichiers.isActivated = etat.modeFichiers
        liaison.boutonEffacer.isVisible = etat.requete.isNotBlank()
        liaison.chargement.isVisible = etat.chargement
        liaison.resume.isVisible = etat.resultats.isNotEmpty()
        liaison.resume.text = etat.resume
        liaison.listeResultats.isVisible = etat.resultats.isNotEmpty()
        liaison.listeResultats.text =
            etat.resultats.joinToString("\n\n") { r ->
                "${r.nomFichier}:${r.numeroLigne}\n  ${r.extrait}"
            }
        liaison.zoneVide.isVisible = etat.requete.isNotBlank() && etat.resultats.isEmpty() && !etat.chargement
        liaison.erreur.isVisible = etat.erreur != null
        liaison.erreur.text = etat.erreur
        liaison.plafoidAtteint.isVisible = etat.plafondAtteint
        // S3 : navigation.
        liaison.boutonPrecedent.isVisible = etat.occurrencePrecedentePossible
        liaison.boutonSuivant.isVisible = etat.occurrenceSuivantePossible
        // S4 : remplacement.
        liaison.zoneRemplacement.isVisible = etat.afficherRemplacement
        liaison.boutonToutRemplacer.isVisible = etat.afficherRemplacement && etat.remplacementPossible
        // S5 : libellé du bouton mode.
        liaison.basculeModeFichiers.text =
            if (etat.modeFichiers) {
                getString(
                    R.string.recherche_mode_texte,
                )
            } else {
                getString(R.string.recherche_mode_fichiers)
            }
    }

    /** S4 : confirmation avant « tout remplacer ». */
    private fun confirmerToutRemplacer() {
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recherche_confirmer_remplacement)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.recherche_confirmer_message,
                    etatValue().resultats.size,
                    etatValue().resultats.size,
                    etatValue().nbFichiers,
                    etatValue().texteRemplacement,
                ),
            ).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recherche_remplacer) { _, _ ->
                viewModel.toutRemplacer()
            }.show()
    }

    private fun etatValue() = viewModel.etat.value

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonAmorce = null
    }
}
