package jo.codeide.core.ai

/**
 * Outil de l'agent IA (mission IA, lot IA-7, ADR 0125) : un nom, un
 * niveau de risque déclaré et une exécution suspendue. Les paramètres
 * arrivent sous forme de paires (le modèle émet du JSON — le schéma
 * ToolSpec décrit les champs) ; l'outil valide ses entrées et renvoie
 * un résultat MODÉLISÉ (jamais d'exception jusqu'à l'UI : un échec
 * d'outil est une observation pour le modèle, ADR 0125 « Confinement »).
 */
public interface OutilIa {
    /** Identifiant technique (snake_case) envoyé au modèle. */
    public val identifiant: String

    /** Description (pour le schéma ToolSpec — jamais de secret). */
    public val description: String

    /** Niveau de risque — pilote l'approbation. */
    public val niveau: NiveauRisqueIa

    /** Schéma JSON des paramètres (spécification ToolSpec). */
    public val parametresJson: String

    /**
     * Exécute l'outil. Toute sortie observable revient dans
     * [ResultatOutilIa] — le refus est un résultat, pas une erreur.
     */
    public suspend fun executer(arguments: Map<String, String>): ResultatOutilIa
}

/** Niveaux de risque d'un outil (ADR 0125 « Niveaux d'approbation »). */
public enum class NiveauRisqueIa {
    /** Lecture : approbation automatique. */
    LECTURE,

    /** Écriture MISE EN ATTENTE : n'écrit rien, alimente le ChangeSet. */
    ECRITURE_ATTENTE,

    /** Exécution : approbation explicite À CHAQUE appel. */
    EXECUTION,
}

/** Résultat d'un outil — modélisé, jamais une exception. */
public sealed interface ResultatOutilIa {
    /**
     * Sortie texte (observation pour le modèle, bornée).
     *
     * @property sortie observation renvoyée au modèle.
     * @property compteEchec `true` = la sortie rapporte un ÉCHEC qui
     * compte dans la garde `echecsConsecutifsMax` (auto-correction
     * bornée, A5 rapport IA lot 2 : un `run_gradle` en échec alimente
     * l'observation ET la garde — la boucle de correction ne peut pas
     * tourner indéfiniment).
     */
    public data class Succes(public val sortie: String, public val compteEchec: Boolean = false) : ResultatOutilIa

    /** Refus : l'outil n'a pas agi (évasion, fichier exclu, hors limite). */
    public data class Refuse(public val motif: String) : ResultatOutilIa
}

/**
 * Registre des outils de l'agent (ADR 0125 action 1) : liste fermée,
 * accès par identifiant, schéma ToolSpec assemblé pour la requête.
 */
public class RegistreOutilsIa(outils: List<OutilIa>) {
    private val parIdentifiant: Map<String, OutilIa> =
        outils.associateBy { outil -> outil.identifiant }

    /** Outils enregistrés (ordre stable — schéma de la requête). */
    public val outils: List<OutilIa> = outils.toList()

    /** Outil par identifiant (`null` = outil inconnu — résultat refusé). */
    public fun parIdentifiant(identifiant: String): OutilIa? = parIdentifiant[identifiant]

    /** Spécifications ToolSpec pour la requête du fournisseur. */
    public fun specifications(): List<ToolSpec> = outils.map { outil ->
        ToolSpec(
            name = outil.identifiant,
            description = outil.description,
            parametersJsonSchema = outil.parametresJson,
        )
    }
}
