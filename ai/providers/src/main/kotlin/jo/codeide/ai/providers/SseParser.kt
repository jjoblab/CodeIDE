package jo.codeide.ai.providers

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Événement SSE brut tel que renvoyé par [SseParser] : paire
 * (nom d'événement, données), **avant** traduction vers [jo.codeide.core.ai.AiEvent].
 *
 * @property event nom d'événement (`event:` du flux), `null` si absent —
 * les adaptateurs traitent alors l'événement par défaut.
 * @property data données (`data:` du flux) — plusieurs lignes `data:`
 * d'un même événement sont jointes par `\n` (spécification SSE).
 */
public class SseEvent(
    public val event: String?,
    public val data: String,
) {
    override fun toString(): String = "SseEvent(event=${event ?: "message"}, data=${data.length} car.)"
}

/**
 * Parseur SSE maison (ADR 0123, option A) : lecture ligne à ligne,
 * jointure des lignes `data:` multiples, commentaire `:` ignoré, champs
 * `id:`/`retry:` et champs inconnus ignorés (les adaptateurs ignorent de
 * leur côté les noms d'événements qu'ils ne connaissent pas — plan IA-1,
 * « `event:` inconnu ignoré »).
 *
 * Décodage UTF-8 : la lecture passe par un décodeur continu — un caractère
 * multioctet coupé au milieu d'un segment réseau est reconstitué
 * correctement (plan IA-1, « UTF-8 coupé en milieu de séquence ») ; une
 * séquence tronquée à la toute fin du flux produit un caractère de
 * remplacement qui fera échouer le JSON en aval (erreur
 * `MalformedResponse` modélisée).
 *
 * Conformité SSE : un événement est délivré à la ligne vide qui le
 * termine ; un événement encore en mémoire à la fin du flux est délivré
 * (serveur sans retour à la ligne final), ce qui permet à l'adaptateur de
 * détecter un flux tronqué au milieu d'un JSON.
 */
public class SseParser {
    /**
     * Parse un flux SSE brut, ligne à ligne.
     *
     * @param lines lignes du flux (sans les fins de ligne).
     * @return séquence d'événements, dans l'ordre d'arrivée.
     */
    @Suppress("CyclomaticComplexMethod") // machine à états SSE : une branche par type de ligne
    public fun parseLines(lines: Sequence<String>): Sequence<SseEvent> =
        sequence {
            var eventName: String? = null
            val data = StringBuilder()

            for (line in lines) {
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty() || eventName != null) {
                            yield(SseEvent(eventName, data.toString()))
                            eventName = null
                            data.setLength(0)
                        }
                    }

                    line.startsWith(COMMENTAIRE_PREFIXE) -> {
                        // Commentaire SSE (`: keep-alive`) — ignoré.
                    }

                    else -> {
                        val deuxPoints = line.indexOf(':')
                        val champ =
                            if (deuxPoints < 0) line else line.substring(0, deuxPoints)
                        var valeur =
                            if (deuxPoints < 0) "" else line.substring(deuxPoints + 1)
                        if (valeur.startsWith(" ")) valeur = valeur.substring(1)
                        when (champ) {
                            "data" -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(valeur)
                            }

                            "event" -> {
                                eventName = valeur.ifEmpty { null }
                            }
                            // `id`, `retry` et tout champ inconnu : ignorés
                            // (la reprise par Last-Event-ID est hors périmètre).
                        }
                    }
                }
            }

            // Fin de flux : l'événement en cours (sans ligne vide finale) est
            // délivré — un JSON tronqué remonte alors à l'adaptateur.
            if (data.isNotEmpty() || eventName != null) {
                yield(SseEvent(eventName, data.toString()))
            }
        }

    /**
     * Parse un flux SSE depuis un flux d'octets (réseau) — même contrat
     * que [parseLines], avec décodage UTF-8 continu.
     *
     * @param input flux d'octets du corps de réponse.
     * @return séquence d'événements, dans l'ordre d'arrivée.
     */
    public fun parseBytes(input: InputStream): Sequence<SseEvent> =
        parseLines(
            input
                .bufferedReader(StandardCharsets.UTF_8)
                .lineSequence(),
        )

    private companion object {
        /** Préfixe d'un commentaire SSE. */
        private const val COMMENTAIRE_PREFIXE = ":"
    }
}
