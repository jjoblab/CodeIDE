package jo.codeide.feature.ai

/**
 * Commandes `/` du chat (rapport IA, lot 3 — design d'après la
 * maquette) : les commandes rapides proposées pendant la frappe —
 * `/expliquer`, `/corriger`, `/tests`, `/doc`, `/refactor`. PURE et
 * testée en JVM : la suggestion ne montre que ce qui n'est pas encore
 * tranché (la saisie commence par `/` ET aucun espace n'a encore été
 * tapé — dès que la commande est suivie d'un espace, l'utilisateur
 * précise, les suggestions se ferment).
 */
public object CommandesBarreIa {
    /** Les commandes du chat, dans l'ordre de la maquette. */
    public val COMMANDES: List<String> =
        listOf(
            COMMANDE_EXPLIQUER,
            COMMANDE_CORRIGER,
            COMMANDE_TESTS,
            COMMANDE_DOC,
            COMMANDE_REFACTOR,
        )

    /** `/expliquer` — expliquer le fichier actif ou la sélection. */
    public const val COMMANDE_EXPLIQUER: String = "/expliquer"

    /** `/corriger` — corriger l'erreur ou le diagnostic courant. */
    public const val COMMANDE_CORRIGER: String = "/corriger"

    /** `/tests` — générer des tests pour le fichier actif ou la sélection. */
    public const val COMMANDE_TESTS: String = "/tests"

    /** `/doc` — documenter le fichier actif ou la sélection. */
    public const val COMMANDE_DOC: String = "/doc"

    /** `/refactor` — simplifier le code du fichier actif ou de la sélection. */
    public const val COMMANDE_REFACTOR: String = "/refactor"

    /**
     * Suggestions pendant la frappe : la saisie commence par `/` SANS
     * espace (la commande n'est pas encore tranchée) → les commandes
     * dont le préfixe correspond, dans l'ordre de la maquette. Une
     * saisie sans `/`, une commande complète suivie d'un espace ou un
     * préfixe inconnu ne suggèrent RIEN (jamais de bruit).
     */
    // Exemption detekt ciblée (règle 16) : ReturnCount — la fonction
    // EST la chaîne de gardes (pas un « / », commande tranchée, préfixe
    // seul, préfixe filtré) — même motif que `FournisseurIaActif.acceder`.
    @Suppress("ReturnCount")
    public fun suggestions(saisie: String): List<String> {
        if (!saisie.startsWith(PREFIXE)) return emptyList()
        if (saisie.contains(' ')) return emptyList()
        if (saisie == PREFIXE) return COMMANDES
        return COMMANDES.filter { it.startsWith(saisie) }
    }

    /** `true` quand la saisie commence par une commande suivie d'un espace (ou seule). */
    public fun estCommande(saisie: String): Boolean = COMMANDES.any { saisie == it || saisie.startsWith("$it ") }

    private const val PREFIXE = "/"
}
