package jo.codeide.feature.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.RetentionIa
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.Retour
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.feature.ai.databinding.FragmentReglagesIaBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Paramètres → Assistant IA (mission IA, lot IA-2, spec E4) : activation,
 * fournisseurs (état de clé par ligne), modèle, confidentialité, limites.
 * Aucune logique métier : orchestration du [ReglagesIaViewModel] (UDF).
 */
@AndroidEntryPoint
public class ReglagesIaFragment : BaseFragment<FragmentReglagesIaBinding>() {
    /** Navigation découplée (consentement, détail fournisseur, retour). */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: ReglagesIaViewModel by viewModels()

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentReglagesIaBinding = FragmentReglagesIaBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.toolbarReglagesIa.setNavigationOnClickListener { navigator.goBack() }
        brancher()
    }

    private fun brancher() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.etat.collect { etat -> rendre(etat) }
                }
                launch {
                    viewModel.effets.collect { effet -> rendreEffet(effet) }
                }
            }
        }

        // v0.187.0 (rapport IA §5 Lot 2, B2b — boucle du consentement) :
        // les interrupteurs qui REFLETTENT un état persisté ne réagissent
        // plus au changement d'état (`setOnCheckedChangeListener`) mais au
        // CLIC SEUL — un clic utilisateur. Le listener d'état se
        // déclenchait AUSSI sur la restauration de vue (Android restaure
        // `isChecked` APRÈS `onViewCreated`, à `onViewStateRestored`) et
        // sur tout `setChecked()` programmatique : au retour du
        // consentement, l'interrupteur restauré à « coché » réarmait
        // `ChangerActivation(true)` → `OuvrirConsentement` → l'écran se
        // rouvrait EN BOUCLE. `saveEnabled=false` (layout) retire en
        // PLUS la restauration ; le clic reste la seule porte d'entrée.
        binding.interrupteurIaActive.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.ChangerActivation(binding.interrupteurIaActive.isChecked))
            // « Activer » ne persiste RIEN (c'est l'écran de consentement
            // qui active) : on rend à l'interrupteur l'état RÉÉL — « Pas
            // maintenant » le laisse donc désactivé, `rendre()` ne le
            // repassera à vrai qu'après le consentement daté.
            binding.interrupteurIaActive.isChecked = viewModel.etat.value.active
        }
        binding.interrupteurCompletionFantome.setOnClickListener {
            viewModel.onAction(
                ActionReglagesIa.ChangerCompletionFantome(binding.interrupteurCompletionFantome.isChecked),
            )
        }
        binding.ligneConsentement.setOnClickListener { viewModel.onAction(ActionReglagesIa.OuvrirConsentement) }

        binding.rangeeFournisseurAnthropic.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.OuvrirFournisseur(FournisseursIa.ANTHROPIC))
        }
        binding.rangeeFournisseurOpenai.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.OuvrirFournisseur(FournisseursIa.OPENAI_COMPATIBLE))
        }
        binding.radioFournisseurAnthropic.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.ChoisirFournisseur(FournisseursIa.ANTHROPIC))
        }
        binding.radioFournisseurOpenai.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.ChoisirFournisseur(FournisseursIa.OPENAI_COMPATIBLE))
        }

        binding.champModeleIa.setOnItemClickListener { _, _, position, _ ->
            val choisi =
                binding.champModeleIa.adapter
                    .getItem(position)
                    .toString()
            viewModel.onAction(ActionReglagesIa.ChangerModele(choisi))
        }
        binding.champModeleIa.setOnEditorActionListener { texte, _, _ ->
            viewModel.onAction(ActionReglagesIa.ChangerModele(texte.text.toString()))
            false
        }
        binding.boutonActualiserModeles.setOnClickListener { viewModel.onAction(ActionReglagesIa.ActualiserModeles) }

        brancherInterrupteur(binding.interrupteurConfFichier, AspectConfidentialite.FICHIER_ACTIF)
        brancherInterrupteur(binding.interrupteurConfChemins, AspectConfidentialite.LISTE_CHEMINS)
        brancherInterrupteur(binding.interrupteurConfDiagnostics, AspectConfidentialite.DIAGNOSTICS)

        brancherHistorique()

        brancherChampsNumeriques()
    }

    /**
     * Champs numériques (v0.189.0, rapport IA §5 Lot 4, B3) : à la
     * sortie du focus, une valeur VALIDE part au ViewModel, une valeur
     * ABSENTE ou INVALIDE est ignorée et le champ REPREND la valeur
     * persistée. L'ancien `texteVersEntier = … ?: -1` amenait
     * `ChangerMaxJetons(-1)`/`ChangerDelai(-1)` que le `coerceIn` du
     * ViewModel bridait SILENCIEUSEMENT à 128 jetons et 5 s : vider un
     * champ puis quitter le focus décuplait les réponses sans prévenir.
     */
    private fun brancherChampsNumeriques() {
        binding.champMaxJetons.setOnFocusChangeListener { _, aLeFocus ->
            if (!aLeFocus) {
                apurerChampNumerique(binding.champMaxJetons, viewModel.etat.value.maxJetonsReponse) { valeur ->
                    viewModel.onAction(ActionReglagesIa.ChangerMaxJetons(valeur))
                }
            }
        }
        binding.champDelai.setOnFocusChangeListener { _, aLeFocus ->
            if (!aLeFocus) {
                apurerChampNumerique(binding.champDelai, viewModel.etat.value.delaiSecondes) { valeur ->
                    viewModel.onAction(ActionReglagesIa.ChangerDelai(valeur))
                }
            }
        }
    }

    /** Historique (lot IA-6) : rétention + tout effacer. */
    private fun brancherHistorique() {
        binding.boutonRetentionHistorique.setOnClickListener {
            val retenues = listOf(RetentionIa.JOURS_7, RetentionIa.JOURS_30, RetentionIa.ILLIMITEE)
            val courante = viewModel.etat.value.retentionConversations
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ia_carte_historique_titre)
                .setSingleChoiceItems(
                    arrayOf(
                        getString(R.string.ia_retention_libelle_7j),
                        getString(R.string.ia_retention_libelle_30j),
                        getString(R.string.ia_retention_libelle_illimitee),
                    ),
                    retenues.indexOf(courante),
                ) { dialogue, choix ->
                    viewModel.onAction(ActionReglagesIa.ChoisirRetention(retenues[choix]))
                    dialogue.dismiss()
                }.setNegativeButton(R.string.ia_historique_annuler, null)
                .show()
        }
        binding.boutonToutEffacerHistorique.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ia_historique_tout_effacer_titre)
                .setMessage(R.string.ia_historique_tout_effacer_message)
                .setPositiveButton(R.string.ia_historique_confirmer) { _, _ ->
                    viewModel.onAction(ActionReglagesIa.ToutEffacerHistorique)
                }.setNegativeButton(R.string.ia_historique_annuler, null)
                .show()
        }
    }

    /** Interrupteur d'aspect de confidentialité (v0.187.0 : clic seul,
     *  plus de listener d'état — cf. [brancher]). */
    private fun brancherInterrupteur(interrupteur: MaterialSwitch, aspect: AspectConfidentialite) {
        interrupteur.setOnClickListener {
            viewModel.onAction(ActionReglagesIa.ChangerConfidentialite(aspect, interrupteur.isChecked))
        }
    }

    private fun rendre(etat: EtatReglagesIa) {
        // v0.187.0 : plus de garde `renduEnCours` — les interrupteurs ne
        // réagissent qu'au CLIC, `setChecked()` programmatique ne
        // ré-arme plus aucune action (la boucle B2b naissait de cette
        // double entrée : rendre × restauration d'état).
        binding.interrupteurIaActive.isChecked = etat.active
        binding.interrupteurCompletionFantome.isChecked = etat.completionFantomeActive
        binding.radioFournisseurAnthropic.isChecked = etat.fournisseurActif == FournisseursIa.ANTHROPIC
        binding.radioFournisseurOpenai.isChecked = etat.fournisseurActif == FournisseursIa.OPENAI_COMPATIBLE

        val parIdentifiant = etat.fournisseurs.associateBy { it.identifiant }
        rendreSousTitreFournisseur(
            binding.sousTitreFournisseurAnthropic,
            parIdentifiant[FournisseursIa.ANTHROPIC],
        )
        rendreSousTitreFournisseur(
            binding.sousTitreFournisseurOpenai,
            parIdentifiant[FournisseursIa.OPENAI_COMPATIBLE],
        )

        binding.champModeleIa.setText(etat.modeleActif)
        binding.champModeleIa.setSimpleItems(etat.modelesRecenses.toTypedArray())
        binding.boutonActualiserModeles.isEnabled = !etat.actualisationEnCours

        binding.interrupteurConfFichier.isChecked = etat.envoyerFichierActif
        binding.interrupteurConfChemins.isChecked = etat.envoyerListeChemins
        binding.interrupteurConfDiagnostics.isChecked = etat.envoyerDiagnostics

        binding.boutonRetentionHistorique.text =
            when (etat.retentionConversations) {
                RetentionIa.JOURS_7 -> getString(R.string.ia_retention_libelle_7j)
                RetentionIa.ILLIMITEE -> getString(R.string.ia_retention_libelle_illimitee)
                RetentionIa.JOURS_30 -> getString(R.string.ia_retention_libelle_30j)
            }

        binding.champMaxJetons.setText(
            String.format(java.util.Locale.getDefault(), "%d", etat.maxJetonsReponse),
        )
        binding.champDelai.setText(
            String.format(java.util.Locale.getDefault(), "%d", etat.delaiSecondes),
        )
    }

    private fun rendreSousTitreFournisseur(
        vue: com.google.android.material.textview.MaterialTextView,
        etatFournisseur: EtatFournisseurIaUi?,
    ) {
        val context = requireContext()
        vue.text =
            when (etatFournisseur?.etatCle) {
                EtatCleFournisseur.ENREGISTREE -> {
                    context.getString(
                        R.string.ia_fournisseur_sous_titre_ok,
                        etatFournisseur.apercu ?: context.getString(R.string.ia_cle_apercu_vide),
                    )
                }

                EtatCleFournisseur.A_RESAISIR -> {
                    context.getString(R.string.ia_fournisseur_sous_titre_ressaisir)
                }

                EtatCleFournisseur.ABSENTE, null -> {
                    context.getString(R.string.ia_fournisseur_sous_titre_absente)
                }
            }
    }

    private fun rendreEffet(effet: EffetReglagesIa) {
        when (effet) {
            EffetReglagesIa.OuvrirConsentement -> {
                navigator.openAiConsentement()
            }

            is EffetReglagesIa.OuvrirFournisseur -> {
                navigator.openAiFournisseur(effet.identifiant)
            }

            is EffetReglagesIa.Message -> {
                val texte =
                    effet.argument?.let { requireContext().getString(effet.ressource, it) }
                        ?: requireContext().getString(effet.ressource)
                Retour.snackbar(binding.root, texte)
            }
        }
    }

    /**
     * Apure un champ numérique à la sortie du focus (v0.189.0, B3) :
     * une valeur VALIDE part au ViewModel (le domaine la borne), une
     * valeur ABSENTE ou INVALIDE est ignorée et le champ reprend la
     * valeur PERSISTÉE — jamais de bridage silencieux du domaine par
     * un `-1` forgé.
     */
    private fun apurerChampNumerique(
        champ: com.google.android.material.textfield.TextInputEditText,
        valeurPersistee: Int,
        envoyer: (Int) -> Unit,
    ) {
        val valeur = champ.text?.toString()?.trim()?.toIntOrNull()
        if (valeur == null) {
            champ.setText(
                String.format(java.util.Locale.getDefault(), "%d", valeurPersistee),
            )
        } else {
            envoyer(valeur)
        }
    }
}
