package jo.codeide.core.domain.templates

import jo.codeide.core.model.RaisonValidation

/**
 * Validateurs nommés des paramètres de modèle (étape 8 — section 11).
 *
 * Noms enregistrés : `project-name` (règles du nom de projet, section 12.3),
 * `package-name`, `identifier`, `semver`, et la forme paramétrée
 * `regex:<motif>` (correspondance **complète**). Tout autre nom est refusé au
 * chargement du manifeste — pas de validateur implicite.
 *
 * Les messages sont en français, destinés aux journaux et au bloc « copier
 * les détails » ; l'UI (étape 10) les remplacera par des ressources.
 */
internal object TemplateValidators {
    /** Noms de validateurs non paramétrés reconnus. */
    val NOMS: Set<String> = setOf("project-name", "file-name", "package-name", "identifier", "semver")

    /** Préfixe de la forme paramétrée. */
    private const val PREFIXE_REGEX = "regex:"

    /** Dernier numéro de série historique (COM1-9, LPT1-9). */
    private const val DERNIER_NUMERO_SERIE = 9

    /** Numéros de série historiques (COM1-9, LPT1-9). */
    private val NUMEROS_SERIE_WINDOWS = 1..DERNIER_NUMERO_SERIE

    /** Point de sortie Unicode des caractères de contrôle. */
    private const val POINT_CONTROLE = 0x20

    /** Longueur maximale d'un nom de projet après trim (section 12.3). */
    private const val LONGUEUR_NOM_MAX = 64

    /** Longueur minimale d'un nom de projet après trim. */
    private const val LONGUEUR_NOM_MIN = 1

    /** Motif SemVer 2.0.0 complet (préversion et métadonnées incluses). */
    private val MOTIF_SEMVER =
        Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)" +
                "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
                "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$",
        )

    /** Segments de nom de package : minuscule initiale, puis minuscules/chiffres/underscore. */
    private val MOTIF_SEGMENT_PACKAGE = Regex("^[a-z][a-z0-9_]*$")

    /** Identifiant générique : lettre ou underscore, puis alphanumériques/underscores. */
    private val MOTIF_IDENTIFIANT = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    /** Caractères interdits dans un nom de projet (section 12.3 + contrôle). */
    private const val CARACTERES_INTERDITS_NOM = "/\\:*?\"<>|"

    /** Noms réservés Windows — les projets peuvent être copiés vers un PC. */
    private val NOMS_RESERVES_WINDOWS =
        setOf("CON", "PRN", "AUX", "NUL") +
            NUMEROS_SERIE_WINDOWS.map { "COM$it" } +
            NUMEROS_SERIE_WINDOWS.map { "LPT$it" }

    /**
     * Mots-clés Java et Kotlin refusés comme segments de package ou
     * identifiants (union des deux langages, minuscules).
     */
    private val MOTS_CLES =
        setOf(
            // Java.
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            // Kotlin.
            "as",
            "by",
            "constructor",
            "crossinline",
            "data",
            "dynamic",
            "expect",
            "external",
            "field",
            "fun",
            "infix",
            "init",
            "inline",
            "inner",
            "in",
            "internal",
            "is",
            "lateinit",
            "noinline",
            "object",
            "open",
            "out",
            "override",
            "reified",
            "sealed",
            "tailrec",
            "typealias",
            "val",
            "var",
            "vararg",
            "when",
            "where",
            "actual",
            "annotation",
            "companion",
            "const",
            "suspend",
            "value",
            // Littéraux.
            "true",
            "false",
            "null",
        )

    /** Nom de validateur syntaxiquement valide (forme ou paramétrage) ? */
    fun nomConnu(nom: String): Boolean =
        nom in NOMS || (nom.startsWith(PREFIXE_REGEX) && nom.length > PREFIXE_REGEX.length)

    /**
     * Échec d'une validation : **raison typée** pour l'interface (étape 10,
     * ressources localisées) et **message français** pour les journaux et le
     * bloc « copier les détails ».
     */
    internal data class EchecValidation(
        val raison: RaisonValidation,
        val message: String,
    )

    /**
     * Évalue [valeur] avec le validateur [nom] (déjà vérifié par [nomConnu]).
     *
     * @return `null` si la valeur est valide, sinon l'échec structuré
     * (raison typée + message français).
     */
    fun evaluer(
        nom: String,
        valeur: String,
    ): EchecValidation? =
        when {
            nom == "project-name" -> {
                validerNomProjet(valeur)
            }

            // Règles du nom de fichier/dossier de l'explorateur (étape 17) :
            // les mêmes que le nom de projet (section 12.3 — transportables
            // vers Windows), le nom de fichier est un simple segment.
            nom == "file-name" -> {
                validerNomProjet(valeur)
            }

            nom == "package-name" -> {
                validerNomPackage(valeur)
            }

            nom == "identifier" -> {
                validerIdentifiant(valeur)
            }

            nom == "semver" -> {
                validerSemver(valeur)
            }

            nom.startsWith(PREFIXE_REGEX) -> {
                validerRegex(valeur, nom)
            }

            else -> {
                EchecValidation(
                    RaisonValidation.ValeurInterdite(nom),
                    "validateur inconnu « $nom »",
                )
            }
        }

    /**
     * Valide [valeur] avec le validateur [nom] (forme historique, journaux).
     *
     * @return `null` si la valeur est valide, sinon le message d'erreur
     * français (jamais vide).
     */
    fun valider(
        nom: String,
        valeur: String,
    ): String? = evaluer(nom, valeur)?.message

    /** Règles du nom de projet (section 12.3, transportables vers Windows). */
    @Suppress("ReturnCount") // Une clause par règle du nom (section 12.3, règle 16).
    private fun validerNomProjet(valeur: String): EchecValidation? {
        val nom = valeur.trim()
        if (nom.length !in LONGUEUR_NOM_MIN..LONGUEUR_NOM_MAX) {
            return EchecValidation(
                RaisonValidation.LongueurNom,
                "le nom doit contenir de $LONGUEUR_NOM_MIN à $LONGUEUR_NOM_MAX caractères après trim",
            )
        }
        val fautif = nom.firstOrNull { it in CARACTERES_INTERDITS_NOM || it.code < POINT_CONTROLE }
        if (fautif != null) {
            return EchecValidation(
                RaisonValidation.CaractereInterditNom(fautif),
                "le caractère « $fautif » est interdit dans un nom de projet",
            )
        }
        if (nom == "." || nom == "..") {
            return EchecValidation(
                RaisonValidation.PointsFictifsNom,
                "« . » et « .. » ne sont pas des noms de projet valides",
            )
        }
        if (nom.endsWith(".") || nom.endsWith(" ")) {
            return EchecValidation(
                RaisonValidation.FinNomInterdite,
                "le nom ne peut pas se terminer par un point ni un espace",
            )
        }
        if (nom.uppercase() in NOMS_RESERVES_WINDOWS) {
            return EchecValidation(
                RaisonValidation.NomReserveWindows,
                "« $nom » est un nom réservé par Windows",
            )
        }
        return null
    }

    /** Nom de package Java/Kotlin : segments, mots-clés refusés. */
    @Suppress("ReturnCount") // Une clause par règle du package (règle 16).
    private fun validerNomPackage(valeur: String): EchecValidation? {
        val segments = valeur.split(".")
        if (valeur.isBlank() || segments.any { it.isEmpty() }) {
            return EchecValidation(
                RaisonValidation.PackageVideOuSegmentVide,
                "le nom de package ne peut pas être vide ni contenir de segment vide",
            )
        }
        segments.forEach { segment ->
            if (!MOTIF_SEGMENT_PACKAGE.matches(segment)) {
                return EchecValidation(
                    RaisonValidation.SegmentPackageInvalide(segment),
                    "segment « $segment » invalide : minuscule initiale puis minuscules, chiffres et underscores",
                )
            }
            if (segment in MOTS_CLES) {
                return EchecValidation(
                    RaisonValidation.MotClePackage(segment),
                    "segment « $segment » : mot-clé Java/Kotlin réservé",
                )
            }
        }
        return null
    }

    /** Identifiant générique (champ, variable, artefact sans tiret…). */
    @Suppress("ReturnCount") // Une clause par règle de l'identifiant (règle 16).
    private fun validerIdentifiant(valeur: String): EchecValidation? {
        if (!MOTIF_IDENTIFIANT.matches(valeur)) {
            return EchecValidation(
                RaisonValidation.IdentifiantInvalide(valeur),
                "identifiant invalide : lettre ou underscore initial, puis alphanumériques et underscores",
            )
        }
        if (valeur in MOTS_CLES) {
            return EchecValidation(
                RaisonValidation.MotCleIdentifiant(valeur),
                "« $valeur » : mot-clé Java/Kotlin réservé",
            )
        }
        return null
    }

    /** Version SemVer 2.0.0. */
    private fun validerSemver(valeur: String): EchecValidation? {
        if (!MOTIF_SEMVER.matches(valeur)) {
            return EchecValidation(
                RaisonValidation.VersionInvalide,
                "version invalide : format SemVer attendu (ex. « 0.1.0 », « 1.0.0-rc.1 »)",
            )
        }
        return null
    }

    /** Correspondance complète avec le motif de `regex:<motif>`. */
    @Suppress("ReturnCount") // Motif invalide puis correspondance (règle 16).
    private fun validerRegex(
        valeur: String,
        nom: String,
    ): EchecValidation? {
        val motif =
            try {
                Regex(nom.removePrefix(PREFIXE_REGEX))
            } catch (erreur: IllegalArgumentException) {
                return EchecValidation(
                    RaisonValidation.ValeurInterdite(nom),
                    "motif de validateur invalide : ${erreur.message}",
                )
            }
        if (!motif.matches(valeur)) {
            return EchecValidation(
                RaisonValidation.RegexNonCorrespondance(nom.removePrefix(PREFIXE_REGEX)),
                "valeur ne correspond pas au motif « ${nom.removePrefix(PREFIXE_REGEX)} »",
            )
        }
        return null
    }
}
