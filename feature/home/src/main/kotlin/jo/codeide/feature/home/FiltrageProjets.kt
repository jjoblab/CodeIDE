package jo.codeide.feature.home

import jo.codeide.core.model.Project
import java.text.Normalizer

/*
 * Filtrage et tri de la liste des projets (étape 7) — fonctions pures,
 * testées isolément ([FiltrageProjetsTest]) et appliquées par le
 * ViewModel à chaque changement de registre, de recherche ou de tri.
 *
 * La recherche est insensible à la casse **et aux accents** : « theses »
 * trouve « Thèses » — la saisie mobile s'en dispense volontiers.
 */

/** Recherche appliquée : nom, description et emplacement parcourus. */
internal fun List<Project>.filtrer(requete: String): List<Project> {
    val cible = requete.normaliser()
    if (cible.isEmpty()) return this
    return filter { projet ->
        projet.name.normaliser().contains(cible) ||
            projet.description.normaliser().contains(cible) ||
            projet.location.displayPath
                .normaliser()
                .contains(cible)
    }
}

/** Tri de l'accueil : épingles d'abord, puis selon le choix utilisateur. */
internal fun List<Project>.trier(tri: TriAccueil): List<Project> =
    when (tri) {
        TriAccueil.RECENTS -> {
            sortedWith(
                compareByDescending<Project> { it.isPinned }
                    .thenByDescending { it.lastOpenedAtMillis ?: -1L }
                    .thenBy { it.name.cleDeTri() },
            )
        }

        TriAccueil.NOM -> {
            sortedWith(
                compareByDescending<Project> { it.isPinned }
                    .thenBy { it.name.cleDeTri() },
            )
        }
    }

/** Clé de tri d'un nom : minuscule sans accent, déterministe. */
private fun String.cleDeTri(): String = normaliser()

/**
 * Normalisation de recherche : minuscule, accents décomposés puis
 * supprimés (NFD), espacements pliés.
 */
internal fun String.normaliser(): String =
    Normalizer
        .normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .trim()
