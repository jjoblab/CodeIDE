package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.model.License
import jo.codeide.core.model.TemplateSection
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.EtapeRecapitulatifBinding
import jo.codeide.feature.newproject.databinding.VueLigneArborescenceBinding

/**
 * Étape 5 « Récapitulatif » (section 12.3) : résumé lisible par section
 * (modèle, configuration, informations, fichiers) avec bouton « Modifier »
 * qui ramène à l'étape concernée, puis **aperçu de l'arborescence
 * générée** (dry-run du plan — dossiers repliables, nombre de fichiers) :
 * ce qui est planifié est ce qui sera écrit, à l'octet près.
 */
class EtapeRecapitulatifFragment : EtapeFragment<EtapeRecapitulatifBinding>() {
    private var adapteurArbre: ArborescenceAdapter? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EtapeRecapitulatifBinding = EtapeRecapitulatifBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val arbre = ArborescenceAdapter()
        adapteurArbre = arbre
        binding.listeArborescence.adapter = arbre

        binding.boutonModifierModele.setOnClickListener { aller(EtapeId.MODELE) }
        binding.boutonModifierConfiguration.setOnClickListener { aller(EtapeId.CONFIGURATION) }
        binding.boutonModifierInformations.setOnClickListener { aller(EtapeId.INFORMATIONS) }
        binding.boutonModifierFichiers.setOnClickListener { aller(EtapeId.FICHIERS) }
        binding.boutonReessayerPlan.setOnClickListener { wizard.action(ActionWizard.ReessayerPlan) }

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Retour direct à une étape (le ViewModel ne garde que l'arrière). */
    private fun aller(etape: EtapeId) {
        wizard.action(ActionWizard.AllerEtape(etape))
    }

    /** Rendu du résumé et de l'arborescence (idempotent). */
    private fun rendre(etat: EtatWizard) {
        val modele = etat.modeles.firstOrNull { it.id == etat.templateId }
        binding.texteResumeModele.text = modele?.nom ?: getString(R.string.wizard_recap_valeur_absente)

        binding.texteResumeConfiguration.text = resumeConfiguration(etat)
        binding.texteResumeInformations.text = resumeInformations(etat)
        binding.texteResumeFichiers.text = resumeFichiers(etat)

        binding.progressionPlan.isVisible = etat.chargementPlan
        binding.texteErreurPlan.isVisible = etat.erreurPlan
        binding.boutonReessayerPlan.isVisible = etat.erreurPlan

        val arborescence = etat.plan?.let(Arborescence::depuisPlan)
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

    /** Résumé Configuration : « Application · Gradle · JDK 21 · Wrapper ». */
    private fun resumeConfiguration(etat: EtatWizard): String =
        etat
            .parametresSection(TemplateSection.CONFIGURATION)
            .joinToString(" · ") { parametre -> parametre.label }
            .ifBlank { getString(R.string.wizard_recap_valeur_absente) }

    /** Résumé Informations : nom, paramètres visibles et emplacement. */
    private fun resumeInformations(etat: EtatWizard): String {
        val lignes =
            buildList {
                add(etat.nomProjet.trim().ifBlank { getString(R.string.wizard_recap_valeur_absente) })
                etat.parametresSection(TemplateSection.INFORMATION).forEach { parametre ->
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

    /** Résumé Fichiers : fichiers optionnels, licence, langue du contenu. */
    private fun resumeFichiers(etat: EtatWizard): String {
        val fichiers =
            buildList {
                if (etat.options.includeReadme) add("README.md")
                if (etat.options.includeGitignore) add(".gitignore")
                if (etat.options.includeEditorconfig) add(".editorconfig")
            }.joinToString(" · ")

        return buildList {
            add(fichiers.ifBlank { getString(R.string.wizard_recap_aucun_fichier_optionnel) })
            add(getString(R.string.wizard_recap_licence, licenceCourte(etat.options.license)))
            add(getString(R.string.wizard_recap_langue_contenu, etat.options.contentLanguage))
        }.joinToString("\n")
    }

    /** Code court d'une licence (résumé compact). */
    private fun licenceCourte(licence: License): String =
        when (licence) {
            License.NONE -> getString(R.string.wizard_licence_aucune)
            License.MIT -> "MIT"
            License.APACHE_2_0 -> "Apache-2.0"
            License.GPL_3_0 -> "GPL-3.0"
            License.BSD_3_CLAUSE -> "BSD-3-Clause"
        }

    override fun onDestroyView() {
        adapteurArbre = null
        super.onDestroyView()
    }
}

/**
 * Adaptateur de l'arborescence prévue (section 12.3) : lignes aplaties du
 * plan, dossiers repliables par un toucher (contenu décrit pour TalkBack).
 */
private class ArborescenceAdapter :
    ListAdapter<ArborescenceAdapter.LigneArbre, ArborescenceAdapter.Support>(ComparaisonLignes()) {
    /** Dossiers repliés (chemin complet). */
    private val replies = mutableSetOf<String>()

    /** Dernier arbre soumis — l'aplatissement redérive de l'état replié. */
    private var dernierArbre: Arborescence? = null

    /** Une ligne affichable : profondeur, nœud, chemin complet (repli). */
    data class LigneArbre(
        val profondeur: Int,
        val noeud: Noeud,
        val chemin: String,
    )

    /** Soumet un arbre : aplatit selon l'état replié courant. */
    fun soumettre(arbre: Arborescence) {
        dernierArbre = arbre
        rafraichir()
    }

    private fun rafraichir() {
        val arbre = dernierArbre ?: return
        val lignes = mutableListOf<LigneArbre>()
        aplatir(arbre.racine, 0, "", lignes, estRacine = true)
        submitList(lignes)
    }

    /** Parcours préfixe : la racine est masquée, un replié cache ses enfants. */
    private fun aplatir(
        dossier: Noeud.Dossier,
        profondeur: Int,
        prefixe: String,
        sortie: MutableList<LigneArbre>,
        estRacine: Boolean,
    ) {
        dossier.enfantsTries.forEach { noeud ->
            val chemin = if (prefixe.isEmpty()) noeud.nom else "$prefixe/${noeud.nom}"
            if (!estRacine) sortie += LigneArbre(profondeur, noeud, chemin)
            if (noeud is Noeud.Dossier && chemin !in replies) {
                aplatir(noeud, if (estRacine) 0 else profondeur + 1, chemin, sortie, estRacine = false)
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Support {
        val liaison =
            VueLigneArborescenceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Support(liaison)
    }

    override fun onBindViewHolder(
        support: Support,
        position: Int,
    ) {
        val ligne = getItem(position)
        val densite = support.liaison.root.resources.displayMetrics.density
        support.liaison.espaceIndentation.layoutParams.width =
            (INDENTATION_DP * densite).toInt() * ligne.profondeur.coerceAtMost(PROFONDEUR_MAX)
        support.liaison.espaceIndentation.requestLayout()

        val dossier = ligne.noeud as? Noeud.Dossier
        support.liaison.iconeDossier.isVisible = dossier != null
        support.liaison.texteNoeud.text = ligne.noeud.nom

        if (dossier == null) {
            support.liaison.root.isClickable = false
            support.liaison.root.contentDescription = null
        } else {
            support.liaison.root.isClickable = true
            support.liaison.root.setOnClickListener {
                if (ligne.chemin in replies) replies.remove(ligne.chemin) else replies.add(ligne.chemin)
                rafraichir()
            }
            val replie = ligne.chemin in replies
            support.liaison.root.contentDescription =
                support.liaison.root.context.getString(
                    if (replie) R.string.wizard_arbre_deplier else R.string.wizard_arbre_replier,
                    ligne.noeud.nom,
                )
        }
    }

    /** Support de vue d'une ligne. */
    class Support(
        val liaison: VueLigneArborescenceBinding,
    ) : RecyclerView.ViewHolder(liaison.root)

    /** Diff par chemin (identité) puis par contenu (nœud et profondeur). */
    private class ComparaisonLignes : DiffUtil.ItemCallback<LigneArbre>() {
        override fun areItemsTheSame(
            ancienne: LigneArbre,
            nouvelle: LigneArbre,
        ): Boolean = ancienne.chemin == nouvelle.chemin

        override fun areContentsTheSame(
            ancienne: LigneArbre,
            nouvelle: LigneArbre,
        ): Boolean = ancienne == nouvelle
    }

    private companion object {
        /** Indentation par niveau de profondeur (dp → px au rendu). */
        const val INDENTATION_DP = 16

        /** Profondeur maximale affichée avant saturation (lisibilité). */
        const val PROFONDEUR_MAX = 6
    }
}
