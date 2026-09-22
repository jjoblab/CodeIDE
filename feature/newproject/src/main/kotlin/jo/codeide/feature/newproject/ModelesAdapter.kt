package jo.codeide.feature.newproject

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.model.TemplateId
import jo.codeide.feature.newproject.databinding.ItemModeleBinding
import java.text.Normalizer

/** Modèle prêt à afficher, avec son état de sélection (étape 1). */
data class ModeleAffiche(
    val modele: TemplateSummaryUi,
    val selectionne: Boolean,
)

/**
 * Adaptateur de la grille de modèles (étape 1, section 12.3) : cartes
 * sélectionnables à choix unique — le trio (liste, sélection, requête)
 * compose le rendu ; un même modèle change d'apparence quand la sélection
 * bouge ([DiffUtil] par valeur d'affichage).
 *
 * La recherche filtre sur le nom, la description et les tags, sans casse
 * ni accents (même normalisation NFD que l'accueil, étape 7).
 */
class ModelesAdapter(
    private val choix: (TemplateId) -> Unit,
) : ListAdapter<ModeleAffiche, ModelesAdapter.Contenant>(Ecart) {
    /** Requête de recherche courante (vide = tout). */
    private var requete = ""

    /** La sélection courante, pour rafraîchir les cartes existantes. */
    private var selection: TemplateId? = null

    /** Soumet le catalogue et la sélection courante. */
    fun soumettre(
        modeles: List<TemplateSummaryUi>,
        selection: TemplateId?,
    ) {
        this.selection = selection
        submitList(modeles.map { ModeleAffiche(it, it.id == selection) })
    }

    /** Filtre le catalogue (nom, description, tags — sans casse ni accents). */
    fun filtrer(nouvelleRequete: String) {
        if (nouvelleRequete == requete) return
        requete = nouvelleRequete
        refresh()
    }

    /** Le rendu courant est-il vide (état « aucun résultat ») ? */
    fun estVide(): Boolean = itemCount == 0

    private fun refresh() {
        val cible = currentList.map { it.modele }
        submitList(
            cible
                .filter { modele ->
                    requete.isBlank() || modeleTexte(modele).contains(sansAccents(requete))
                }.map { ModeleAffiche(it, it.id == selection) },
        )
    }

    /** Texte de recherche d'un modèle (nom + description + tags). */
    private fun modeleTexte(modele: TemplateSummaryUi): String =
        sansAccents(
            buildString {
                append(modele.nom)
                append(' ')
                append(modele.description)
                append(' ')
                modele.tags.forEach {
                    append(it)
                    append(' ')
                }
            },
        )

    /** Normalise pour une recherche tolérante (casse et accents). */
    private fun sansAccents(texte: String): String =
        Normalizer
            .normalize(texte, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}"), "")
            .lowercase()

    /** Contenant d'une carte de modèle. */
    class Contenant(
        val liaison: ItemModeleBinding,
    ) : RecyclerView.ViewHolder(liaison.root)

    /** Écart structurel et visuel (la sélection compte). */
    private object Ecart : DiffUtil.ItemCallback<ModeleAffiche>() {
        override fun areItemsTheSame(
            ancien: ModeleAffiche,
            nouveau: ModeleAffiche,
        ): Boolean = ancien.modele.id == nouveau.modele.id

        override fun areContentsTheSame(
            ancien: ModeleAffiche,
            nouveau: ModeleAffiche,
        ): Boolean = ancien == nouveau
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Contenant {
        val liaison =
            ItemModeleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Contenant(liaison)
    }

    override fun onBindViewHolder(
        contenant: Contenant,
        position: Int,
    ) {
        val affiche = getItem(position)
        val modele = affiche.modele
        val liaison = contenant.liaison

        liaison.carteModele.isChecked = affiche.selectionne
        liaison.pastilleModele.text = modele.monogramme
        liaison.nomModele.text = modele.nom
        liaison.descriptionModele.text = modele.description
        liaison.tagsModele.isVisible = modele.tags.isNotEmpty()
        liaison.tagsModele.text = modele.tags.joinToString(SEPARATEUR_TAGS)
        liaison.root.setOnClickListener { choix(modele.id) }
        liaison.root.contentDescription =
            if (affiche.selectionne) {
                liaison.root.context.getString(
                    R.string.wizard_modele_selectionne,
                    modele.nom,
                )
            } else {
                modele.nom
            }
    }

    private companion object {
        /** Séparateur des tags sur la carte (« Kotlin · JVM · Gradle »). */
        const val SEPARATEUR_TAGS = " · "
    }
}
