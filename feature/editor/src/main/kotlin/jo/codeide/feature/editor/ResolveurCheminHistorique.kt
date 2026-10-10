package jo.codeide.feature.editor

import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.model.AppResult
import javax.inject.Inject

/**
 * Résout l'URI de document d'un chemin RELATIF du projet ouvert (mission
 * H3 — l'historique d'un dossier et la restauration d'un fichier
 * supprimé partent d'un chemin, pas d'une URI).
 *
 * Les URI SAF construites par concaténation (`racine/chemin`) ne sont
 * pas adressables en écriture (leçon v0.64.0 : seul `DocumentsContract`
 * connaît l'identifiant réel) : on énumère chaque dossier PARENT — un
 * `list` par segment, borné — et l'on repère l'enfant par son NOM.
 * C'est le même mécanisme que la résolution des scripts Gradle et les
 * sondes de pile (R4), réutilisable pour toute la feuille Historique.
 *
 * Un segment manquant retourne `null` : le fichier est supprimé (pierre
 * tombale restaurable) ou le dossier parent a disparu — l'appelant
 * décide, jamais d'URI inventée.
 */
@Suppress("ReturnCount") // Gardes typées : hors projet, chemin vide, segment manquant, énumération refusée.
class ResolveurCheminHistorique
    @Inject
    constructor(
        private val fichiers: FileSystem,
        private val source: SourceProjetHistorique,
    ) {
        /**
         * URI du document désigné par [cheminRelatif] (segments `/`), ou
         * `null` : hors projet, chemin vide, segment introuvable,
         * énumération impossible.
         */
        suspend fun resoudre(cheminRelatif: String): String? {
            val racine = source.racineDocument ?: return null
            val segments = cheminRelatif.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return racine
            // Garde de profondeur : un chemin d'entrée corrompue ne
            // déclenche jamais une énumération sans fin (16 `list` max).
            if (segments.size > PROFONDEUR_MAX) return null

            var uriCourante = racine
            for (segment in segments) {
                val enfants =
                    when (val enumeration = fichiers.list(uriCourante)) {
                        is AppResult.Success -> enumeration.value
                        is AppResult.Failure -> return null
                    }
                uriCourante = enfants.firstOrNull { it.name == segment }?.uri ?: return null
            }
            return uriCourante
        }

        private companion object {
            /** Profondeur maximale résolue (garde anti-énumération). */
            const val PROFONDEUR_MAX = 16
        }
    }
