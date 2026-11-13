package jo.codeide.ai.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Outils JSON partagés des adaptateurs (mission IA, IA-1) : navigation
 * **tolérante** dans les structures renvoyées par les fournisseurs — un
 * champ absent, null ou d'un type inattendu renvoie `null` plutôt que de
 * lever, la traduction en `MalformedResponse` restant à la charge du
 * contexte appelant.
 */
internal object JsonIa {
    /** Parseur strict utilisé pour les corps de requête et les flux. */
    internal val parseur = Json { ignoreUnknownKeys = true }

    /**
     * Lit un objet imbriqué : `objet(json, "message", "usage")` descend
     * le chemin et renvoie l'objet terminal, ou `null`.
     */
    internal fun objet(source: JsonObject, vararg chemin: String): JsonObject? = descendre(source, chemin.toList())

    /**
     * Lit une chaîne imbriquée : `chaine(json, "delta", "text")` renvoie
     * le texte terminal, ou `null` (absent, null, type inattendu).
     */
    internal fun chaine(source: JsonObject, vararg chemin: String): String? {
        if (chemin.isEmpty()) return null
        val parent = descendre(source, chemin.dropLast(1))
        val primitive =
            (parent?.get(chemin.last()) as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }
        return primitive?.content
    }

    /**
     * Lit un entier long imbriqué (`input_tokens`, `output_tokens`…),
     * ou `null`.
     */
    internal fun entier(source: JsonObject, vararg chemin: String): Long? = chaine(source, *chemin)?.toLongOrNull()

    /**
     * Descend le chemin d'objets intermédiaires (`null` dès qu'un maillon
     * manque ou n'est pas un objet).
     */
    private fun descendre(source: JsonObject, chemin: List<String>): JsonObject? =
        chemin.fold(source as JsonObject?) { courant, etape ->
            courant?.get(etape) as? JsonObject
        }
}

/**
 * Appel d'outil en cours de reconstitution : les fournisseurs fragmentent
 * les arguments JSON librement — l'identifiant et le nom arrivent au
 * démarrage, les arguments par fragments à concaténer (ADR 0123 point 3).
 *
 * @property toolCallId identifiant d'appel (peut être vide chez les
 * serveurs compatibles OpenAI qui ne le répètent pas).
 * @property name nom de l'outil.
 */
internal class AppelOutilEnCours(var toolCallId: String, var name: String) {
    /** Fragments d'arguments déjà reçus, dans l'ordre. */
    val arguments = StringBuilder()

    /**
     * Concatène un fragment d'arguments.
     *
     * @param fragment fragment de texte JSON à ajouter.
     */
    fun ajouter(fragment: String) {
        arguments.append(fragment)
    }
}
