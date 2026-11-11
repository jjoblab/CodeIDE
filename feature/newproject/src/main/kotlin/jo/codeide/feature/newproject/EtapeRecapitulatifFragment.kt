package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jo.codeide.core.model.EtapeParcours
import jo.codeide.core.model.License
import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.DialogueRenommageBinding
import jo.codeide.feature.newproject.databinding.EtapeRecapitulatifBinding
import jo.codeide.feature.newproject.databinding.VueLigneArborescenceBinding
import jo.codeide.feature.newproject.databinding.VueLigneRecapEtapeBinding

/**
 * Étape « Récapitulatif » (section 12.3, **fidèle au parcours** — ADR 0155) :
 * le résumé s'organise selon les **étapes réellement parcourues** — une
 * ligne par étape avec bouton « Modifier » (retour à l'arrière uniquement),
 * bibliothèques et choix listés, **avertissements restants** affichés —
 * puis **aperçu de l'arborescence générée** (dry-run du plan — dossiers
 * repliables, nombre de fichiers) : ce qui est planifié est ce qui sera
 * écrit, à l'octet près.
 *
 * Phase 4 (ADR 0077) : chaque nœud de l'aperçu est **renommable** avant
 * création (crayon en bout de ligne, masqué pour les métadonnées
 * `.codeide`) — le plan se recalcule, l'arbre suit.
 */
@Suppress("TooManyFunctions") // Rendu fidèle au parcours : résumés par famille (règle 16).
class EtapeRecapitulatifFragment : EtapeFragment<EtapeRecapitulatifBinding>() {
    private var adapteurArbre: ArborescenceAdapter? = null

    /** Dernier arbre rendu — référence du dialogue de renommage. */
    private var dernierArbre: Arborescence? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EtapeRecapitulatifBinding = EtapeRecapitulatifBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val arbre = ArborescenceAdapter { noeud -> ouvrirDialogueRenommage(noeud) }
        adapteurArbre = arbre
        binding.listeArborescence.adapter = arbre

        binding.boutonReessayerPlan.setOnClickListener { wizard.action(ActionWizard.ReessayerPlan) }

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu du résumé fidèle au parcours, des avertissements et de l'arbre. */
    private fun rendre(etat: EtatWizard) {
        rendreEtapesParcourues(etat)
        rendreAvertissements(etat)

        binding.progressionPlan.isVisible = etat.chargementPlan
        binding.texteErreurPlan.isVisible = etat.erreurPlan
        binding.boutonReessayerPlan.isVisible = etat.erreurPlan

        val arborescence = etat.plan?.let(Arborescence::depuisPlan)
        dernierArbre = arborescence
        if (arborescence != null) {
            binding.texteTitreArborescence.text =
                resources.getQuantityString(
                    R.plurals.wizard_recap_arborescence,
                    arborescence.nombreFichiers,
                    arborescence.nombreFichiers,
                )
            adapteurArbre?.soumettre(arborescence)
            binding.listeArborescence.isVisible = true
        } else {
            binding.texteTitreArborescence.text = getString(R.string.wizard_recap_arborescence_titre)
            binding.listeArborescence.isVisible = false
        }
    }

    /**
     * Une ligne par étape parcourue (toutes sauf le récapitulatif) : titre,
     * résumé compact, bouton « Modifier » — l'ordre est celui du parcours,
     * une étape disparue n'y figure pas (fidélité, ADR 0155).
     */
    private fun rendreEtapesParcourues(etat: EtatWizard) {
        binding.conteneurEtapesRecap.removeAllViews()
        etat.parcours.dropLast(1).forEach { etape ->
            val ligne = VueLigneRecapEtapeBinding.inflate(layoutInflater, binding.conteneurEtapesRecap, false)
            ligne.titreEtapeRecap.text = etat.titreEtape(requireContext(), etape)
            ligne.texteResumeEtape.text = resumeEtape(etat, etape)
            ligne.boutonModifierEtape.setOnClickListener {
                wizard.action(ActionWizard.AllerEtape(EtapeId.deCle(etape.id)))
            }
            binding.conteneurEtapesRecap.addView(ligne.root)
        }
    }

    /** Avertissements (et informations) restants ; une ERREUR bloquerait « Créer ». */
    private fun rendreAvertissements(etat: EtatWizard) {
        MessagesRegles.rendre(binding.conteneurAvertissements, etat.evaluation?.messages.orEmpty(), layoutInflater)
    }

    /**
     * Résumé compact d'une étape parcourue : modèle nommé, étape de modèle
     * en libellés (choix résolus, interrupteurs actifs), identité nom +
     * paramètres + emplacement, fichiers optionnels retenus + licence +
     * langue.
     */
    private fun resumeEtape(etat: EtatWizard, etape: EtapeParcours): String = when (etape.id) {
        EtapeId.Modele.cle ->
            etat.modeles.firstOrNull { it.id == etat.templateId }?.nom
                ?: getString(R.string.wizard_recap_valeur_absente)

        EtapeId.Identite.cle -> resumeIdentite(etat, etape)

        EtapeId.Fichiers.cle -> resumeFichiers(etat)

        else -> resumeEtapeModele(etape)
    }

    /** Résumé d'une étape de modèle : libellés lisibles, jamais d'identifiants bruts. */
    private fun resumeEtapeModele(etape: EtapeParcours): String {
        val morceaux =
            etape.parametres
                .filter { it.visible }
                .map { parametre ->
                    when (parametre.type) {
                        TemplateParameterType.BOOLEAN ->
                            if (parametre.effectiveValue == "true") parametre.label else null

                        TemplateParameterType.CHOICE -> {
                            val libelle =
                                parametre.choix
                                    .firstOrNull { it.valeur == parametre.effectiveValue }
                                    ?.libelle
                                    ?: parametre.effectiveValue
                            "${parametre.label} : $libelle"
                        }

                        TemplateParameterType.TEXT -> {
                            if (parametre.effectiveValue.isBlank()) {
                                null
                            } else {
                                "${parametre.label} : ${parametre.effectiveValue}"
                            }
                        }
                    }
                }.filterNotNull()
        return morceaux.joinToString(" · ").ifBlank { getString(R.string.wizard_recap_valeur_absente) }
    }

    /** Résumé Identité : nom, paramètres visibles et emplacement. */
    private fun resumeIdentite(etat: EtatWizard, etape: EtapeParcours): String {
        val lignes =
            buildList {
                add(etat.nomProjet.trim().ifBlank { getString(R.string.wizard_recap_valeur_absente) })
                etape.parametres.filter { it.visible }.forEach { parametre ->
                    add("${parametre.label} : ${parametre.effectiveValue}")
                }
                add(
                    etat.emplacement
                        ?.let { getString(R.string.wizard_recap_emplacement, it.displayPath) }
                        ?: getString(R.string.wizard_emplacement_absent),
                )
            }
        return lignes.joinToString("\n")
    }

    /** Résumé Fichiers : fichiers optionnels déclarés et retenus, licence, langue. */
    private fun resumeFichiers(etat: EtatWizard): String {
        val groupes = etat.evaluation?.groupesFichiers.orEmpty()
        val fichiers =
            buildList {
                if (jo.codeide.core.model.TemplateFileGroup.README in groupes && etat.options.includeReadme) {
                    add("README.md")
                }
                if (jo.codeide.core.model.TemplateFileGroup.GITIGNORE in groupes && etat.options.includeGitignore) {
                    add(".gitignore")
                }
                if (
                    jo.codeide.core.model.TemplateFileGroup.EDITORCONFIG in groupes &&
                    etat.options.includeEditorconfig
                ) {
                    add(".editorconfig")
                }
            }.joinToString(" · ")

        return buildList {
            add(fichiers.ifBlank { getString(R.string.wizard_recap_aucun_fichier_optionnel) })
            add(getString(R.string.wizard_recap_licence, licenceCourte(etat.options.license)))
            add(getString(R.string.wizard_recap_langue_contenu, etat.options.contentLanguage))
        }.joinToString("\n")
    }

    /** Code court d'une licence (résumé compact). */
    private fun licenceCourte(licence: License): String = when (licence) {
        License.NONE -> getString(R.string.wizard_licence_aucune)
        License.MIT -> "MIT"
        License.APACHE_2_0 -> "Apache-2.0"
        License.GPL_3_0 -> "GPL-3.0"
        License.BSD_3_CLAUSE -> "BSD-3-Clause"
    }

    /**
     * Dialogue de renommage d'un nœud (ADR 0077) : validation locale
     * immédiate (vide, séparateur, parent, doublon de frère) — le domaine
     * revalide tout au replan, une collision exotique annule le renommage.
     */
    private fun ouvrirDialogueRenommage(noeud: Noeud) {
        val arbre = dernierArbre ?: return
        val liaison = DialogueRenommageBinding.inflate(layoutInflater)
        liaison.saisieRenommage.setText(noeud.nom)
        val dialogue =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.wizard_renommer_titre, noeud.nom))
                .setView(liaison.root)
                .setNegativeButton(R.string.wizard_renommer_annuler, null)
                .setPositiveButton(R.string.wizard_renommer_bouton, null)
                .create()
        // Positif sur mesure : le dialogue ne se referme que si le nom est valide.
        dialogue.setOnShowListener {
            dialogue.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nom =
                    liaison.saisieRenommage.text
                        ?.toString()
                        .orEmpty()
                when (val erreur = arbre.validerRenommage(noeud.cheminOriginal, nom)) {
                    null -> {
                        dialogue.dismiss()
                        wizard.action(ActionWizard.RenommerChemin(noeud.cheminOriginal, nom))
                    }

                    else -> {
                        liaison.champRenommage.error =
                            getString(
                                when (erreur) {
                                    ErreurRenommage.VIDE -> R.string.wizard_renommer_erreur_vide
                                    ErreurRenommage.SEPARATEUR -> R.string.wizard_renommer_erreur_separateur
                                    ErreurRenommage.PARENT -> R.string.wizard_renommer_erreur_parent
                                    ErreurRenommage.EXISTE -> R.string.wizard_renommer_erreur_existe
                                },
                            )
                    }
                }
            }
        }
        dialogue.show()
    }

    override fun onDestroyView() {
        dernierArbre = null
        adapteurArbre = null
        super.onDestroyView()
    }
}
