package jo.codeide.core.ai

import kotlin.math.ceil

/**
 * Budget de jetons « estimation grossière » (mission IA, plan IA-1) : une
 * heuristique **locale**, sans appel réseau ni tokenizer embarqué —
 * suffisante pour borner le contexte assemblé (IA-4) et afficher une
 * jauge honnête, jamais pour la facturation.
 *
 * Heuristique : ~4 caractères par jeton (ordre de grandeur usuel des
 * tokenizers BPE sur du code et du texte technique). L'estimation est
 * volontairement prudente (arrondie au jeton supérieur) ; elle ne prétend
 * pas reproduire un tokenizer précis.
 *
 * Aucun plafond n'est codé en dur ici : les plafonds viennent des
 * réglages (IA-2) et, à terme, de la fenêtre du modèle choisi
 * ([AiModelInfo.contextWindowTokens]) — ADR 0123 point 6.
 *
 * @param charactersPerToken rapport caractères/jeton de l'heuristique ;
 * la valeur par défaut (4,0) est un ordre de grandeur documenté, pas une
 * promesse de précision.
 */
public class TokenBudget(
    private val charactersPerToken: Double = 4.0,
) {
    init {
        require(charactersPerToken > 0.0) { "le rapport caractères/jeton doit être strictement positif" }
    }

    /**
     * Estime le nombre de jetons d'un texte.
     *
     * @param text texte à estimer (vide autorisé → 0).
     * @return estimation prudente, arrondie au jeton supérieur.
     */
    public fun estimateTokens(text: String): Long =
        if (text.isEmpty()) 0L else ceil(text.length / charactersPerToken).toLong()

    /**
     * Estime le budget total d'une requête : blocs texte, résultats
     * d'outils et arguments d'appels d'outils, plus un forfait par message
     * (enveloppe du format natif : rôles, séparateurs).
     *
     * @param messages messages de la requête.
     * @return estimation totale en jetons.
     */
    public fun estimateMessages(messages: List<AiMessage>): Long {
        var caracteres = 0L
        for (message in messages) {
            caracteres += FORFAIT_MESSAGE_CARACTERES
            for (block in message.blocks) {
                caracteres +=
                    when (block) {
                        is AiContentBlock.Text -> {
                            block.text.length.toLong()
                        }

                        is AiContentBlock.ToolResult -> {
                            block.content.length.toLong()
                        }

                        is AiContentBlock.ToolUse -> {
                            (block.name.length + block.argumentsJson.length).toLong()
                        }
                    }
            }
        }
        return ceil(caracteres / charactersPerToken).toLong()
    }

    /**
     * Indique si la requête tient dans le plafond donné.
     *
     * @param messages messages de la requête.
     * @param maxTokens plafond en jetons (réglages IA-2 ou fenêtre du
     * modèle) — strictement positif.
     * @return `true` si l'estimation tient dans le plafond.
     */
    public fun isWithinBudget(
        messages: List<AiMessage>,
        maxTokens: Long,
    ): Boolean = estimateMessages(messages) <= maxTokens

    private companion object {
        /** Forfait d'enveloppe par message, en caractères. */
        private const val FORFAIT_MESSAGE_CARACTERES = 8L
    }
}
