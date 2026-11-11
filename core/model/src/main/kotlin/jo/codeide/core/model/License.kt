package jo.codeide.core.model

/**
 * Licence par défaut proposée à la création d'un projet (section 12.3 du
 * prompt maître — étape « Fichiers » du wizard ; pré-remplie depuis
 * `AppSettings.defaultLicense`).
 *
 * Le nom de la constante sert de clé de persistance stable dans les
 * préférences : une valeur inconnue lue sur disque (montée de version,
 * édition manuelle) retombe sur le défaut sans crash — la conversion
 * tolérante est [License.fromPersistedName].
 */
public enum class License {
    /** Aucun fichier de licence généré. */
    NONE,

    /** Licence MIT (texte court, permissive). */
    MIT,

    /** Licence Apache 2.0 (notice + `NOTICE` éventuel). */
    APACHE_2_0,

    /** Licence GPL 3.0 (copyleft). */
    GPL_3_0,

    /** Licence BSD à 3 clauses. */
    BSD_3_CLAUSE,
    ;

    public companion object {
        /**
         * Lit une licence depuis son nom persisté.
         *
         * La persistance utilise le nom de la constante ; cette conversion
         * est tolérante par design : toute valeur inconnue retourne `null`
         * pour que l'appelant (la source de données des paramètres) retombe
         * sur le défaut sans interrompre la lecture.
         *
         * @param persistedName nom tel que persisté (sensible à la casse).
         * @return la licence correspondante, ou `null` si inconnue.
         */
        public fun fromPersistedName(persistedName: String): License? = entries.firstOrNull { it.name == persistedName }
    }
}
