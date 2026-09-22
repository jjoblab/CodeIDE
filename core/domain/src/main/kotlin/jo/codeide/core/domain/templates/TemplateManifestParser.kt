package jo.codeide.core.domain.templates

import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.TemplateComputedVariable
import jo.codeide.core.model.TemplateFile
import jo.codeide.core.model.TemplateFileGroup
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateParameter
import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.model.TemplateSection

/**
 * Analyseur des manifestes déclaratifs (étape 8 — section 11).
 *
 * Transforme `template.json` (DTO [ManifesteTemplateJson]) en
 * [ProjectTemplate] **après validation complète** : schéma, identifiants,
 * types, validateurs nommés, fonctions `defaultFrom` enregistrées,
 * expressions analysables, bornes de taille. Un manifeste qui passe ici est
 * utilisable sans autre contrôle syntaxique — le rendu ne peut plus échouer
 * que sur des valeurs (identifiant inconnu à l'exécution, clé i18n absente
 * pour la langue demandée), toujours explicitement.
 */
internal object TemplateManifestParser {
    /** Seule version de schéma comprise par cette version du moteur. */
    internal const val SCHEMA_VERSION = 1

    /** Bornes de taille (manifestes embarqués : générés par nos soins). */
    private const val PARAMETRES_MAX = 32
    private const val VARIABLES_CALCULEES_MAX = 32
    private const val FICHIERS_MAX = 256
    private const val TAGS_MAX = 12

    /** Motif d'un identifiant de modèle (répertoire d'assets). */
    private val MOTIF_ID_MODELE = Regex("^[a-z][a-z0-9-]*$")

    /** Motif d'un identifiant de paramètre ou de variable. */
    private val MOTIF_IDENTIFIANT = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    /** Motif d'une clé i18n. */
    private val MOTIF_CLE_I18N = Regex("^[a-zA-Z0-9_.-]+$")

    /**
     * Variables automatiques et options communes du moteur : un paramètre ou
     * une variable calculée ne peut pas les masquer (section 11).
     */
    private val NOMS_RESERVES_MOTEUR =
        setOf(
            "projectName",
            "description",
            "author",
            "year",
            "slug",
            "packagePath",
            "contentLanguage",
            "includeReadme",
            "includeGitignore",
            "includeEditorconfig",
            "license",
        )

    /**
     * Analyse et valide un manifeste.
     *
     * @param octets contenu brut de `template.json` (UTF-8).
     * @param idRepertoire identifiant du répertoire du modèle — doit
     * correspondre au champ `id` (un manifeste ne peut pas se faire passer
     * pour un autre).
     * @return le modèle validé.
     * @throws TemplateManifestException en cas d'erreur, message explicite.
     */
    @Suppress("SwallowedException") // Illisible -> TemplateManifestException explicite (jamais avalée).
    fun analyser(
        octets: ByteArray,
        idRepertoire: String,
    ): ProjectTemplate {
        val texte = decoderUtf8Strict(octets)
        val manifeste =
            try {
                JsonManifestes.decodeFromString(ManifesteTemplateJson.serializer(), texte)
            } catch (erreur: IllegalArgumentException) {
                throw TemplateManifestException("template.json de « $idRepertoire » illisible : ${erreur.message}")
            }
        val probleme = valider(manifeste, idRepertoire)
        if (probleme != null) {
            throw TemplateManifestException("template.json de « $idRepertoire » invalide : $probleme")
        }
        return convertir(manifeste)
    }

    /** Décode en UTF-8 strict — un manifeste corrompu échoue explicitement. */
    @Suppress("SwallowedException") // Transformée en échec explicite de décodage UTF-8.
    private fun decoderUtf8Strict(octets: ByteArray): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(octets))
                .toString()
        } catch (erreur: java.nio.charset.CharacterCodingException) {
            throw TemplateManifestException("manifeste non décodable en UTF-8 : ${erreur.message}")
        }

    /** Première violation de règle, ou `null` si le manifeste est sain. */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // Clauses de garde (règle 16).
    private fun valider(
        manifeste: ManifesteTemplateJson,
        idRepertoire: String,
    ): String? {
        if (manifeste.schemaVersion != SCHEMA_VERSION) {
            return "schemaVersion ${manifeste.schemaVersion} non supporté (attendu : $SCHEMA_VERSION)"
        }
        if (!MOTIF_ID_MODELE.matches(manifeste.id)) {
            return "id « ${manifeste.id} » invalide : minuscule initiale puis minuscules, chiffres et tirets"
        }
        if (manifeste.id != idRepertoire) {
            return "id « ${manifeste.id} » différent du répertoire « $idRepertoire »"
        }
        if (TemplateValidators.valider("semver", manifeste.templateVersion) != null) {
            return "templateVersion « ${manifeste.templateVersion} » n'est pas une version SemVer valide"
        }
        listOf(
            Triple(manifeste.nameKey, "nameKey", "nom"),
            Triple(manifeste.descriptionKey, "descriptionKey", "description"),
            Triple(manifeste.iconKey, "iconKey", "icône"),
        ).forEach { (cle, champ, role) ->
            if (!MOTIF_CLE_I18N.matches(cle)) return "clé i18n de $role ($champ) invalide : « $cle »"
        }
        if (manifeste.category.isBlank()) return "catégorie vide"
        if (manifeste.tags.size > TAGS_MAX) return "plus de $TAGS_MAX tags"
        if (manifeste.parameters.size > PARAMETRES_MAX) return "plus de $PARAMETRES_MAX paramètres"
        if (manifeste.computed.size >
            VARIABLES_CALCULEES_MAX
        ) {
            return "plus de $VARIABLES_CALCULEES_MAX variables calculées"
        }
        if (manifeste.files.size > FICHIERS_MAX) return "plus de $FICHIERS_MAX fichiers"

        val idsParameters = mutableSetOf<String>()
        for (parametre in manifeste.parameters) {
            val probleme = validerParametre(parametre)
            if (probleme != null) return probleme
            if (parametre.id in NOMS_RESERVES_MOTEUR) {
                return "paramètre « ${parametre.id} » masque une variable automatique du moteur"
            }
            if (!idsParameters.add(parametre.id)) return "paramètre en double : « ${parametre.id} »"
        }
        val nomsCalcules = mutableSetOf<String>()
        for (variable in manifeste.computed) {
            if (!MOTIF_IDENTIFIANT.matches(variable.name)) {
                return "nom de variable calculée invalide : « ${variable.name} »"
            }
            if (variable.name in NOMS_RESERVES_MOTEUR) {
                return "variable calculée « ${variable.name} » masque une variable automatique du moteur"
            }
            if (variable.name in idsParameters) {
                return "variable calculée « ${variable.name} » masque un paramètre du même nom"
            }
            if (!nomsCalcules.add(variable.name)) return "variable calculée en double : « ${variable.name} »"
            try {
                ExpressionParser.analyser(variable.expression)
            } catch (erreur: ExpressionException) {
                return "expression de « ${variable.name} » invalide : ${erreur.message}"
            }
        }

        val cheminsStatiques = mutableSetOf<String>()
        for (fichier in manifeste.files) {
            val probleme = validerFichier(fichier)
            if (probleme != null) return probleme
            // Un chemin sans balise est déjà contrôlable ici ; les chemins
            // templatisés sont revus après substitution par la garde.
            if (!fichier.path.contains("{{") && cheminsStatiques.add(fichier.path.lowercase()).not()) {
                return "chemin de fichier en double : « ${fichier.path} »"
            }
        }
        return null
    }

    /** Règles d'un paramètre du manifeste. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Clauses de garde de validation (règle 16).
    private fun validerParametre(parametre: ParametreTemplateJson): String? {
        if (!MOTIF_IDENTIFIANT.matches(parametre.id)) {
            return "identifiant de paramètre invalide : « ${parametre.id} »"
        }
        val type =
            runCatching { TemplateParameterType.valueOf(parametre.type) }
                .getOrNull()
                ?: return "type de paramètre inconnu : « ${parametre.type} »"
        if (!MOTIF_CLE_I18N.matches(parametre.labelKey)) {
            return "labelKey invalide pour « ${parametre.id} » : « ${parametre.labelKey} »"
        }
        if (parametre.helpKey != null && !MOTIF_CLE_I18N.matches(parametre.helpKey)) {
            return "helpKey invalide pour « ${parametre.id} » : « ${parametre.helpKey} »"
        }
        if (type == TemplateParameterType.CHOICE) {
            if (parametre.choices.isEmpty()) return "« ${parametre.id} » : un CHOICE exige des valeurs"
            if (parametre.choices.size != parametre.choices.toSet().size) {
                return "« ${parametre.id} » : valeurs CHOICE en double"
            }
            if (parametre.default != null && parametre.default !in parametre.choices) {
                return "« ${parametre.id} » : défaut « ${parametre.default} » hors des valeurs CHOICE"
            }
        }
        if (type == TemplateParameterType.BOOLEAN && parametre.default != null &&
            parametre.default.lowercase() !in setOf("true", "false")
        ) {
            return "« ${parametre.id} » : défaut BOOLEAN invalide « ${parametre.default} »"
        }
        if (parametre.defaultFrom != null && parametre.defaultFrom !in TemplateDefaultFunctions.NOMS) {
            return "« ${parametre.id} » : fonction defaultFrom inconnue « ${parametre.defaultFrom} »"
        }
        if (parametre.validator != null && !TemplateValidators.nomConnu(parametre.validator)) {
            return "« ${parametre.id} » : validateur inconnu « ${parametre.validator} »"
        }
        if (parametre.validator?.startsWith("regex:") == true) {
            val motif = parametre.validator.removePrefix("regex:")
            try {
                Regex(motif)
            } catch (erreur: IllegalArgumentException) {
                return "« ${parametre.id} » : motif de regex invalide : ${erreur.message}"
            }
        }
        if (parametre.visibleWhen != null) {
            try {
                ExpressionParser.analyser(parametre.visibleWhen)
            } catch (erreur: ExpressionException) {
                return "visibleWhen de « ${parametre.id} » invalide : ${erreur.message}"
            }
        }
        runCatching { TemplateSection.valueOf(parametre.section) }
            .getOrNull()
            ?: return "section inconnue « ${parametre.section} » pour « ${parametre.id} »"
        return null
    }

    /** Règles d'une entrée de fichier du manifeste. */
    @Suppress("ReturnCount") // Clauses de garde de validation (règle 16).
    private fun validerFichier(fichier: FichierTemplateJson): String? {
        if (fichier.path.isBlank()) return "chemin de fichier vide"
        if (fichier.path.startsWith("/")) return "chemin absolu interdit : « ${fichier.path} »"
        if (fichier.path.contains('\\')) return "séparateur « \\ » interdit : « ${fichier.path} »"
        if (fichier.path.split("/").any { it == ".." || it == "." }) {
            return "segment « . »/« .. » interdit : « ${fichier.path} »"
        }
        if (fichier.source.isBlank()) return "source vide pour « ${fichier.path} »"
        if (fichier.source.startsWith("/") || fichier.source.contains("..") || fichier.source.contains('\\')) {
            return "source hors du répertoire du modèle interdite : « ${fichier.source} »"
        }
        if (fichier.whenExpr != null) {
            try {
                ExpressionParser.analyser(fichier.whenExpr)
            } catch (erreur: ExpressionException) {
                return "when de « ${fichier.path} » invalide : ${erreur.message}"
            }
        }
        val groupes = TemplateFileGroup.entries.map { it.name.lowercase() }
        if (fichier.group.lowercase() !in groupes) {
            return "groupe inconnu « ${fichier.group} » pour « ${fichier.path} »"
        }
        return null
    }

    /** Conversion DTO validé → modèle public. */
    private fun convertir(manifeste: ManifesteTemplateJson): ProjectTemplate =
        ProjectTemplate(
            id = TemplateId(manifeste.id),
            templateVersion = manifeste.templateVersion,
            nameKey = manifeste.nameKey,
            descriptionKey = manifeste.descriptionKey,
            category = manifeste.category,
            iconKey = manifeste.iconKey,
            tags = manifeste.tags,
            parameters =
                manifeste.parameters.map { parametre ->
                    TemplateParameter(
                        id = parametre.id,
                        type = TemplateParameterType.valueOf(parametre.type),
                        labelKey = parametre.labelKey,
                        helpKey = parametre.helpKey,
                        choices = parametre.choices,
                        defaultValue = parametre.default,
                        defaultFrom = parametre.defaultFrom,
                        validator = parametre.validator,
                        visibleWhen = parametre.visibleWhen,
                        section = TemplateSection.valueOf(parametre.section),
                        persist = parametre.persist,
                    )
                },
            computedVariables =
                manifeste.computed.map { variable ->
                    TemplateComputedVariable(
                        name = variable.name,
                        expression = variable.expression,
                    )
                },
            fichiers =
                manifeste.files.map { fichier ->
                    TemplateFile(
                        chemin = fichier.path,
                        source = fichier.source,
                        binary = fichier.binary,
                        whenExpression = fichier.whenExpr,
                        group =
                            TemplateFileGroup.entries
                                .first { it.name.lowercase() == fichier.group.lowercase() },
                    )
                },
        )
}
