package jo.codeide.core.model

/**
 * Options communes fournies par le moteur de templates (étape 8 — section 11).
 *
 * Ces options sont rendues à l'étape « Fichiers » du wizard (section 12.3)
 * pour **tous** les modèles : elles alimentent des variables du même nom dans
 * le mini-langage (`includeReadme`, `license`…) et pilotent les fichiers
 * groupés par [TemplateFileGroup]. La licence par défaut vient des paramètres
 * (`AppSettings.defaultLicense`) ; la langue du contenu suit la langue de
 * l'application par défaut.
 *
 * @property includeReadme générer le README du modèle.
 * @property includeGitignore générer le `.gitignore` du modèle.
 * @property includeEditorconfig générer le `.editorconfig` du modèle.
 * @property license licence à générer (fichier `assets/licenses/` officiel
 * SPDX ; MIT et BSD incluent auteur et année).
 * @property contentLanguage langue du **contenu généré** (README, KDoc,
 * messages) — `fr` ou `en`, distincte de la langue de l'interface.
 */
public data class TemplateOptions(
    public val includeReadme: Boolean = true,
    public val includeGitignore: Boolean = true,
    public val includeEditorconfig: Boolean = true,
    public val license: License = License.NONE,
    public val contentLanguage: String = LANGUE_DEFAUT,
) {
    public companion object {
        /** Langues du contenu acceptées par le moteur. */
        public val LANGUES_CONTENU: Set<String> = setOf("fr", "en")

        /** Langue par défaut du contenu (repli international). */
        public const val LANGUE_DEFAUT: String = "en"

        /**
         * La langue de contenu est-elle connue du moteur ?
         *
         * @param langue langue demandée (BCP 47, seule la partie principale
         * est significative).
         * @return `true` si la langue est `fr` ou `en`.
         */
        public fun langueValide(langue: String): Boolean = langue.lowercase() in LANGUES_CONTENU
    }
}

/**
 * Code d'une licence dans les expressions du mini-langage.
 *
 * Forme courte et stable (`"none"`, `"mit"`, `"apache-2.0"`, `"gpl-3.0"`,
 * `"bsd-3-clause"`) : les manifestes écrivent `when: "license != \"none\""`.
 * C'est aussi le nom du fichier de référence dans `assets/licenses/`.
 *
 * @return le code de la licence, sans extension.
 */
public fun License.codeTemplate(): String =
    when (this) {
        License.NONE -> "none"
        License.MIT -> "mit"
        License.APACHE_2_0 -> "apache-2.0"
        License.GPL_3_0 -> "gpl-3.0"
        License.BSD_3_CLAUSE -> "bsd-3-clause"
    }
