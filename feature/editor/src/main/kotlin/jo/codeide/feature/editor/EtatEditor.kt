package jo.codeide.feature.editor

import jo.codeide.core.model.Project

/**
 * État observable de l'espace de travail (étape 13 — fondations).
 *
 * Volontairement minimal : le projet courant suffit. Les onglets ouverts,
 * l'état du panneau inférieur et l'arborescence arriveront avec les
 * étapes 14 à 16 — l'état grandira avec, jamais avant.
 *
 * @property chargement première lecture du projet en cours.
 * @property projet projet ouvert, ou `null` si l'identifiant reçu
 * n'existe plus au registre (l'écran le signale au lieu de planter).
 */
data class EtatEditor(
    val chargement: Boolean = true,
    val projet: Project? = null,
)

/** Clés partagées de l'espace de travail. */
object ClesEditor {
    /** Extra d'intention : identifiant du projet à ouvrir. */
    const val EXTRA_PROJECT_ID: String = "jo.codeide.editor.PROJECT_ID"
}
