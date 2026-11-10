package jo.codeide.core.domain

/**
 * L'expurgation À L'ÉCRITURE des sorties persistées (ADR 0161 décision 4,
 * lot L3) — la frontière live ≠ persisté assumée par l'ADR : l'affichage
 * live n'est JAMAIS altéré (c'est le contenu de l'utilisateur) ; ce qui
 * part sur disque passe ICI d'abord.
 *
 * Deux passes, dans cet ordre :
 *
 * 1. **relativisation** des chemins du projet : un chemin absolu SOUS la
 *    racine du projet (le `projectDir` du câble tooling) devient RELATIF
 *    — la sortie persistée reste lisible et le lien source d'une ligne
 *    `e: app/src/MainActivity.kt:12:5` reste résolvable à la relecture ;
 *    le préfixe `file://` tombe au passage ;
 * 2. **expurgation générale** ([LogRedactor]) : courriels, secrets,
 *    URI `content://`, chemins absolus restants (HORS du projet) — la
 *    même discipline que le journal applicatif (ADR 0009), idempotente.
 *
 * Une exécution sans racine connue (`racineProjet = null` — build lancé
 * sans sync observée) saute la relativisation : tout chemin absolu est
 * alors masqué par [LogRedactor] (jamais de chemin complet sur disque).
 *
 * Frontière assumée (ADR 0161) : un chemin absolu HORS du projet est
 * masqué — le lien source d'une dépendance externe ne survit pas à la
 * persistance ; le live, lui, reste cliquable tel quel.
 */
public object ExpurgateurSorties {

    /**
     * Expurge UNE ligne avant persistance.
     *
     * @param ligne la ligne de sortie telle qu'affichée en live.
     * @param racineProjet la racine du projet au moment de l'exécution
     *        (chemin absolu, sans terminaison), ou `null` si inconnue.
     * @return la ligne expurgée, prête pour le registre.
     */
    public fun expurger(ligne: LigneSortie, racineProjet: String?): LigneSortie {
        if (racineProjet == null) {
            return ligne.copy(
                message = LogRedactor.redact(ligne.message),
                positionsSource = ligne.positionsSource.map { position -> position.expurgee(null) },
            )
        }
        return ligne.copy(
            message = LogRedactor.redact(relativiser(ligne.message, racineProjet)),
            positionsSource = ligne.positionsSource.map { position -> position.expurgee(racineProjet) },
        )
    }

    /**
     * Expurge UN texte libre destiné à la persistance (résumé, message
     * d'échec d'en-tête) — même double passe que les lignes.
     */
    public fun expurgerTexte(texte: String, racineProjet: String?): String =
        LogRedactor.redact(if (racineProjet == null) texte else relativiser(texte, racineProjet))

    /** La position d'un lien source, relativisée puis expurgée. */
    private fun PositionSource.expurgee(racineProjet: String?): PositionSource = when (racineProjet) {
        null -> copy(fichier = LogRedactor.redact(fichier))

        else -> {
            val sousRacine = fichier.startsWith(racineProjet) || fichier.startsWith("file://$racineProjet")
            copy(fichier = if (sousRacine) relativiser(fichier, racineProjet) else LogRedactor.redact(fichier))
        }
    }

    /**
     * Relativise les chemins sous [racineProjet] dans un texte — les deux
     * formes du câble sont traitées : `file://<racine>/…` (marqueurs
     * `e:`/`w:` de Kotlin) et `<racine>/…` nu (Java, scripts). La
     * découpée garde le séparateur droit (le segment qui suit la racine
     * commence nu : « app/src/… »), et une racine PRÉFIXE d'un autre
     * chemin (`MyAppBackup/…`) reste intacte (leçon de l'ancrage : la
     * limite est un caractère de mot, jamais un chiffre absent).
     */
    private fun relativiser(texte: String, racineProjet: String): String {
        val racine = racineProjet.trimEnd('/')
        if (racine.isEmpty()) return texte
        val regexRacine = Regex("${Regex.escape(racine)}(?![\\w-])")
        return texte
            .replace("file://$racine/", "")
            .replace("$racine/", "")
            .replace(regexRacine, "")
    }
}
