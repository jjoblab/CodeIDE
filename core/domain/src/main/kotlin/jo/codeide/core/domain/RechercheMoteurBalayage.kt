package jo.codeide.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Implémentation de production de [RechercheMoteur] (ADR 0094) : balayage
 * Kotlin pur en flux sur le chemin FUSE réel du projet.
 *
 * Parcourt récursivement les fichiers avec `java.nio`, lit chaque fichier
 * ligne par ligne, émet les résultats par lots via [Flow]. L'annulation
 * est immédiate (flux froid — la collecte annulée arrête le balayage).
 *
 * Règles d'exclusion appliquées :
 * - `.git/`, `build/`, `.gradle/`, `node_modules/` toujours exclus
 * - Fichiers binaires (NUL dans les 8 premiers Ko) ignorés
 * - Taille max par fichier configurable (défaut 2 Mo)
 * - Fichiers cachés (option) et dossiers de build (option) masqués
 * - Plafond de résultats configurable (défaut 20 000)
 */
@Suppress("TooManyFunctions") // Port de recherche : une fonction par étape du balayage.
public class RechercheMoteurBalayage : RechercheMoteur {
    override fun rechercher(
        cheminFuse: String,
        requete: String,
        options: OptionsRecherche,
    ): Flow<LotResultats> =
        flow {
            if (requete.isBlank()) return@flow
            val racine = File(cheminFuse)
            if (!racine.isDirectory) return@flow
            val motif = compilerMotif(requete, options) ?: return@flow
            val resultats = mutableListOf<ResultatRecherche>()
            var fichiersBalayes = 0
            var plafondAtteint = false

            racine.walkTopDown().forEach { fichier ->
                if (plafondAtteint) return@forEach
                if (!fichier.isFile) return@forEach
                if (estExclu(fichier, racine, options)) return@forEach
                if (fichier.length() > options.tailleMaxFichier) return@forEach
                if (estBinaire(fichier)) return@forEach

                fichiersBalayes++
                val cheminRelatif = fichier.relativeTo(racine).path
                fichier.useLines { lignes ->
                    lignes.forEachIndexed { index, ligne ->
                        if (resultats.size >= options.plafondResultats) {
                            plafondAtteint = true
                            return@useLines
                        }
                        val trouveur = motif.find(ligne)
                        trouveur?.let { match ->
                            resultats +=
                                ResultatRecherche(
                                    chemin = cheminRelatif,
                                    numeroLigne = index + 1,
                                    colonneDebut = match.range.first,
                                    colonneFin = match.range.last + 1,
                                    extrait = ligne.take(EXTRAIT_MAX).trim(),
                                    nomFichier = fichier.name,
                                )
                        }
                    }
                }
                if (resultats.size >= LOT_TAILLE || resultats.isNotEmpty()) {
                    emit(LotResultats(resultats.toList(), fichiersBalayes, plafondAtteint, false))
                    resultats.clear()
                }
            }
            emit(LotResultats(resultats.toList(), fichiersBalayes, plafondAtteint, true))
        }

    /** Compile la requête en [Regex] selon les [options]. */
    @Suppress("SwallowedException") // Regex invalide → null (repli, pas de crash).
    internal fun compilerMotif(
        requete: String,
        options: OptionsRecherche,
    ): Regex? =
        try {
            val flags = mutableSetOf<RegexOption>()
            if (options.ignorerCasse) flags.add(RegexOption.IGNORE_CASE)
            if (options.motEntier) {
                Regex("\\b${Regex.escape(requete)}\\b", flags.toSet())
            } else if (options.regex) {
                Regex(requete, flags.toSet())
            } else {
                Regex(Regex.escape(requete), flags.toSet())
            }
        } catch (e: Exception) {
            null
        }

    /** Vérifie si un fichier doit être exclu selon les règles. */
    @Suppress("ReturnCount") // Gardes d'exclusion : nom, chemin, options.
    internal fun estExclu(
        fichier: File,
        racine: File,
        options: OptionsRecherche,
    ): Boolean {
        val nom = fichier.name
        val relatif = fichier.relativeTo(racine).path
        // Dossiers toujours exclus.
        val dossiersExclus = setOf(".git", "build", ".gradle", "node_modules")
        if (dossiersExclus.any { relatif.startsWith("$it/") || relatif.contains("/$it/") }) return true
        // Fichiers cachés.
        if (options.masquerCaches && nom.startsWith(".")) return true
        // Dossiers de build (option redondante avec dossiersExclus mais explicite).
        if (options.masquerBuild && (nom == "build" || nom == ".gradle")) return true
        return false
    }

    /** Détecte un fichier binaire (NUL dans les 8 premiers Ko). */
    @Suppress("SwallowedException", "ReturnCount", "NestedBlockDepth") // Lecture fichier : gardes + exception avalée.
    internal fun estBinaire(fichier: File): Boolean {
        if (fichier.length() == 0L) return false
        return try {
            fichier.inputStream().use { flux ->
                val tampon = ByteArray(TAILLE_DETECTION_BINAIRE)
                val lu = flux.read(tampon)
                if (lu <= 0) return false
                tampon.copyOfRange(0, lu).any { it == 0.toByte() }
            }
        } catch (e: Exception) {
            true
        }
    }

    private companion object {
        /** Taille d'un lot de résultats (émission intermédiaire). */
        const val LOT_TAILLE = 50

        /** Longueur maximum de l'extrait affiché. */
        const val EXTRAIT_MAX = 200

        /** Taille de détection des fichiers binaires (8 Ko). */
        const val TAILLE_DETECTION_BINAIRE = 8 * 1024
    }
}
