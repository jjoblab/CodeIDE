package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.PlannedFile
import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.TemplateFileGroup
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplateParameterEvaluation
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.model.TemplateSummary
import jo.codeide.core.model.codeTemplate
import jo.codeide.core.model.getOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Requête interne de génération : entrées complètes d'un plan ou d'une
 * création, déjà assemblées par les cas d'usage (nom, auteur des paramètres,
 * année de l'horloge injectée, version du générateur).
 */
internal data class RequeteGeneration(
    val nom: String,
    val description: String,
    val valeursParametres: Map<String, String>,
    val modifiesManuellement: Set<String>,
    val options: TemplateOptions,
    val auteur: String,
    val annee: String,
    val versionGenerateur: String,
)

/** Résultat interne de l'évaluation des paramètres. */
internal data class EvaluationParametres(
    val visibilites: Map<String, Boolean>,
    val valeursEffectives: Map<String, String>,
    val erreurs: Map<String, String>,
    val variablesCalculees: Map<String, ExpressionValue>,
)

/**
 * Moteur de templates (étape 8 — section 11) : évaluation du formulaire,
 * construction du contexte de substitution, plan *dry-run* complet.
 *
 * Contrats clés :
 * - le plan inclut le **contenu final** (chemins rendus, textes substitués,
 *   binaires lus) — `CreateProjectUseCase` écrit exactement le plan ;
 * - un paramètre masqué vaut sa valeur par défaut, non validée, non
 *   persistée (section 11) ;
 * - les variables automatiques (`projectName`, `description`, `author`,
 *   `year`, `slug`, `packagePath`, `contentLanguage`) et les options communes
 *   (`includeReadme`, `includeGitignore`, `includeEditorconfig`, `license`)
 *   alimentent le contexte comme n'importe quel paramètre ;
 * - toute défaillance (variable inconnue, clé i18n manquante, chemin
 *   interdit) échoue explicitement — jamais de `{{…}}` résiduel.
 *
 * Les modèles fournis doivent avoir été validés par
 * [TemplateManifestParser] (contrat des [ProjectTemplateProvider]).
 *
 * Contexte d'exécution attendu : [planifier] est suspendante (lectures
 * d'assets via le port) ; le reste est pur.
 */
@Suppress("TooManyFunctions") // Évaluer, résumer, planifier : le cœur du moteur.
public class TemplateEngine
    @Inject
    constructor(
        private val assets: TemplateAssetsSource,
    ) {
        /**
         * Évalue le formulaire dynamique (section 12.2) : visibilité, valeurs
         * effectives (dérivées ou saisies), validité, libellés résolus.
         *
         * @param charge modèle chargé.
         * @param nomProjet nom du projet saisi (source des dérivations).
         * @param auteur nom d'auteur des paramètres applicatifs.
         * @param valeursParametres valeurs saisies (chaînes ; booléens
         * `true`/`false`).
         * @param modifiesManuellement identifiants modifiés à la main — les
         * autres suivent leurs sources.
         * @param langue langue de résolution des libellés (repli anglais).
         */
        @Suppress("LongParameterList") // Signature du wizard (section 12.2) : cinq entrées indépendantes.
        public fun evaluerFormulaire(
            charge: LoadedTemplate,
            nomProjet: String,
            auteur: String,
            valeursParametres: Map<String, String>,
            modifiesManuellement: Set<String>,
            langue: String,
        ): TemplateFormEvaluation {
            val evaluation =
                evaluerParametres(charge.template, nomProjet, auteur, valeursParametres, modifiesManuellement)
            val dictionnaire = charge.dictionnaireEffectif(langue)
            val parametres =
                charge.template.parameters.map { parametre ->
                    TemplateParameterEvaluation(
                        parameterId = parametre.id,
                        visible = evaluation.visibilites.getValue(parametre.id),
                        effectiveValue = evaluation.valeursEffectives.getValue(parametre.id),
                        error = evaluation.erreurs[parametre.id],
                        label = dictionnaire[parametre.labelKey] ?: parametre.labelKey,
                        help = parametre.helpKey?.let { dictionnaire[it] } ?: "",
                    )
                }
            return TemplateFormEvaluation(
                parameters = parametres,
                computedValues = evaluation.variablesCalculees.mapValues { stringifier(it.value) },
                isValid = evaluation.erreurs.isEmpty(),
            )
        }

        /**
         * Résume un modèle pour le catalogue (libellés résolus).
         *
         * Une clé absente des dictionnaires retombe sur la clé elle-même —
         * le fournisseur embarqué refuse de tels manifestes au chargement ;
         * ce repli ne protège que des fournisseurs externes distraits.
         */
        public fun resumer(
            charge: LoadedTemplate,
            langue: String,
        ): TemplateSummary {
            val dictionnaire = charge.dictionnaireEffectif(langue)
            return TemplateSummary(
                id = charge.template.id,
                nom = dictionnaire[charge.template.nameKey] ?: charge.template.nameKey,
                description = dictionnaire[charge.template.descriptionKey] ?: charge.template.descriptionKey,
                category = charge.template.category,
                iconKey = charge.template.iconKey,
                tags = charge.template.tags,
            )
        }

        /**
         * Produit le plan complet (dry-run, section 12.4) : rien n'est écrit.
         *
         * @return le plan, ou l'échec typé (`Validation` pour des entrées
         * invalides, `Template` pour un manifeste défaillant à l'exécution).
         */
        @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount") // Pipeline : un contrôle par sécurité.
        internal suspend fun planifier(
            charge: LoadedTemplate,
            requete: RequeteGeneration,
        ): AppResult<TemplatePlan> {
            val template = charge.template

            val erreurNom = TemplateValidators.valider("project-name", requete.nom)
            if (erreurNom != null) {
                return AppResult.Failure(AppError.Validation("nom de projet : $erreurNom"))
            }
            if (!TemplateOptions.langueValide(requete.options.contentLanguage)) {
                return AppResult.Failure(
                    AppError.Validation("langue de contenu inconnue « ${requete.options.contentLanguage} »"),
                )
            }

            val evaluation =
                evaluerParametres(
                    template,
                    requete.nom,
                    requete.auteur,
                    requete.valeursParametres,
                    requete.modifiesManuellement,
                )
            if (evaluation.erreurs.isNotEmpty()) {
                val details = evaluation.erreurs.entries.joinToString("; ") { "${it.key} : ${it.value}" }
                return AppResult.Failure(AppError.Validation(details))
            }

            val contexte = construireContexte(template, requete, evaluation)
            val dictionnaire = charge.dictionnaireEffectif(requete.options.contentLanguage)

            val planifies = mutableListOf<PlannedFile>()
            for (fichier in template.fichiers) {
                val conditionInclusion = fichier.whenExpression
                if (conditionInclusion != null) {
                    val incluse =
                        try {
                            evaluerBooleen(conditionInclusion, contexte)
                        } catch (erreur: Exception) {
                            return AppResult.Failure(
                                AppError.Template("when de « ${fichier.chemin} » invalide : ${erreur.message}"),
                            )
                        }
                    if (!incluse) continue
                }
                val cheminRendu =
                    try {
                        TemplateRenderer.rendreFichier(fichier.chemin, contexte, dictionnaire, fichier.chemin)
                    } catch (erreur: TemplateRenderException) {
                        return AppResult.Failure(
                            AppError.Template("chemin « ${fichier.chemin} » : ${erreur.message}"),
                        )
                    }
                val problemeChemin = TemplatePathGuard.valider(cheminRendu)
                if (problemeChemin != null) {
                    return AppResult.Failure(AppError.Template(problemeChemin))
                }

                val octets =
                    assets.readTemplateFile(template.id.value, fichier.source).getOrNull()
                        ?: return AppResult.Failure(
                            AppError.Template(
                                "source « ${fichier.source} » introuvable pour « ${template.id.value} »",
                            ),
                        )
                if (octets.size > TAILLE_ASSET_MAX) {
                    return AppResult.Failure(
                        AppError.Template("source « ${fichier.source} » trop volumineuse (${octets.size} octets)"),
                    )
                }

                val contenu: PlannedContent =
                    if (fichier.binary) {
                        PlannedContent.Binaire(octets)
                    } else {
                        val rendu =
                            try {
                                TemplateRenderer.rendreFichier(
                                    decoderUtf8Strict(octets, fichier.source),
                                    contexte,
                                    dictionnaire,
                                    fichier.source,
                                )
                            } catch (erreur: TemplateRenderException) {
                                return AppResult.Failure(AppError.Template(erreur.message ?: "rendu impossible"))
                            }
                        PlannedContent.Texte(normaliserFinsDeLigne(cheminRendu, rendu))
                    }
                planifies += PlannedFile(cheminRendu, fichier.group, contenu)
            }

            // Licence : injectée par le moteur pour tout modèle (option commune).
            if (requete.options.license != jo.codeide.core.model.License.NONE) {
                val fichierLicence = requete.options.license.codeTemplate() + EXTENSION_LICENCE
                val texteLicence = lireEtRendreLicence(fichierLicence, contexte, dictionnaire)
                val texte =
                    texteLicence.getOrNull()
                        ?: return AppResult.Failure(AppError.Template("licence $fichierLicence illisible"))
                planifies +=
                    PlannedFile(
                        CHEMIN_LICENCE,
                        TemplateFileGroup.LICENSE,
                        PlannedContent.Texte(normaliserFinsDeLigne(CHEMIN_LICENCE, texte)),
                    )
            }

            // Métadonnées du projet : toujours présentes (section 11).
            val parametresPersistes =
                template.parameters
                    .filter { it.persist && evaluation.visibilites.getValue(it.id) }
                    .map { it.id to evaluation.valeursEffectives.getValue(it.id) }
                    .toMap(LinkedHashMap())
            val metadata =
                MetadataProjetJson(
                    schemaVersion = SCHEMA_METADATA,
                    templateId = template.id.value,
                    templateVersion = template.templateVersion,
                    generator = requete.versionGenerateur,
                    parameters = parametresPersistes,
                )
            planifies +=
                PlannedFile(
                    CHEMIN_METADATA,
                    TemplateFileGroup.CORE,
                    PlannedContent.Texte(
                        JsonMetadata.encodeToString(MetadataProjetJson.serializer(), metadata) + "\n",
                    ),
                )

            val doublon = TemplatePathGuard.verifierDoublons(planifies.map { it.chemin })
            if (doublon != null) {
                return AppResult.Failure(AppError.Template(doublon))
            }
            return AppResult.Success(TemplatePlan(planifies.toList()))
        }

        /**
         * Lit et rend un texte de licence officiel : MIT et BSD substituent
         * `{{year}}` et `{{author}}` comme n'importe quel template (échecs
         * explicites, jamais un `null` silencieux).
         */
        @Suppress("ReturnCount") // Trois issues : absent, trop volumineux, illisible (règle 16).
        private suspend fun lireEtRendreLicence(
            fichierLicence: String,
            contexte: ContexteExpressions,
            dictionnaire: Map<String, String>,
        ): AppResult<String> {
            val octets =
                assets.readLicenseFile(fichierLicence).getOrNull()
                    ?: return AppResult.Failure(
                        AppError.Template("texte de licence introuvable : $fichierLicence"),
                    )
            if (octets.size > TAILLE_LICENCE_MAX) {
                return AppResult.Failure(AppError.Template("texte de licence $fichierLicence trop volumineux"))
            }
            return try {
                AppResult.Success(
                    TemplateRenderer.rendreFichier(
                        decoderUtf8Strict(octets, fichierLicence),
                        contexte,
                        dictionnaire,
                        fichierLicence,
                    ),
                )
            } catch (erreur: TemplateRenderException) {
                AppResult.Failure(AppError.Template(erreur.message ?: "licence illisible"))
            }
        }

        // ------------------------------------------------------------ privé

        /** Évalue visibilité, valeurs effectives, validité et variables calculées. */
        @Suppress("LongMethod", "CyclomaticComplexMethod", "SwallowedException") // Passes documentées.
        private fun evaluerParametres(
            template: ProjectTemplate,
            nomProjet: String,
            auteur: String,
            valeurs: Map<String, String>,
            modifiesManuellement: Set<String>,
        ): EvaluationParametres {
            // Passe 1 : valeurs effectives sans dérivation (défauts et saisies).
            val effectives = LinkedHashMap<String, String>()
            template.parameters.forEach { parametre ->
                effectives[parametre.id] =
                    when {
                        parametre.id in modifiesManuellement -> {
                            valeurs[parametre.id] ?: parametre.defaultValue ?: defautDeType(parametre)
                        }

                        parametre.defaultFrom == null -> {
                            valeurs[parametre.id] ?: parametre.defaultValue ?: defautDeType(parametre)
                        }

                        else -> {
                            ""
                        } // recalculée en passe 2
                    }
            }

            // Passe 2 : valeurs dérivées (defaultFrom) dans l'ordre du manifeste
            // — les sources sont relues à chaque paramètre, une dérivée doit
            // donc être déclarée APRÈS sa source (docs/TEMPLATES.md).
            template.parameters.forEach { parametre ->
                val derivee = parametre.defaultFrom
                if (parametre.id !in modifiesManuellement && derivee != null) {
                    val sources =
                        TemplateDefaultFunctions.Sources(
                            nomProjet = nomProjet,
                            auteur = auteur,
                            nomPackage = effectives[NOM_PARAMETRE_PACKAGE] ?: "",
                        )
                    effectives[parametre.id] = TemplateDefaultFunctions.appliquer(derivee, sources)
                }
            }

            // Passe 3 : variables calculées du manifeste (ordre déclaré) —
            // chacune voit les paramètres et les calculées précédentes.
            val calculees = LinkedHashMap<String, ExpressionValue>()
            val contexteCalcul = contexteBrut(template, nomProjet, effectives, calculees)
            template.computedVariables.forEach { variable ->
                val valeur =
                    try {
                        ExpressionEvaluator.evaluer(ExpressionParser.analyser(variable.expression), contexteCalcul)
                    } catch (erreur: Exception) {
                        throw TemplateManifestException(
                            "variable calculée « ${variable.name} » : ${erreur.message}",
                        )
                    }
                calculees[variable.name] = valeur
                contexteCalcul[variable.name] = valeur
            }

            // Passe 4 : visibilité (paramètres, automatiques et calculées).
            val visibilites =
                template.parameters.associate { parametre ->
                    val condition = parametre.visibleWhen
                    val visible =
                        condition == null ||
                            (
                                ExpressionEvaluator.evaluer(
                                    ExpressionParser.analyser(condition),
                                    contexteCalcul,
                                ) as? ExpressionValue.Booleen
                            )?.valeur ?: throw TemplateManifestException(
                                "visibleWhen de « ${parametre.id} » doit être booléen",
                            )
                    parametre.id to visible
                }

            // Passe 5 : validation des paramètres visibles uniquement.
            val erreurs = LinkedHashMap<String, String>()
            template.parameters.forEach { parametre ->
                if (!visibilites.getValue(parametre.id)) return@forEach
                val valeur = effectives.getValue(parametre.id)
                val erreurType = validerType(parametre, valeur)
                if (erreurType != null) {
                    erreurs[parametre.id] = erreurType
                    return@forEach
                }
                val validateur = parametre.validator ?: return@forEach
                val erreur = TemplateValidators.valider(validateur, valeur)
                if (erreur != null) erreurs[parametre.id] = erreur
            }

            // Les paramètres masqués retombent sur leur valeur par défaut
            // (section 11 : ignorés — non validés, non persistés).
            template.parameters.forEach { parametre ->
                if (!visibilites.getValue(parametre.id)) {
                    effectives[parametre.id] = parametre.defaultValue ?: defautDeType(parametre)
                }
            }

            return EvaluationParametres(visibilites, effectives, erreurs, calculees)
        }

        /** Valeur par défaut d'un type quand le manifeste n'en fournit pas. */
        private fun defautDeType(parametre: jo.codeide.core.model.TemplateParameter): String =
            when (parametre.type) {
                jo.codeide.core.model.TemplateParameterType.BOOLEAN -> "false"
                jo.codeide.core.model.TemplateParameterType.CHOICE -> parametre.choices.firstOrNull() ?: ""
                jo.codeide.core.model.TemplateParameterType.TEXT -> ""
            }

        /** Contrôle de type d'une valeur saisie (avant validateur nommé). */
        private fun validerType(
            parametre: jo.codeide.core.model.TemplateParameter,
            valeur: String,
        ): String? =
            when (parametre.type) {
                jo.codeide.core.model.TemplateParameterType.BOOLEAN -> {
                    if (valeur.lowercase() in setOf("true", "false")) {
                        null
                    } else {
                        "valeur booléenne attendue (« true » ou « false »), reçu « $valeur »"
                    }
                }

                jo.codeide.core.model.TemplateParameterType.CHOICE -> {
                    if (valeur in parametre.choices) {
                        null
                    } else {
                        "valeur hors des choix possibles : « $valeur »"
                    }
                }

                jo.codeide.core.model.TemplateParameterType.TEXT -> {
                    null
                }
            }

        /** Contexte des calculs et de la visibilité (sans les options). */
        private fun contexteBrut(
            template: ProjectTemplate,
            nomProjet: String,
            effectives: Map<String, String>,
            calculees: Map<String, ExpressionValue>,
        ): MutableMap<String, ExpressionValue> {
            val contexte = linkedMapOf<String, ExpressionValue>()
            val types = template.parameters.associate { it.id to it.type }
            effectives.forEach { (id, valeur) ->
                contexte[id] = valeurTypee(types[id], valeur)
            }
            calculees.forEach { (nom, valeur) -> contexte[nom] = valeur }
            contexte[NOM_VARIABLE_NOM] = ExpressionValue.Chaine(nomProjet)
            contexte[NOM_VARIABLE_SLUG] = ExpressionValue.Chaine(TemplateFilters.slug(nomProjet))
            return contexte
        }

        /** Contexte complet de rendu : paramètres + automatiques + options + calculées. */
        private fun construireContexte(
            template: ProjectTemplate,
            requete: RequeteGeneration,
            evaluation: EvaluationParametres,
        ): ContexteExpressions {
            val contexte = linkedMapOf<String, ExpressionValue>()
            val types = template.parameters.associate { it.id to it.type }
            evaluation.valeursEffectives.forEach { (id, valeur) ->
                contexte[id] = valeurTypee(types[id], valeur)
            }
            evaluation.variablesCalculees.forEach { (nom, valeur) -> contexte[nom] = valeur }
            val nomPackage = evaluation.valeursEffectives[NOM_PARAMETRE_PACKAGE]
            contexte[NOM_VARIABLE_NOM] = ExpressionValue.Chaine(requete.nom)
            contexte[NOM_VARIABLE_DESCRIPTION] = ExpressionValue.Chaine(requete.description)
            contexte[NOM_VARIABLE_AUTEUR] = ExpressionValue.Chaine(requete.auteur)
            contexte[NOM_VARIABLE_ANNEE] = ExpressionValue.Chaine(requete.annee)
            contexte[NOM_VARIABLE_SLUG] = ExpressionValue.Chaine(TemplateFilters.slug(requete.nom))
            contexte[NOM_VARIABLE_CHEMIN_PACKAGE] =
                ExpressionValue.Chaine(nomPackage?.replace('.', '/') ?: "")
            contexte[NOM_VARIABLE_LANGUE_CONTENU] = ExpressionValue.Chaine(requete.options.contentLanguage)
            contexte[NOM_OPTION_README] = ExpressionValue.Booleen(requete.options.includeReadme)
            contexte[NOM_OPTION_GITIGNORE] = ExpressionValue.Booleen(requete.options.includeGitignore)
            contexte[NOM_OPTION_EDITORCONFIG] = ExpressionValue.Booleen(requete.options.includeEditorconfig)
            contexte[NOM_OPTION_LICENCE] = ExpressionValue.Chaine(requete.options.license.codeTemplate())
            return contexte
        }

        /** Un paramètre BOOLEAN est typé booléen dans les expressions. */
        private fun valeurTypee(
            type: jo.codeide.core.model.TemplateParameterType?,
            valeur: String,
        ): ExpressionValue =
            if (type == jo.codeide.core.model.TemplateParameterType.BOOLEAN) {
                ExpressionValue.Booleen(valeur.lowercase() == "true")
            } else {
                ExpressionValue.Chaine(valeur)
            }

        /** Évalue une expression en exigeant un booléen. */
        private fun evaluerBooleen(
            expression: String,
            contexte: ContexteExpressions,
        ): Boolean {
            val valeur = ExpressionEvaluator.evaluer(ExpressionParser.analyser(expression), contexte)
            if (valeur !is ExpressionValue.Booleen) {
                throw TemplateRenderException(0, "expression non booléenne : $expression")
            }
            return valeur.valeur
        }

        /** Décode en UTF-8 strict — un fichier corrompu échoue explicitement. */
        @Suppress("SwallowedException") // Transformée en échec explicite de décodage UTF-8.
        private fun decoderUtf8Strict(
            octets: ByteArray,
            nom: String,
        ): String =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(octets))
                    .toString()
            } catch (erreur: java.nio.charset.CharacterCodingException) {
                throw TemplateRenderException(0, "« $nom » n'est pas de l'UTF-8 valide")
            }

        /**
         * Normalise le texte rendu : BOM retiré, fins de ligne unifiées en
         * LF — sauf `.bat` (CRLF, exigence de l'étape 9).
         */
        private fun normaliserFinsDeLigne(
            chemin: String,
            texte: String,
        ): String {
            val sansBom = texte.removePrefix("\uFEFF")
            val unifie = sansBom.replace("\r\n", "\n").replace('\r', '\n')
            return if (chemin.endsWith(".bat")) unifie.replace("\n", "\r\n") else unifie
        }

        private fun stringifier(valeur: ExpressionValue): String =
            when (valeur) {
                is ExpressionValue.Chaine -> valeur.valeur
                is ExpressionValue.Booleen -> valeur.valeur.toString()
            }

        /** Métadonnées `.codeide/project.json` (section 11 — pas de données personnelles). */
        @Serializable
        private data class MetadataProjetJson(
            val schemaVersion: Int,
            val templateId: String,
            val templateVersion: String,
            val generator: String,
            @SerialName("parameters")
            val parameters: Map<String, String>,
        )

        internal companion object {
            /** Taille maximale d'un asset de modèle lu (texte ou binaire). */
            internal const val TAILLE_ASSET_MAX = 8 * 1024 * 1024

            /** Taille maximale d'un texte de licence. */
            internal const val TAILLE_LICENCE_MAX = 256 * 1024

            /** Schéma courant de `.codeide/project.json`. */
            internal const val SCHEMA_METADATA = 1

            private const val CHEMIN_LICENCE = "LICENSE"
            private const val CHEMIN_METADATA = ".codeide/project.json"
            private const val EXTENSION_LICENCE = ".txt"

            private const val NOM_VARIABLE_NOM = "projectName"
            private const val NOM_VARIABLE_DESCRIPTION = "description"
            private const val NOM_VARIABLE_AUTEUR = "author"
            private const val NOM_VARIABLE_ANNEE = "year"
            private const val NOM_VARIABLE_SLUG = "slug"
            private const val NOM_VARIABLE_CHEMIN_PACKAGE = "packagePath"
            private const val NOM_VARIABLE_LANGUE_CONTENU = "contentLanguage"
            private const val NOM_OPTION_README = "includeReadme"
            private const val NOM_OPTION_GITIGNORE = "includeGitignore"
            private const val NOM_OPTION_EDITORCONFIG = "includeEditorconfig"
            private const val NOM_OPTION_LICENCE = "license"

            /** Nom conventionnel du paramètre de package (variable `packagePath`). */
            private const val NOM_PARAMETRE_PACKAGE = "packageName"

            private val JsonMetadata =
                Json {
                    prettyPrint = true
                }
        }
    }
