package jo.codeide.core.model

/**
 * Réglages LSP « Intelligence du code » (lot LSP-2, ADR 0128 §4) — hors
 * [AppSettings] par décision de la revue `LSP_REVUE_REGLAGES.md` :
 * un DÉPÔT DÉDIÉ ([jo.codeide.core.datastore.LspSettingsStore]), miroir
 * du traitement réservé aux réglages IA (ADR 0122 — jamais dans le
 * sac-à-dos à 40 champs, la leçon detekt v0.134.27).
 *
 * Périmètre v0.157.0 : chaque champ projette une API RÉELLE de l'hôte
 * `codeide-lsp` v1.2.0 (`ConfigurationLsp` — « jamais un contrôle sans
 * effet », ADR 0059) :
 *
 * - **budget du cache d'index** : `null` = **Auto** (le dérivé
 *   `max(30 Mo, 512 Kio/jar)` du banc lot F2) ; un plafond explicite
 *   borne le cache LRU de l'index dans les DEUX sens ;
 * - **surveillance des fichiers externes** : période du surveillant
 *   S1-d (`0` = désactivée ; défaut 5 s) — un git pull ou une édition
 *   depuis un autre outil atteint le cache de parse sans réouverture.
 *
 * Les filtres d'AFFICHAGE (gravité des diagnostics affichés,
 * interrupteurs de capacités par langage) exigeant une évolution de la
 * bibliothèque `code-editor` (le rendu des diagnostics vit dans
 * cel-ui — écran L2, `docs/LSP_SPEC_ECRANS.md`), ils rejoindront ce
 * modèle au lot qui les rend réels : rien n'est persisté sans effet.
 *
 * La traduction vers l'hôte ([versConfiguration] côté `feature:editor`)
 * est le SEUL point où ce modèle pur rencontre `:lsp:host` (frontière
 * nette : `core:model` ne dépend d'aucun module LSP).
 */
public data class LspSettings(
    /** Plafond du budget du cache LRU d'index (octets) ; `null` = Auto. */
    public val budgetCacheOctets: Long? = BUDGET_AUTO,
    /** Période du surveillant de fichiers externes (ms) ; `0` = désactivée. */
    public val surveillanceFichiersMs: Long = SURVEILLANCE_PAR_DEFAUT_MS,
) {
    init {
        require(surveillanceFichiersMs >= 0) {
            "surveillanceFichiersMs doit être >= 0 (0 = désactivée) : $surveillanceFichiersMs"
        }
        if (budgetCacheOctets != null) {
            require(budgetCacheOctets > 0) {
                "budgetCacheOctets doit être > 0 ou null (Auto) : $budgetCacheOctets"
            }
        }
    }

    public companion object {
        /** Période historique du surveillant (5 s, S1-d). */
        public const val SURVEILLANCE_PAR_DEFAUT_MS: Long = 5_000L

        /** Budget « Auto » — plafond dérivé du corpus (comportement historique). */
        public val BUDGET_AUTO: Long? = null

        /** Les défauts = le comportement historique de l'hôte (v1.0.x–v1.2.0). */
        public fun parDefaut(): LspSettings = LspSettings()

        /** Octets par Mo (le réglage public est exprimé en Mo à l'écran L3). */
        public const val OCTETS_PAR_MO: Long = 1024L * 1024L

        /**
         * Choix de l'écran L3 « Taille maximale » (ADR 0128 §4) — le
         * premier élément est Auto, ensuite les paliers proposés.
         */
        public val BUDGETS_POSSIBLES_OCTETS: List<Long?> =
            listOf(
                BUDGET_AUTO,
                256L * OCTETS_PAR_MO,
                512L * OCTETS_PAR_MO,
                1024L * OCTETS_PAR_MO,
            )

        /**
         * Choix de l'écran L3 « Fréquence de surveillance » — le premier
         * élément (0) est l'interrupteur coupé, ensuite les périodes.
         */
        public val SURVEILLANCES_POSSIBLES_MS: List<Long> =
            listOf(0L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}
