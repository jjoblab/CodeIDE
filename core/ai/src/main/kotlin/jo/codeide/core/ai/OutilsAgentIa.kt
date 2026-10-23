package jo.codeide.core.ai

import jo.codeide.core.ai.FournisseurContexteProjet
import jo.codeide.core.model.AppResult
import java.io.File

/**
 * Résolveur de chemins de l'agent (mission IA, lot IA-7, ADR 0125
 * « Confinement ») : TOUTE opération de chemin passe ICI — normalisation,
 * refus de `..`, des chemins absolus, des liens sortant du projet et des
 * fichiers exclus (ADR 0124, [ExclusionsIa]). Le résultat est soit un
 * [File] racine-projet garanti, soit un motif de refus renvoyé au modèle.
 *
 * Le dossier racine RÉEL du projet est fourni par l'hôte (pont
 * SAF → FUSE, [ResoudreRepertoireProjet]) : l'agent ne voit JAMAIS un
 * chemin hors de cette racine.
 */
public class ResolveurCheminsProjetIa(private val racineReelle: File, private val exclusions: ExclusionsIa) {
    /**
     * Résout un chemin relatif (fichier ou dossier).
     *
     * @return le fichier borné à la racine, ou le motif du refus.
     */
    @Suppress("ReturnCount") // chaque motif de refus est un retour dédié (résultats modélisés, ADR 0125).
    public fun resoudre(cheminRelatif: String): ResultatFichierIa {
        if (cheminRelatif.isBlank()) {
            return ResultatFichierIa.Refuse("chemin vide")
        }
        if (cheminRelatif.startsWith('/')) {
            return ResultatFichierIa.Refuse("chemin absolu refusé (ADR 0125)")
        }
        val segments = cheminRelatif.split('/')
        if (segments.any { it == ".." }) {
            return ResultatFichierIa.Refuse("sortie de projet refusée (..) — ADR 0125")
        }
        val canonique = segments.filter { it.isNotBlank() }.joinToString("/")
        if (canonique.isEmpty()) {
            return ResultatFichierIa.Refuse("chemin vide")
        }
        if (exclusions.estExclu(canonique)) {
            return ResultatFichierIa.Refuse("fichier exclu de l'IA (ADR 0124)")
        }
        val fichier = File(racineReelle, canonique)
        // Lien symbolique sortant : le chemin CANONIQUE doit rester sous
        // la racine canonique (garde du confinement).
        val racineCanonique = racineReelle.canonicalFile
        val cible = fichier.canonicalFile
        if (!cible.path.startsWith(racineCanonique.path)) {
            return ResultatFichierIa.Refuse("lien symbolique sortant du projet refusé (ADR 0125)")
        }
        return ResultatFichierIa.OK(cible, canonique)
    }

    /** Vérifie l'exclusion d'un chemin déjà canonique (listes, lectures). */
    public fun estExclu(cheminRelatif: String): Boolean = exclusions.estExclu(cheminRelatif)

    /** Résultat de résolution. */
    public sealed interface ResultatFichierIa {
        /** Fichier borné + chemin canonique relatif. */
        public data class OK(public val fichier: File, public val cheminRelatif: String) : ResultatFichierIa

        /** Refus motivé (renvoyé au modèle, jamais d'exception). */
        public data class Refuse(public val motif: String) : ResultatFichierIa
    }
}

/**
 * Outils V1 de lecture de l'agent (ADR 0125, table des outils) :
 * `list_files` et `read_file` passent par [FournisseurContexteProjet]
 * (exclusions + garde d'évasion déjà appliquées côté port) — le contenu
 * est borné (le port tronque) et les chemins restent RELATIFS.
 */
public class OutilsLectureIa(private val fournisseur: FournisseurContexteProjet) {
    /** `list_files(dossier?)` : chemins du projet (ou d'un sous-dossier). */
    public suspend fun listerFichiers(dossier: String?): ResultatOutilIa =
        when (val resultat = fournisseur.listerChemins()) {
            is AppResult.Success -> {
                val prefixe = dossier?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                val chemins =
                    resultat.value
                        .filter { prefixe == null || it.startsWith("$prefixe/") }
                        .take(LIMITE_CHEMINS)
                ResultatOutilIa.Succes(chemins.joinToString("\n").ifEmpty { "(aucun fichier)" })
            }

            is AppResult.Failure -> {
                ResultatOutilIa.Refuse("listing indisponible")
            }
        }

    /** `read_file(chemin)` : contenu borné, refus des exclus (port). */
    public suspend fun lireFichier(cheminRelatif: String): ResultatOutilIa =
        when (val resultat = fournisseur.lireFichier(cheminRelatif)) {
            is AppResult.Success -> ResultatOutilIa.Succes(resultat.value)
            is AppResult.Failure -> ResultatOutilIa.Refuse("fichier introuvable, exclu ou illisible : $cheminRelatif")
        }

    private companion object {
        /** Plafond du listing renvoyé au modèle (sobriété). */
        private const val LIMITE_CHEMINS = 200
    }
}
