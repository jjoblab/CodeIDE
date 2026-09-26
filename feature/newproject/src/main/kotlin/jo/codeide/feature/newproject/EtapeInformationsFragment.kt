package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import jo.codeide.core.domain.ValidationDossier
import jo.codeide.core.domain.VerificationCible
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.TemplateSection
import jo.codeide.core.ui.SimpleTextWatcher
import jo.codeide.core.ui.applyImeBottomInset
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.EtapeInformationsBinding

/**
 * Étape 3 « Informations et emplacement » (section 12.3) : nom du projet,
 * description avec compteur, paramètres de section `INFORMATION` rendus
 * dynamiquement (package dérivé, coordonnées selon la visibilité), et
 * **carte d'emplacement** — dossier de travail par défaut, changement
 * « pour cette création uniquement » (SAF), aperçu du chemin final et
 * **vérifications asynchrones avec délai** (permission, joignabilité,
 * collision de nom, indicateur en cours).
 *
 * Actions IME « Suivant »/« OK », focus automatique sur le premier champ.
 */
class EtapeInformationsFragment : EtapeFragment<EtapeInformationsBinding>() {
    private var rendu: RenduParametres? = null

    /** Sélecteur SAF « Changer de dossier » (pour cette création uniquement). */
    private val selecteurDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { wizard.action(ActionWizard.ChangerEmplacement(it.toString())) }
        }

    /** Restaure la saisie une seule fois (puis la vue gère son texte). */
    private var premierRendu = true

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EtapeInformationsBinding = EtapeInformationsBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Le clavier ne doit jamais passer sous la carte d'emplacement.
        binding.defilementInformations.applyImeBottomInset()

        rendu =
            RenduParametres(
                binding.conteneurParametres,
                object : RenduParametres.Ecouteur {
                    override fun saisirTexte(
                        parametreId: String,
                        valeur: String,
                    ) {
                        wizard.action(ActionWizard.SaisirTexte(parametreId, valeur))
                    }

                    override fun choisirValeur(
                        parametreId: String,
                        valeur: String,
                    ) {
                        wizard.action(ActionWizard.ChoisirValeur(parametreId, valeur))
                    }

                    override fun resynchroniser(parametreId: String) {
                        wizard.action(ActionWizard.Resynchroniser(parametreId))
                    }
                },
            )

        binding.saisieNom.addTextChangedListener(
            object : SimpleTextWatcher() {
                override fun onTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    avant: Int,
                    nombre: Int,
                ) {
                    wizard.action(ActionWizard.SaisirNom(texte?.toString().orEmpty()))
                }
            },
        )
        // Action IME « Suivant » : passe à la description.
        binding.saisieNom.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.saisieDescription.requestFocus()
            }
            false
        }

        binding.saisieDescription.addTextChangedListener(
            object : SimpleTextWatcher() {
                override fun onTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    avant: Int,
                    nombre: Int,
                ) {
                    wizard.action(ActionWizard.SaisirDescription(texte?.toString().orEmpty()))
                }
            },
        )

        binding.boutonChangerDossier.setOnClickListener { selecteurDossier.launch(null) }

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            rendreNom(etat)
            rendreDescription(etat)
            if (etat.evaluation != null) {
                binding.conteneurParametres.isVisible = true
                rendu?.rendre(etat.parametresSection(TemplateSection.INFORMATION))
            } else {
                binding.conteneurParametres.isVisible = false
            }
            rendreEmplacement(etat)
            rendreVerification(etat)
        }
    }

    /** Rend le nom : restauration, erreur inline localisée, compteur. */
    private fun rendreNom(etat: EtatWizard) {
        if (premierRendu) {
            binding.saisieNom.setText(etat.nomProjet)
            binding.saisieNom.requestFocus()
            premierRendu = false
        } else if (binding.saisieNom.text.toString() != etat.nomProjet) {
            val curseur = binding.saisieNom.selectionStart
            binding.saisieNom.setText(etat.nomProjet)
            binding.saisieNom.setSelection(curseur.coerceIn(0, etat.nomProjet.length))
        }
        binding.champNom.counterMaxLength = LONGUEUR_NOM_MAX
        binding.champNom.isCounterEnabled = true
        binding.champNom.error = etat.raisonNom?.let(::messageNom)
    }

    /** Rend la description : restauration et compteur. */
    private fun rendreDescription(etat: EtatWizard) {
        if (premierRendu || binding.saisieDescription.text.toString() != etat.description) {
            binding.saisieDescription.setText(etat.description)
        }
        binding.champDescription.counterMaxLength = LONGUEUR_DESCRIPTION_MAX
        binding.champDescription.isCounterEnabled = true
    }

    /** Rend la carte d'emplacement : dossier, aperçu, erreur de choix. */
    private fun rendreEmplacement(etat: EtatWizard) {
        val emplacement = etat.emplacement
        val couleur =
            if (emplacement == null) {
                jo.codeide.core.ui.R.color.codeide_error
            } else {
                jo.codeide.core.ui.R.color.codeide_on_surface
            }
        binding.texteEmplacement.text = emplacement?.displayPath ?: getString(R.string.wizard_emplacement_absent)
        binding.texteEmplacement.setTextColor(
            ContextCompat.getColor(binding.texteEmplacement.context, couleur),
        )

        // Aperçu lisible du chemin final « …/<NomDuProjet> ».
        binding.texteApercuChemin.text =
            if (etat.nomProjet.isBlank()) {
                getString(R.string.wizard_emplacement_apercu_vide)
            } else {
                getString(R.string.wizard_emplacement_apercu, etat.nomProjet.trim())
            }

        when (etat.erreurEmplacement) {
            is ValidationDossier.Refuse -> {
                binding.texteErreurEmplacement.isVisible = true
                binding.texteErreurEmplacement.text = getString(R.string.wizard_emplacement_refuse)
            }

            is ValidationDossier.Erreur -> {
                binding.texteErreurEmplacement.isVisible = true
                binding.texteErreurEmplacement.text = getString(R.string.wizard_emplacement_erreur)
            }

            else -> {
                binding.texteErreurEmplacement.isVisible = false
            }
        }
    }

    /** Rend l'état de la vérification asynchrone de la cible. */
    private fun rendreVerification(etat: EtatWizard) {
        binding.progressionVerification.isVisible = etat.verificationEnCours
        when (etat.verificationCible) {
            is VerificationCible.Valide -> {
                binding.texteVerification.isVisible = true
                binding.texteVerification.text = getString(R.string.wizard_verification_ok)
            }

            is VerificationCible.NomDejaPris -> {
                binding.texteVerification.isVisible = true
                binding.texteVerification.text = getString(R.string.wizard_verification_collision)
            }

            is VerificationCible.EmplacementInaccessible -> {
                binding.texteVerification.isVisible = true
                binding.texteVerification.text = getString(R.string.wizard_verification_inaccessible)
            }

            is VerificationCible.Erreur -> {
                binding.texteVerification.isVisible = true
                binding.texteVerification.text = getString(R.string.wizard_verification_erreur)
            }

            null -> {
                binding.texteVerification.isVisible =
                    etat.emplacement != null && etat.nomProjet.isNotBlank()
                binding.texteVerification.text = ""
            }
        }
    }

    /** Message localisé de la raison d'un nom invalide. */
    private fun messageNom(raison: RaisonValidation): String =
        when (raison) {
            is RaisonValidation.LongueurNom -> {
                getString(R.string.wizard_erreur_nom_longueur)
            }

            is RaisonValidation.CaractereInterditNom -> {
                getString(R.string.wizard_erreur_nom_caractere, raison.fautif.toString())
            }

            is RaisonValidation.PointsFictifsNom -> {
                getString(R.string.wizard_erreur_nom_points)
            }

            is RaisonValidation.FinNomInterdite -> {
                getString(R.string.wizard_erreur_nom_fin)
            }

            is RaisonValidation.NomReserveWindows -> {
                getString(R.string.wizard_erreur_nom_reserves)
            }

            else -> {
                getString(R.string.wizard_erreur_nom_generique)
            }
        }

    override fun onDestroyView() {
        rendu = null
        super.onDestroyView()
    }

    private companion object {
        /** Longueur maximale du nom (règle `project-name`, section 12.3). */
        const val LONGUEUR_NOM_MAX = 64

        /** Longueur maximale de la description (champ libre, compteur). */
        const val LONGUEUR_DESCRIPTION_MAX = 200
    }
}
