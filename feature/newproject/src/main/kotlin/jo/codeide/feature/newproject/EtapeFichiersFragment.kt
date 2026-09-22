package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import jo.codeide.core.model.License
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.EtapeFichiersBinding

/**
 * Étape 4 « Fichiers » (section 12.3) : interrupteurs des fichiers
 * optionnels (README, .gitignore, .editorconfig), licence pré-remplie des
 * Paramètres (auteur et année affichés — MIT et BSD les intègrent), langue
 * du contenu généré (boutons segmentés Français / English).
 *
 * Aucune validation bloquante : toute combinaison est légitime — le bouton
 * Suivant reste donc actif ; l'aperçu de l'arborescence (étape suivante)
 * reflète immédiatement ces choix.
 */
class EtapeFichiersFragment : EtapeFragment<EtapeFichiersBinding>() {
    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EtapeFichiersBinding = EtapeFichiersBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.interrupteurReadme.setOnCheckedChangeListener { _, inclus ->
            if (!renduEnCours) wizard.action(ActionWizard.BasculerFichier(FichierOptionnel.README, inclus))
        }
        binding.interrupteurGitignore.setOnCheckedChangeListener { _, inclus ->
            if (!renduEnCours) wizard.action(ActionWizard.BasculerFichier(FichierOptionnel.GITIGNORE, inclus))
        }
        binding.interrupteurEditorconfig.setOnCheckedChangeListener { _, inclus ->
            if (!renduEnCours) wizard.action(ActionWizard.BasculerFichier(FichierOptionnel.EDITORCONFIG, inclus))
        }

        val licences = License.entries
        val libelles = licences.map(::libelleLicence)
        binding.saisieLicence.setAdapter(
            ArrayAdapter(binding.root.context, android.R.layout.simple_list_item_1, libelles),
        )
        binding.saisieLicence.setOnItemClickListener { _, _, position, _ ->
            if (!renduEnCours) wizard.action(ActionWizard.ChoisirLicence(licences[position]))
        }

        binding.boutonLangueFrancais.setOnClickListener {
            wizard.action(ActionWizard.ChoisirLangueContenu(LANGUE_FRANCAIS))
        }
        binding.boutonLangueAnglais.setOnClickListener {
            wizard.action(ActionWizard.ChoisirLangueContenu(LANGUE_ANGLAIS))
        }

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu idempotent des options (la vue garde le dernier état rendu). */
    private fun rendre(etat: EtatWizard) {
        renduEnCours = true

        if (binding.interrupteurReadme.isChecked != etat.options.includeReadme) {
            binding.interrupteurReadme.isChecked = etat.options.includeReadme
        }
        if (binding.interrupteurGitignore.isChecked != etat.options.includeGitignore) {
            binding.interrupteurGitignore.isChecked = etat.options.includeGitignore
        }
        if (binding.interrupteurEditorconfig.isChecked != etat.options.includeEditorconfig) {
            binding.interrupteurEditorconfig.isChecked = etat.options.includeEditorconfig
        }

        val libelle = libelleLicence(etat.options.license)
        if (binding.saisieLicence.text.toString() != libelle) {
            binding.saisieLicence.setText(libelle, false)
        }

        // Auteur et année pré-remplis des Paramètres (section 12.3) —
        // information, jamais une saisie : MIT et BSD les consomment.
        binding.texteAuteurLicence.text =
            getString(R.string.wizard_fichiers_licence_auteur, etat.auteur, etat.annee)

        binding.boutonLangueFrancais.isChecked = etat.options.contentLanguage == LANGUE_FRANCAIS
        binding.boutonLangueAnglais.isChecked = etat.options.contentLanguage == LANGUE_ANGLAIS

        renduEnCours = false
    }

    /** Libellé localisé d'une licence (liste déroulante et récapitulatif). */
    private fun libelleLicence(licence: License): String =
        when (licence) {
            License.NONE -> getString(R.string.wizard_licence_aucune)
            License.MIT -> getString(R.string.wizard_licence_mit)
            License.APACHE_2_0 -> getString(R.string.wizard_licence_apache)
            License.GPL_3_0 -> getString(R.string.wizard_licence_gpl)
            License.BSD_3_CLAUSE -> getString(R.string.wizard_licence_bsd)
        }

    private companion object {
        /** Langues du contenu (codes du moteur, section 11). */
        const val LANGUE_FRANCAIS = "fr"
        const val LANGUE_ANGLAIS = "en"
    }
}
