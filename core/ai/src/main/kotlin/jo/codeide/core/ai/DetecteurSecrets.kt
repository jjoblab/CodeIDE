package jo.codeide.core.ai

/**
 * Balayage préventif de secrets du contexte (mission IA, lot IA-4,
 * ADR 0124 §4) : avant tout envoi, le texte assemblé est passé aux
 * motifs fermés ci-dessous (l'esprit de `LogRedactor` — core:domain) ;
 * toute occurrence produit une alerte portant l'ÉTIQUETTE du motif,
 * jamais la valeur détectée.
 *
 * Aucun envoi n'est bloqué ici : l'UI décide du dialogue bloquant
 * (« Envoyer sans » / « Annuler ») — le domaine rapporte, l'humain
 * tranche.
 */
public object DetecteurSecrets {
    /** Un motif fermé : étiquette lisible + expression régulière. */
    public data class Motif(public val etiquette: String, public val regex: Regex)

    /**
     * Motifs fermés — toute nouvelle famille de secrets s'ajoute ICI
     * (avec son test), jamais au fil de l'eau dans l'assembleur.
     */
    public val MOTIFS: List<Motif> =
        listOf(
            Motif("clé sk-", Regex("""\bsk-[A-Za-z0-9_-]{16,}""")),
            Motif("clé Google AIza", Regex("""\bAIza[0-9A-Za-z_-]{20,}""")),
            Motif("jeton GitHub ghp_", Regex("""\bghp_[A-Za-z0-9]{20,}""")),
            Motif("jeton GitHub fin", Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}""")),
            Motif("clé AWS AKIA", Regex("""\bAKIA[0-9A-Z]{16}\b""")),
            Motif("jeton Slack", Regex("""\bxox[baprs]-[A-Za-z0-9-]{10,}""")),
            Motif("en-tête Authorization", Regex("""\bAuthorization\s*[:=]\s*\S+""", RegexOption.IGNORE_CASE)),
            Motif("jeton Bearer", Regex("""\bBearer\s+[A-Za-z0-9._-]{16,}""", RegexOption.IGNORE_CASE)),
            Motif("clé x-api-key", Regex("""\bx-api-key\s*[:=]\s*\S+""", RegexOption.IGNORE_CASE)),
            Motif("clé privée", Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")),
            Motif(
                "jeton long",
                Regex("""\b[A-Fa-f0-9]{40,}\b|\b[A-Za-z0-9_-]{45,}\b"""),
            ),
        )

    /**
     * Balaye un texte et rend les étiquettes des motifs trouvés
     * (dédupliquées, ordre de [MOTIFS]). Le texte du contexte assemblé
     * est le seul balayé — jamais les journaux ni les états d'UI.
     */
    public fun balayer(texte: String): List<String> =
        MOTIFS.filter { motif -> motif.regex.containsMatchIn(texte) }.map(Motif::etiquette)

    /**
     * Balaye un texte et rend les alertes associées à un chemin
     * (pièce d'origine) — utile à l'assembleur pour situer l'alerte.
     */
    public fun balayer(texte: String, cheminRelatif: String?): List<AlerteSecret> = MOTIFS
        .filter { motif -> motif.regex.containsMatchIn(texte) }
        .map { motif -> AlerteSecret(motif.etiquette, cheminRelatif) }
}
