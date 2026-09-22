package jo.codeide.feature.newproject

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import jo.codeide.core.ui.SimpleTextWatcher
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.EtapeModeleBinding

/**
 * Étape 1 « Modèle » du wizard (section 12.3) : grille de **cartes
 * sélectionnables** — monogramme maison (pas de logo officiel, règle de
 * l'étape 9), nom, description courte, tags. Sélection unique, modèle
 * présélectionné au retour arrière.
 *
 * La barre de recherche n'apparaît que si le catalogue dépasse
 * [SEUIL_RECHERCHE] modèles — l'interface est prête pour la croissance sans
 * rien changer (section 12.3) ; l'état « aucun résultat » est prévu.
 */
class EtapeModeleFragment : EtapeFragment<EtapeModeleBinding>() {
    private var adapteur: ModelesAdapter? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EtapeModeleBinding = EtapeModeleBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: android.os.Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        adapteur =
            ModelesAdapter { id ->
                wizard.action(ActionWizard.ChoisirModele(id))
            }
        binding.grilleModeles.layoutManager = GridLayoutManager(requireContext(), NB_COLONNES)
        binding.grilleModeles.adapter = adapteur

        // Recherche : uniquement utile au-delà du seuil — masquée en
        // Phase 1 (2 modèles), prête sans modification pour la suite.
        binding.saisieRecherche.addTextChangedListener(
            object : SimpleTextWatcher() {
                override fun onTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    avant: Int,
                    nombre: Int,
                ) {
                    adapteur?.filtrer(texte?.toString().orEmpty())
                    mettreAJourEtatVide()
                }
            },
        )

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            adapteur?.soumettre(etat.modeles, etat.templateId)
            binding.recherche.isVisible = etat.modeles.size > SEUIL_RECHERCHE

            binding.progression.isVisible = etat.chargementCatalogue
            binding.erreurCatalogue.isVisible = etat.erreurCatalogue
            binding.boutonReessayer.isVisible = etat.erreurCatalogue
            binding.boutonReessayer.setOnClickListener {
                wizard.action(ActionWizard.ReessayerCatalogue)
            }
            binding.grilleModeles.isVisible = !etat.chargementCatalogue && !etat.erreurCatalogue
            mettreAJourEtatVide()
        }
    }

    /** Bascule l'état « aucun résultat » (visible seulement en recherche). */
    private fun mettreAJourEtatVide() {
        val vide = adapteur?.estVide() ?: true
        val etat = wizard.etat.value
        binding.aucunResultat.isVisible =
            !etat.chargementCatalogue && !etat.erreurCatalogue && vide
        if (vide) {
            binding.aucunResultat.text =
                getString(
                    R.string.wizard_aucun_resultat,
                    binding.saisieRecherche.text
                        ?.toString()
                        .orEmpty(),
                )
        }
    }

    override fun onDestroyView() {
        adapteur = null
        super.onDestroyView()
    }

    private companion object {
        /** Colonnes de la grille de cartes (2 sur téléphone). */
        const val NB_COLONNES = 2

        /** Au-delà, la recherche et les catégories s'affichent (section 12.3). */
        const val SEUIL_RECHERCHE = 4
    }
}
