package jo.codeide.feature.newproject

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.model.AppError
import jo.codeide.core.model.CreationProgress
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.EcranCreationBinding
import jo.codeide.feature.newproject.databinding.VueLigneEvenementBinding

/**
 * Écran de création (section 12.3, **hors numérotation**) : liste de
 * progression en temps réel (chaque événement du domaine devient une ligne)
 * avec bouton Annuler, puis état **succès** (animation sobre, ouvrir /
 * accueil / créer un autre) ou **échec** (message compréhensible, essai
 * nouveau, copie des détails expurgés, nettoyage signalé).
 *
 * Le ViewModel reste celui du wizard (partagé avec l'hôte) : cet écran ne
 * fait que rendre [EtatCreation].
 */
class EcranCreationFragment : BaseFragmentEcran<EcranCreationBinding>() {
    private var adapteurEvenements: EvenementsAdapter? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EcranCreationBinding = EcranCreationBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        adapteurEvenements = EvenementsAdapter()
        binding.listeEvenements.layoutManager = LinearLayoutManager(requireContext())
        binding.listeEvenements.adapter = adapteurEvenements

        binding.boutonAnnuler.setOnClickListener { wizard.action(ActionWizard.AnnulerCreation) }
        binding.boutonOuvrirProjet.setOnClickListener { wizard.action(ActionWizard.OuvrirProjetCree) }
        binding.boutonRetourAccueil.setOnClickListener { wizard.action(ActionWizard.RetourAccueil) }
        binding.boutonAutreProjet.setOnClickListener { wizard.action(ActionWizard.Recommencer(garderModele = true)) }
        binding.boutonReessayer.setOnClickListener { wizard.action(ActionWizard.ReessayerCreation) }
        binding.boutonRetourRecapitulatif.setOnClickListener { wizard.action(ActionWizard.RetourRecapitulatif) }
        binding.boutonCopierDetails.setOnClickListener { copierDetails() }
        // V0.31.5 : sortie du piège « un dossier porte déjà ce nom » — le
        // message dit « choisis un autre nom », l'écran doit ENFIN le
        // proposer : retour direct à l'étape Informations (retour
        // arrière pur — les gardes de validité restent intactes).
        binding.boutonCorriger.setOnClickListener {
            wizard.action(ActionWizard.RetourRecapitulatif)
            wizard.action(ActionWizard.AllerEtape(EtapeId.INFORMATIONS))
        }

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat.etatCreation, etat.nomProjet) }
    }

    /** Rendu des trois états possibles de l'écran de création. */
    private fun rendre(
        etat: EtatCreation,
        nomProjet: String,
    ) {
        binding.conteneurProgression.isVisible = etat is EtatCreation.EnCours
        binding.conteneurSucces.isVisible = etat is EtatCreation.Succes
        binding.conteneurEchec.isVisible = etat is EtatCreation.Echec

        when (etat) {
            is EtatCreation.EnCours -> {
                adapteurEvenements?.soumettre(etat.evenements)
            }

            is EtatCreation.Succes -> {
                binding.texteSuccesProjet.text =
                    getString(R.string.wizard_succes_projet, nomProjet.trim())
            }

            is EtatCreation.Echec -> {
                binding.texteEchecMessage.text = messageErreur(etat.erreur)
                binding.texteEchecNettoyage.text =
                    if (etat.residues.isEmpty()) {
                        getString(R.string.wizard_echec_nettoyage_ok)
                    } else {
                        getString(R.string.wizard_echec_nettoyage_residus)
                    }
                // V0.31.5 : les détails techniques deviennent VISIBLES (et
                // restent copiables) — un échec de stockage porte son
                // diagnostic (URI de l'homonyme, pré-vol, insertion
                // refusée) ; sans lui, tout retour d'appareil réel restait
                // une devinette.
                binding.texteEchecDetails.isVisible = detailsTechniques(etat.erreur).isNotBlank()
                binding.texteEchecDetails.text = detailsTechniques(etat.erreur)
                binding.boutonCorriger.isVisible =
                    etat.erreur is AppError.Storage &&
                    etat.erreur.reason == AppError.StorageReason.AlreadyExists
            }

            EtatCreation.Inactif -> {
                Unit
            }
        }
    }

    /** Message compréhensible d'une erreur typée (section 12.3). */
    private fun messageErreur(erreur: AppError): String =
        when (erreur) {
            is AppError.Storage -> {
                when (erreur.reason) {
                    AppError.StorageReason.AlreadyExists -> getString(R.string.wizard_echec_collision)
                    AppError.StorageReason.NotFound -> getString(R.string.wizard_echec_introuvable)
                    else -> getString(R.string.wizard_echec_ecriture)
                }
            }

            is AppError.Validation -> {
                getString(R.string.wizard_echec_validation)
            }

            is AppError.Template -> {
                getString(R.string.wizard_echec_modele)
            }

            is AppError.Bootstrap -> {
                // Hors périmètre de la création de projet : les outils du
                // terminal ne participent pas à la génération des fichiers.
                getString(R.string.wizard_echec_inattendu)
            }

            is AppError.Tooling -> {
                // Hors périmètre de la création de projet : le tooling
                // Gradle ne participe pas à la génération des fichiers.
                getString(R.string.wizard_echec_inattendu)
            }

            is AppError.Unknown -> {
                getString(R.string.wizard_echec_inattendu)
            }
        }

    /** Détails techniques portés par l'erreur typée (v0.31.5). */
    private fun detailsTechniques(erreur: AppError): String =
        when (erreur) {
            is AppError.Storage -> erreur.details
            is AppError.Validation -> erreur.details
            is AppError.Template -> erreur.details
            is AppError.Bootstrap -> erreur.details
            is AppError.Tooling -> erreur.message
            is AppError.Unknown -> erreur.details
        }

    /** Copie les détails expurgés (erreurs typées — identifiants seulement). */
    private fun copierDetails() {
        val echec = wizard.etat.value.etatCreation as? EtatCreation.Echec ?: return
        val pressePapiers =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        pressePapiers.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.wizard_echec_copier), echec.erreur.toString()),
        )
        Toast.makeText(requireContext(), R.string.wizard_echec_copie_ok, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        adapteurEvenements = null
        super.onDestroyView()
    }
}

/**
 * Fragment de base des écrans hors étapes (création) : même ViewModel
 * partagé que les étapes, sans l'API d'étape.
 */
abstract class BaseFragmentEcran<VB : androidx.viewbinding.ViewBinding> : jo.codeide.core.ui.BaseFragment<VB>() {
    /** ViewModel partagé, scopé à l'hôte [NewProjectFragment]. */
    protected val wizard: WizardViewModel by viewModels({ requireParentFragment() })
}

/**
 * Liste des événements de progression (section 12.3) : chaque événement du
 * domaine devient une ligne libellée ; les lignes passées sont estompées.
 */
private class EvenementsAdapter :
    ListAdapter<CreationProgress, EvenementsAdapter.Support>(ComparaisonEvenements()) {
    /** Soumet les événements (l'événement courant est en gras). */
    fun soumettre(evenements: List<CreationProgress>) {
        submitList(evenements)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Support {
        val liaison =
            VueLigneEvenementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Support(liaison)
    }

    override fun onBindViewHolder(
        support: Support,
        position: Int,
    ) {
        val evenement = getItem(position)
        support.liaison.texteEvenement.text = libelle(support, evenement)
        support.liaison.iconeEtat.alpha =
            if (position == itemCount - 1) ALPHA_EVENEMENT_COURANT else ALPHA_EVENEMENT_PASSE
    }

    /** Libellé localisé d'un événement de progression. */
    private fun libelle(
        support: Support,
        evenement: CreationProgress,
    ): String =
        with(support.liaison.root.context) {
            when (evenement) {
                CreationProgress.Preparation -> {
                    getString(R.string.wizard_creation_preparation)
                }

                is CreationProgress.CreationDossierRacine -> {
                    getString(R.string.wizard_creation_dossier, evenement.nomProjet)
                }

                is CreationProgress.GenerationFichier -> {
                    getString(
                        R.string.wizard_creation_fichier,
                        evenement.index,
                        evenement.total,
                        evenement.cheminRelatif,
                    )
                }

                CreationProgress.Enregistrement -> {
                    getString(R.string.wizard_creation_enregistrement)
                }

                is CreationProgress.Termine -> {
                    getString(R.string.wizard_creation_termine)
                }
            }
        }

    /** Support de vue d'une ligne. */
    class Support(
        val liaison: VueLigneEvenementBinding,
    ) : RecyclerView.ViewHolder(liaison.root)

    /**
     * Diff par classe+position (les événements ne se répètent qu'à l'identique).
     *
     * Exemption lint ciblée (règle 16) : `CreationProgress` est une
     * interface scellée dont **toutes** les implémentations sont des
     * `data class`/`data object` — l'égalité structurelle `==` est donc
     * correcte ; le vérificateur `DiffUtilEquals` ne remonte simplement
     * pas jusqu'aux sous-types.
     */
    @Suppress("DiffUtilEquals")
    private class ComparaisonEvenements : DiffUtil.ItemCallback<CreationProgress>() {
        override fun areItemsTheSame(
            ancienne: CreationProgress,
            nouvelle: CreationProgress,
        ): Boolean = ancienne == nouvelle

        override fun areContentsTheSame(
            ancienne: CreationProgress,
            nouvelle: CreationProgress,
        ): Boolean = ancienne == nouvelle
    }

    private companion object {
        /** Opacité de la ligne de l'événement courant (pleine). */
        const val ALPHA_EVENEMENT_COURANT = 1f

        /** Opacité des lignes déjà passées (estompées). */
        const val ALPHA_EVENEMENT_PASSE = 0.4f
    }
}
