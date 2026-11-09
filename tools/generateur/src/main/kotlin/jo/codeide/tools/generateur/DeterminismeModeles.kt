package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.PlannedContent
import java.io.File
import java.security.MessageDigest
import java.util.SortedMap
import java.util.TreeMap

/**
 * Vérification du **déterminisme** `determinisme` (lot L2, ADR 0157 §1) :
 * chaque combinaison est planifiée **deux fois** — deux exécutions
 * indépendantes du planificateur — puis déversée dans deux arborescences
 * comparées **octet pour octet** (chemins identiques, contenus identiques).
 *
 * Le plan figé (ADR 0017) doit être reproductible à l'identique : une
 * divergence signale une horloge, une carte, une itération ou une HashMap
 * non déterministe quelque part dans le moteur.
 */
public class VerificateurDeterminisme(private val planifier: PlanProjectCreationUseCase) {
    /**
     * Vérifie chaque combinaison et rapporte le bilan.
     *
     * @param combinaisons entrées à vérifier.
     * @param racineA première arborescence de déversement (créée si absente).
     * @param racineB seconde arborescence de déversement (créée si absente).
     * @return un bilan par combinaison, dans l'ordre d'entrée.
     */
    public suspend fun verifier(
        combinaisons: List<Combinaison>,
        racineA: File,
        racineB: File,
    ): List<BilanDeterminisme> = combinaisons.map { combinaison -> verifierUne(combinaison, racineA, racineB) }

    /** Planifie deux fois, déverse deux fois et compare les arborescences. */
    @Suppress("TooGenericExceptionCaught") // Toute panne devient un bilan explicite (échec, jamais avalé).
    private suspend fun verifierUne(
        combinaison: Combinaison,
        racineA: File,
        racineB: File,
    ): BilanDeterminisme = try {
        val planUn = planification(combinaison)
        val planDeux = planification(combinaison)
        val projetA = File(racineA, combinaison.id)
        val projetB = File(racineB, combinaison.id)
        deverserPlan(planUn, projetA)
        deverserPlan(planDeux, projetB)
        val difference = comparerArborescences(projetA, projetB)
        BilanDeterminisme(combinaison.id, difference == null, difference)
    } catch (erreur: Exception) {
        BilanDeterminisme(combinaison.id, false, erreur.message ?: erreur::class.simpleName)
    }

    /** Planifie la création (dry-run complet, même cheminement que l'écriture). */
    private suspend fun planification(combinaison: Combinaison) =
        when (val resultat = planifier(requeteDe(combinaison))) {
            is AppResult.Success -> resultat.value
            is AppResult.Failure -> error("planification impossible : ${resultat.error}")
        }

    /**
     * Compare deux arborescences octet pour octet.
     *
     * @return `null` si identiques, sinon la description du premier écart.
     */
    private fun comparerArborescences(arbreA: File, arbreB: File): String? {
        val fichiersA = inventaire(arbreA, arbreA)
        val fichiersB = inventaire(arbreB, arbreB)
        return when {
            fichiersA.keys != fichiersB.keys ->
                when {
                    (fichiersA.keys - fichiersB.keys).isNotEmpty() ->
                        "fichier en trop : ${(fichiersA.keys - fichiersB.keys).first()}"

                    else -> "fichier manquant : ${(fichiersB.keys - fichiersA.keys).first()}"
                }

            else -> fichiersA.entries.firstOrNull { (chemin, empreinte) ->
                fichiersB.getValue(chemin) != empreinte
            }?.let { (chemin, _) -> "contenu différent : $chemin" }
        }
    }

    /** Inventaire trié (chemin relatif → SHA-256) d'une arborescence. */
    private fun inventaire(racine: File, dossier: File): SortedMap<String, String> {
        val entrees = TreeMap<String, String>()
        dossier
            .listFiles()
            .orEmpty()
            .sortedBy { it.name }
            .forEach { entree ->
                if (entree.isDirectory) {
                    entrees.putAll(inventaire(racine, entree))
                } else {
                    entrees[entree.relativeTo(racine).invariantSeparatorsPath] = empreinte(entree)
                }
            }
        return entrees
    }

    /** SHA-256 hexadécimal d'un fichier. */
    private fun empreinte(fichier: File): String = MessageDigest
        .getInstance("SHA-256")
        .digest(fichier.readBytes())
        .joinToString("") { "%02x".format(it) }
}

/** Bilan de déterminisme d'une combinaison (JSONL). */
@kotlinx.serialization.Serializable
public data class BilanDeterminisme(
    public val id: String,
    public val identique: Boolean,
    public val difference: String? = null,
)
