package jo.codeide.core.domain

import jo.codeide.core.model.getOrNull
import javax.inject.Inject

/**
 * Cas d'usage « résoudre un fichier du projet par chemin relatif »
 * (mission Projet P6+, v0.80.4) : traduit `app/build.gradle.kts` en URI
 * de document SAF en parcourant l'arborescence segment par segment —
 * le serveur de tooling et l'éditeur ne parlent pas le même vocabulaire
 * (chemin FUSE pour l'un, URI pour l'autre), ce cas d'usage est le pont.
 *
 * Tolérant par construction (même contrat que la lecture de l'état
 * d'espace) : un segment absent, un listing illisible ou un chemin qui
 * désigne un dossier retournent `null` — l'appelant replie sur un
 * message d'erreur honnête, jamais de crash ni d'URI inventée.
 *
 * Contexte d'exécution attendu : suspendante (listings SAF), hors
 * thread principal.
 */
@Suppress("ReturnCount") // Clauses de garde : chaque segment absent interrompt — le contrat de tolérance.
public class ResoudreFichierRelatifUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
    ) {
        /**
         * Résout [cheminRelatif] sous la racine [uriRacine] du projet.
         *
         * @param uriRacine URI de document du dossier racine du projet.
         * @param cheminRelatif chemin POSIX relatif (`a/b/c.kts`), séparateurs
         *   `/` uniquement ; les segments vides, `.` et `..` sont refusés
         *   (défense en profondeur, même règle que le pont FUSE).
         * @return l'URI de document du FICHIER, ou `null` si introuvable,
         *   illisible, ou si le chemin désigne un dossier.
         */
        public suspend operator fun invoke(
            uriRacine: String,
            cheminRelatif: String,
        ): String? {
            val segments =
                cheminRelatif
                    .split('/')
                    .filter { it.isNotEmpty() }
            if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null

            var courant = uriRacine
            for (index in segments.indices) {
                val nom = segments[index]
                val enfant =
                    fichiers
                        .list(courant)
                        .getOrNull()
                        ?.firstOrNull { it.name == nom }
                        ?: return null
                // Le dernier segment doit être un FICHIER ; un dossier
                // intermédiaire est requis pour continuer la descente.
                val dernier = index == segments.lastIndex
                if (dernier && enfant.isDirectory) return null
                if (!dernier && !enfant.isDirectory) return null
                courant = enfant.uri
            }
            return courant
        }
    }
