package jo.codeide.feature.newproject

import jo.codeide.core.model.TemplatePlan

/**
 * Arborescence prévisible du récapitulatif (section 12.3) : transformation
 * pure des chemins du [TemplatePlan] en nœuds affichables — dossiers
 * repliables, fichiers en feuilles, ordre lexicographique stable avec les
 * dossiers d'abord.
 *
 * Le calcul est déterministe : deux plans égaux produisent des arbres égaux
 * (tests sans horloge réelle, section 12.5).
 *
 * @property racine dossier racine (nom vide, jamais affiché tel quel).
 */
data class Arborescence(
    val racine: Noeud.Dossier,
) {
    /** Nombre total de fichiers de l'arbre (feuilles). */
    val nombreFichiers: Int get() = racine.nombreFichiers()

    /** Construit l'arbre depuis le plan (chemins relatifs sûrs du domaine). */
    companion object {
        fun depuisPlan(plan: TemplatePlan): Arborescence {
            val racine = Noeud.Dossier(nom = "", enfants = mutableMapOf())
            plan.fichiers.forEach { fichier ->
                var courant = racine
                val segments = fichier.chemin.split('/')
                segments.dropLast(1).forEach { segment ->
                    val existant = courant.enfants[segment]
                    courant =
                        if (existant is Noeud.Dossier) {
                            existant
                        } else {
                            val nouveau = Noeud.Dossier(nom = segment, enfants = mutableMapOf())
                            courant.enfants[segment] = nouveau
                            nouveau
                        }
                }
                // Une feuille n'écrase jamais un dossier de même nom.
                if (courant.enfants[segments.last()] == null) {
                    courant.enfants[segments.last()] = Noeud.Fichier(nom = segments.last())
                }
            }
            return Arborescence(racine)
        }
    }
}

/** Nœud de l'arborescence : dossier repliable ou fichier feuille. */
sealed interface Noeud {
    /** Nom affiché (vide pour la racine). */
    val nom: String

    /** Dossier : enfants triés (dossiers puis fichiers, ordre stable). */
    data class Dossier(
        override val nom: String,
        val enfants: MutableMap<String, Noeud>,
    ) : Noeud {
        /** Enfants triés : dossiers d'abord, puis fichiers, chacun lexicographique. */
        val enfantsTries: List<Noeud>
            get() = enfants.values.sortedWith(compareBy({ it !is Dossier }, Noeud::nom))

        /** Nombre de fichiers (récursif). */
        fun nombreFichiers(): Int =
            enfants.values.sumOf { noeud ->
                when (noeud) {
                    is Fichier -> 1
                    is Dossier -> noeud.nombreFichiers()
                }
            }
    }

    /** Fichier feuille. */
    data class Fichier(
        override val nom: String,
    ) : Noeud
}
