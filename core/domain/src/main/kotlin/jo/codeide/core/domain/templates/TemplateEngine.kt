package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ChoixEvalue
import jo.codeide.core.model.EtatParametre
import jo.codeide.core.model.MessageRegle
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.PlannedFile
import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.SeveriteRegle
import jo.codeide.core.model.TemplateEtapeEvaluee
import jo.codeide.core.model.TemplateFileGroup
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplateParameterEvaluation
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.model.TemplatePresetResolu
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
 *
 * @property renommages renommages de l'aperçu (phase 4, ADR 0077) : clé =
 * chemin original du nœud (fichier exact ou préfixe de dossier), valeur =
 * nouveau nom **du segment** — jamais un chemin complet.
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
    val renommages: Map<String, String> = emptyMap(),
    val plateformeMajeure: Int = 0,
)

/** Résultat interne de l'évaluation des paramètres. */
internal data class EvaluationParametres(
    val visibilites: Map<String, Boolean>,
    val valeursEffectives: Map<String, String>,
    val erreurs: Map<String, TemplateValidators.EchecValidation>,
    val variablesCalculees: Map<String, ExpressionValue>,
    val etats: Map<String, EtatParametre>,
    val messages: List<MessageRegle>,
    val etapesEffectives: List<EtapeEffective> = emptyList(),
)

/**
 * Étape de modèle effective calculée à la passe 10 (ADR 0155 §3) —
 * identifiant brut et paramètres cités dans l'ordre déclaré ; le titre et
 * l'icône sont résolus au retour public ([TemplateEtapeEvaluee]).
 */
internal data class EtapeEffective(val id: String, val parametres: List<String>, val defaut: Boolean)

/**
 * Moteur de templates (étape 8 — section 11) : évaluation du formulaire,
 * construction du contexte de substitution, plan *dry-run* complet.
 *
 * Contrats clés :
 * - le plan inclut le **contenu final** (chemins rendus, textes substitués,
 *   binaires lus) — `CreateProjectUseCase` écrit exactement le plan ;
 * - un paramètre masqué **ou désactivé** (`enabledWhen` faux, ADR 0153 §2)
 *   vaut sa valeur par défaut, non validée, non persistée ;
 * - les règles de projet (`constraints`) sont évaluées sur la combinaison
 *   finale : une règle `ERREUR` rend la combinaison invalide et **refuse la
 *   planification** (revalidation de `CreateProjectUseCase`, ADR 0153 §5) ;
 * - les dépendances automatiques (`autoDefinit`) positionnent leurs cibles
 *   `BOOLEAN` après dérivation, sans jamais écraser un champ figé à la main ;
 * - les variables automatiques (`projectName`, `description`, `author`,
 *   `year`, `slug`, `packagePath`, `contentLanguage`, `plateformeMajeure`)
 *   et les options communes (`includeReadme`, `includeGitignore`,
 *   `includeEditorconfig`, `includeGitattributes`, `license`) alimentent le contexte comme n'importe
 *   quel paramètre ;
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
constructor(private val assets: TemplateAssetsSource) {
    /**
     * Évalue le formulaire dynamique (section 12.2) : visibilité, état
     * actif/désactivé, valeurs effectives (dérivées ou saisies), validité,
     * libellés et choix résolus, messages globaux des règles de projet.
     *
     * @param charge modèle chargé.
     * @param nomProjet nom du projet saisi (source des dérivations).
     * @param auteur nom d'auteur des paramètres applicatifs.
     * @param valeursParametres valeurs saisies (chaînes ; booléens
     * `true`/`false`).
     * @param modifiesManuellement identifiants modifiés à la main — les
     * autres suivent leurs sources.
     * @param langue langue de résolution des libellés (repli anglais).
     * @param plateformeMajeure majeure de la plateforme de compilation du
     * catalogue d'outils (variable automatique `plateformeMajeure`, ADR
     * 0153 §3) — fournie par le planificateur, source de vérité unique.
     */
    @Suppress("LongParameterList") // Signature du wizard (section 12.2) : entrées indépendantes.
    public fun evaluerFormulaire(
        charge: LoadedTemplate,
        nomProjet: String,
        auteur: String,
        valeursParametres: Map<String, String>,
        modifiesManuellement: Set<String>,
        langue: String,
        plateformeMajeure: Int,
    ): TemplateFormEvaluation {
        val evaluation =
            evaluerParametres(
                charge,
                nomProjet,
                auteur,
                valeursParametres,
                modifiesManuellement,
                plateformeMajeure,
                langue,
            )
        val dictionnaire = charge.dictionnaireEffectif(langue)
        val parametres =
            charge.template.parameters.map { parametre ->
                val echec = evaluation.erreurs[parametre.id]
                TemplateParameterEvaluation(
                    parameterId = parametre.id,
                    visible = evaluation.visibilites.getValue(parametre.id),
                    effectiveValue = evaluation.valeursEffectives.getValue(parametre.id),
                    error = echec?.message,
                    label = dictionnaire[parametre.labelKey] ?: parametre.labelKey,
                    help = parametre.helpKey?.let { dictionnaire[it] } ?: "",
                    errorReason = echec?.raison,
                    type = parametre.type,
                    choices = parametre.choices,
                    derived = parametre.defaultFrom != null,
                    section = parametre.section,
                    etat = evaluation.etats.getValue(parametre.id),
                    choix = choixEvalues(parametre, dictionnaire),
                    rendu = parametre.rendu,
                    groupe = parametre.groupe,
                    groupeTitre =
                    parametre.groupe?.let { groupe ->
                        dictionnaire["groupe.$groupe.titre"] ?: groupe
                    } ?: "",
                    avance = parametre.avance,
                )
            }
        return TemplateFormEvaluation(
            parameters = parametres,
            computedValues = evaluation.variablesCalculees.mapValues { stringifier(it.value) },
            messages = evaluation.messages,
            isValid = evaluation.erreurs.isEmpty() &&
                evaluation.messages.none { it.severite == SeveriteRegle.ERREUR },
            etapesWizard =
            evaluation.etapesEffectives.map { etape ->
                TemplateEtapeEvaluee(
                    id = etape.id,
                    // Étape par défaut : titre applicatif (vide) — les
                    // étapes déclarées résolvent leur titreKey (repli clé).
                    titre = if (etape.defaut) "" else dictionnaire[charge.template.titreEtape(etape.id)] ?: etape.id,
                    icone =
                    charge.template.etapesWizard
                        .firstOrNull { it.id == etape.id }
                        ?.iconeKey
                        ?.let { dictionnaire[it] }
                        ?: "",
                    parametres = etape.parametres,
                    defaut = etape.defaut,
                )
            },
            presets =
            charge.template.presets.map { preset ->
                TemplatePresetResolu(
                    id = preset.id,
                    nom = dictionnaire[preset.nomKey] ?: preset.nomKey,
                    description = preset.descriptionKey?.let { dictionnaire[it] } ?: "",
                    valeurs = preset.valeurs,
                )
            },
            groupesFichiers =
            charge.template.fichiers
                .map { it.group }
                .filter { it in GROUPES_OPTIONNELS }
                .toSet(),
        )
    }

    /** Clé i18n du titre d'une étape déclarée : `etape.<id>.titre`. */
    private fun ProjectTemplate.titreEtape(id: String): String {
        val cle = etapesWizard.firstOrNull { it.id == id }?.titreKey
        return cle ?: "etape.$id.titre"
    }

    /** Choix d'un paramètre `CHOICE` avec leurs textes résolus (ADR 0154 §1). */
    private fun choixEvalues(
        parametre: jo.codeide.core.model.TemplateParameter,
        dictionnaire: Map<String, String>,
    ): List<ChoixEvalue> {
        if (parametre.type != jo.codeide.core.model.TemplateParameterType.CHOICE) return emptyList()
        return parametre.choices.map { valeur ->
            ChoixEvalue(
                valeur = valeur,
                libelle = dictionnaire[cleChoix(parametre.id, valeur, "libelle")] ?: valeur,
                sousTitre = dictionnaire[cleChoix(parametre.id, valeur, "sousTitre")] ?: "",
                description = dictionnaire[cleChoix(parametre.id, valeur, "description")] ?: "",
                icone = dictionnaire[cleChoix(parametre.id, valeur, "icone")] ?: "",
            )
        }
    }

    /** Clé i18n d'un texte de choix : `param.<id>.choix.<valeur>.<suffixe>`. */
    private fun cleChoix(id: String, valeur: String, suffixe: String): String = "param.$id.choix.$valeur.$suffixe"

    /**
     * Résume un modèle pour le catalogue (libellés résolus).
     *
     * Une clé absente des dictionnaires retombe sur la clé elle-même —
     * le fournisseur embarqué refuse de tels manifestes au chargement ;
     * ce repli ne protège que des fournisseurs externes distraits.
     *
     * Depuis l'étape 10, l'icône est **résolue** comme les autres
     * libellés : le manifeste porte une clé i18n (`template.icon`) et le
     * dictionnaire fournit le monogramme maison (ex. « kt », « jv ») —
     * l'interface n'a jamais à connaître les dictionnaires.
     */
    public fun resumer(charge: LoadedTemplate, langue: String): TemplateSummary {
        val dictionnaire = charge.dictionnaireEffectif(langue)
        return TemplateSummary(
            id = charge.template.id,
            nom = dictionnaire[charge.template.nameKey] ?: charge.template.nameKey,
            description = dictionnaire[charge.template.descriptionKey] ?: charge.template.descriptionKey,
            category = charge.template.category,
            iconKey = dictionnaire[charge.template.iconKey] ?: charge.template.iconKey,
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
        val problemeRenommage = validerRenommages(requete.renommages)
        if (problemeRenommage != null) {
            return AppResult.Failure(AppError.Validation(problemeRenommage))
        }

        val evaluation =
            evaluerParametres(
                charge,
                requete.nom,
                requete.auteur,
                requete.valeursParametres,
                requete.modifiesManuellement,
                requete.plateformeMajeure,
                requete.options.contentLanguage,
            )
        if (evaluation.erreurs.isNotEmpty()) {
            val details = evaluation.erreurs.entries.joinToString("; ") { "${it.key} : ${it.value}" }
            return AppResult.Failure(AppError.Validation(details))
        }
        // Revalidation des règles de projet (ADR 0153 §5) : une combinaison
        // contenant une règle ERREUR échoue ici, même si l'UI a été
        // contournée — CreateProjectUseCase passe par ce plan partagé.
        val reglesBloquantes = evaluation.messages.filter { it.severite == SeveriteRegle.ERREUR }
        if (reglesBloquantes.isNotEmpty()) {
            return AppResult.Failure(
                AppError.Validation(
                    "règles de projet bloquantes : " +
                        reglesBloquantes.joinToString("; ") { "« ${it.cle} » ${it.texte}" },
                ),
            )
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
            val cheminOriginal =
                try {
                    TemplateRenderer.rendreFichier(fichier.chemin, contexte, dictionnaire, fichier.chemin)
                } catch (erreur: TemplateRenderException) {
                    return AppResult.Failure(
                        AppError.Template("chemin « ${fichier.chemin} » : ${erreur.message}"),
                    )
                }
            val cheminRendu = renommerChemin(cheminOriginal, requete.renommages)
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
            planifies +=
                PlannedFile(
                    chemin = cheminRendu,
                    group = fichier.group,
                    contenu = contenu,
                    cheminOriginal = cheminOriginal.takeIf { it != cheminRendu },
                )
        }

        // Licence : injectée par le moteur pour tout modèle (option commune).
        if (requete.options.license != jo.codeide.core.model.License.NONE) {
            val fichierLicence = requete.options.license.codeTemplate() + EXTENSION_LICENCE
            val texteLicence = lireEtRendreLicence(fichierLicence, contexte, dictionnaire)
            val texte =
                texteLicence.getOrNull()
                    ?: return AppResult.Failure(AppError.Template("licence $fichierLicence illisible"))
            val cheminLicence = renommerChemin(CHEMIN_LICENCE, requete.renommages)
            val problemeLicence = TemplatePathGuard.valider(cheminLicence)
            if (problemeLicence != null) {
                return AppResult.Failure(AppError.Template(problemeLicence))
            }
            planifies +=
                PlannedFile(
                    chemin = cheminLicence,
                    group = TemplateFileGroup.LICENSE,
                    contenu =
                    PlannedContent.Texte(
                        normaliserFinsDeLigne(cheminLicence, texte),
                    ),
                    cheminOriginal = CHEMIN_LICENCE.takeIf { it != cheminLicence },
                )
        }

        // Métadonnées du projet : toujours présentes (section 11). Les
        // paramètres masqués OU désactivés sont exclus (ADR 0153 §2).
        val parametresPersistes =
            template.parameters
                .filter {
                    it.persist &&
                        evaluation.visibilites.getValue(it.id) &&
                        evaluation.etats.getValue(it.id) == EtatParametre.Visible
                }
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
     * Contrôle la carte des renommages AVANT tout calcul (phase 4,
     * ADR 0077) : un nouveau nom est un **segment** (jamais vide, jamais
     * séparateur, jamais parent), et les métadonnées `.codeide/` sont
     * intouchables — le fichier de suivi du projet ne doit jamais
     * disparaître sous un autre nom.
     *
     * Les clés sans correspondance dans le plan final sont licites et
     * ignorées : l'arborescence a pu changer entre-temps (retour en
     * arrière, autre variante), exactement comme les valeurs de
     * paramètres obsolètes.
     */
    @Suppress("ReturnCount") // Une clause de garde par famille d'erreur (règle 16).
    private fun validerRenommages(renommages: Map<String, String>): String? {
        for ((ancien, nom) in renommages) {
            if (ancien.isBlank()) {
                return "renommage : clé vide"
            }
            if (ancien == CHEMIN_DOSSIER_METADATA || ancien.startsWith("$CHEMIN_DOSSIER_METADATA/")) {
                return "renommage interdit : « $ancien » fait partie des métadonnées du projet"
            }
            if (nom.isBlank()) {
                return "renommage de « $ancien » : le nouveau nom est vide"
            }
            if (SEPARATEUR_CHEMIN in nom || SEPARATEUR_WINDOWS in nom) {
                return "renommage de « $ancien » : le nouveau nom doit être un simple segment (sans séparateur)"
            }
            if (nom == "." || nom == "..") {
                return "renommage de « $ancien » : « $nom » n'est pas un nom valide"
            }
        }
        return null
    }

    /**
     * Applique les renommages au chemin rendu : substitution **simultanée**
     * de chaque segment dont le préfixe (chemin du nœud, du fichier à
     * l'ancêtre) est une clé — les préfixes sont lus sur les segments
     * **originaux**, les renommages d'un dossier et de son fichier se
     * composent donc, et aucune itération ne peut boucler.
     */
    private fun renommerChemin(chemin: String, renommages: Map<String, String>): String {
        if (renommages.isEmpty()) return chemin
        val originaux = chemin.split(SEPARATEUR_CHEMIN)
        val resultats = originaux.toMutableList()
        for (index in originaux.indices) {
            val prefixe = originaux.subList(0, index + 1).joinToString(SEPARATEUR_CHEMIN)
            renommages[prefixe]?.let { resultats[index] = it }
        }
        return resultats.joinToString(SEPARATEUR_CHEMIN)
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

    /**
     * Évalue visibilité, états, valeurs effectives, validité, variables
     * calculées, dépendances automatiques et règles de projet.
     *
     * Passes (ordre contractuel, `docs/TEMPLATES.md`) :
     * 1. valeurs effectives sans dérivation (défauts et saisies) ;
     * 2. valeurs dérivées (`defaultFrom`) ;
     * 3. variables calculées du manifeste ;
     * 4. dépendances automatiques (`autoDefinit`, ADR 0153 §4) — leur
     *    condition peut citer les variables calculées, et les visibilités
     *    doivent voir les cibles repositionnées ; jamais d'écrasement d'un
     *    champ figé à la main ;
     * 5. visibilité (`visibleWhen`) ;
     * 6. état actif/désactivé (`enabledWhen`, ADR 0153 §2) ;
     * 7. validation des paramètres visibles **et** actifs ;
     * 8. les masqués **ou désactivés** retombent sur leur valeur par défaut
     *    (section 11 et ADR 0153 §2 : ignorés — non validés, non persistés) ;
     * 9. règles de projet (`constraints`, ADR 0153 §3) sur la combinaison
     *    **finale** — les messages portent leur texte résolu dans [langue].
     */
    @Suppress(
        "LongMethod", // Passes documentées une à une.
        "CyclomaticComplexMethod",
        "SwallowedException", // Transformées en échecs explicites du manifeste.
        "LongParameterList", // Signature du wizard (ADR 0153) : entrées indépendantes.
    )
    private fun evaluerParametres(
        charge: LoadedTemplate,
        nomProjet: String,
        auteur: String,
        valeurs: Map<String, String>,
        modifiesManuellement: Set<String>,
        plateformeMajeure: Int,
        langue: String,
    ): EvaluationParametres {
        val template = charge.template
        val dictionnaire = charge.dictionnaireEffectif(langue)
        val messages = LinkedHashMap<MessageRegle, Unit>()
        fun annoncer(regle: jo.codeide.core.model.TemplateRegleProjet) {
            messages[
                MessageRegle(
                    regle.severite,
                    dictionnaire[regle.messageKey] ?: regle.messageKey,
                    regle.messageKey,
                ),
            ] =
                Unit
        }
        fun annoncerInfo(cle: String?) {
            if (cle != null) {
                messages[MessageRegle(SeveriteRegle.INFO, dictionnaire[cle] ?: cle, cle)] = Unit
            }
        }

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
                        // Copie figée : les fonctions dérivées lisent les
                        // valeurs effectives déjà calculées (leur source
                        // doit être déclarée avant elles, docs/TEMPLATES.md).
                        valeursParametres = effectives.toMap(),
                    )
                effectives[parametre.id] = TemplateDefaultFunctions.appliquer(derivee, sources)
            }
        }

        // Passe 3 : variables calculées du manifeste (ordre déclaré) —
        // chacune voit les paramètres et les calculées précédentes.
        val calculees = LinkedHashMap<String, ExpressionValue>()
        val contexteCalcul = contexteBrut(template, nomProjet, effectives, calculees, plateformeMajeure)
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

        // Passe 4 : dépendances automatiques (autoDefinit, ADR 0153 §4) —
        // après les calculées (leur condition peut les citer), avant la
        // visibilité ; une seule passe, aucune propagation indirecte.
        template.autoDefinit.forEach { regle ->
            if (!evaluerCondition(regle.quand, contexteCalcul, "autoDefinit")) return@forEach
            regle.definit.forEach { (cible, valeur) ->
                if (cible !in modifiesManuellement) {
                    effectives[cible] = valeur.toString()
                    contexteCalcul[cible] = ExpressionValue.Booleen(valeur)
                }
                // Le champ figé à la main n'est jamais écrasé (le champ
                // modifiesManuellement prime) ; le message INFO l'annonce
                // dans les deux cas quand la clé est fournie.
                annoncerInfo(regle.messageKey)
            }
        }

        // Passe 5 : visibilité (paramètres, automatiques et calculées).
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

        // Passe 6 : état actif/désactivé (enabledWhen, ADR 0153 §2) —
        // un paramètre masqué garde VISIBLE (état sans objet : non rendu).
        val etats =
            template.parameters.associate { parametre ->
                val condition = parametre.enabledWhen
                val etat =
                    when {
                        condition == null || !visibilites.getValue(parametre.id) -> EtatParametre.Visible

                        else -> {
                            val actif =
                                evaluerCondition(condition, contexteCalcul, "enabledWhen de « ${parametre.id} »")
                            if (actif) {
                                EtatParametre.Visible
                            } else {
                                val raison = parametre.disabledReasonKey?.let { dictionnaire[it] ?: it } ?: ""
                                EtatParametre.Desactive(raison)
                            }
                        }
                    }
                parametre.id to etat
            }

        // Passe 7 : validation des paramètres visibles et actifs uniquement.
        val erreurs = LinkedHashMap<String, TemplateValidators.EchecValidation>()
        template.parameters.forEach { parametre ->
            if (!visibilites.getValue(parametre.id)) return@forEach
            if (etats.getValue(parametre.id) is EtatParametre.Desactive) return@forEach
            val valeur = effectives.getValue(parametre.id)
            val erreurType = validerType(parametre, valeur)
            if (erreurType != null) {
                erreurs[parametre.id] = erreurType
                return@forEach
            }
            val validateur = parametre.validator ?: return@forEach
            val erreur = TemplateValidators.evaluer(validateur, valeur)
            if (erreur != null) erreurs[parametre.id] = erreur
        }

        // Passe 8 : les paramètres masqués ou désactivés retombent sur leur
        // valeur par défaut (section 11 et ADR 0153 §2 : ignorés — non
        // validés, non persistés ; la saisie du wizard reste de son côté).
        template.parameters.forEach { parametre ->
            if (!visibilites.getValue(parametre.id) || etats.getValue(parametre.id) is EtatParametre.Desactive) {
                effectives[parametre.id] = parametre.defaultValue ?: defautDeType(parametre)
            }
        }

        // Passe 9 : règles de projet (ADR 0153 §3) sur la combinaison FINALE
        // (valeurs de secours comprises) — déclarées dans l'ordre.
        val contexteFinal = contexteBrut(template, nomProjet, effectives, calculees, plateformeMajeure)
        template.constraints.forEach { regle ->
            if (!evaluerCondition(regle.quand, contexteFinal, "contrainte")) return@forEach
            annoncer(regle)
        }

        // Passe 10 : étapes de wizard EFFECTIVES (ADR 0155 §1/§3) — une
        // étape déclarée dont `when` est faux n'existe pas (jamais d'écran
        // vide) ; les paramètres CONFIGURATION non rangés rejoignent une
        // étape par défaut unique « Configuration » (jamais déclarée).
        val etapesEffectives = etapesWizardEffectives(template, contexteFinal)

        return EvaluationParametres(
            visibilites,
            effectives,
            erreurs,
            calculees,
            etats,
            messages.keys.toList(),
            etapesEffectives,
        )
    }

    /**
     * Calcule la liste des étapes de modèle effectives (ADR 0155 §1/§3) :
     * étapes déclarées dont `when` est vrai (dans l'ordre du manifeste),
     * puis l'étape par défaut « Configuration » si des paramètres
     * `CONFIGURATION` ne sont rangés dans aucune étape.
     */
    private fun etapesWizardEffectives(
        template: ProjectTemplate,
        contexteFinal: ContexteExpressions,
    ): List<EtapeEffective> {
        val declarees =
            template.etapesWizard.mapNotNull { etape ->
                val condition = etape.quand
                val presente =
                    condition == null ||
                        evaluerCondition(condition, contexteFinal, "when de l'étape « ${etape.id} »")
                if (presente) EtapeEffective(etape.id, etape.parametres, defaut = false) else null
            }
        // Rangement incomplet : les paramètres CONFIGURATION non cités par
        // AUCUNE étape déclarée (manifeste sans bloc wizard ou rangement
        // partiel) rejoignent l'étape par défaut — un paramètre cité dans
        // une étape aujourd'hui filtrée par son `when` n'est PAS déplacé
        // (l'étape réapparaîtra si la condition redevient vraie).
        val ranges = template.etapesWizard.flatMap { it.parametres }.toSet()
        val orphelins =
            template.parameters
                .filter { it.section == jo.codeide.core.model.TemplateSection.CONFIGURATION && it.id !in ranges }
                .map { it.id }
        return if (orphelins.isEmpty()) {
            declarees
        } else {
            declarees +
                EtapeEffective(ID_ETAPE_DEFAUT, orphelins, defaut = true)
        }
    }

    /** Évalue une condition déclarative en exigeant un booléen. */
    @Suppress("SwallowedException") // Transformée en échec explicite du manifeste (jamais avalée).
    private fun evaluerCondition(expression: String, contexte: ContexteExpressions, origine: String): Boolean {
        val valeur =
            try {
                ExpressionEvaluator.evaluer(ExpressionParser.analyser(expression), contexte)
            } catch (erreur: ExpressionException) {
                throw TemplateManifestException("$origine : ${erreur.message}")
            }
        if (valeur !is ExpressionValue.Booleen) {
            throw TemplateManifestException("$origine doit être booléen (reçu $valeur)")
        }
        return valeur.valeur
    }

    /** Valeur par défaut d'un type quand le manifeste n'en fournit pas. */
    private fun defautDeType(parametre: jo.codeide.core.model.TemplateParameter): String = when (parametre.type) {
        jo.codeide.core.model.TemplateParameterType.BOOLEAN -> "false"
        jo.codeide.core.model.TemplateParameterType.CHOICE -> parametre.choices.firstOrNull() ?: ""
        jo.codeide.core.model.TemplateParameterType.TEXT -> ""
    }

    /** Contrôle de type d'une valeur saisie (avant validateur nommé). */
    private fun validerType(
        parametre: jo.codeide.core.model.TemplateParameter,
        valeur: String,
    ): TemplateValidators.EchecValidation? = when (parametre.type) {
        jo.codeide.core.model.TemplateParameterType.BOOLEAN -> {
            if (valeur.lowercase() in setOf("true", "false")) {
                null
            } else {
                TemplateValidators.EchecValidation(
                    RaisonValidation.ValeurInterdite(valeur),
                    "valeur booléenne attendue (« true » ou « false »), reçu « $valeur »",
                )
            }
        }

        jo.codeide.core.model.TemplateParameterType.CHOICE -> {
            if (valeur in parametre.choices) {
                null
            } else {
                TemplateValidators.EchecValidation(
                    RaisonValidation.ValeurInterdite(valeur),
                    "valeur hors des choix possibles : « $valeur »",
                )
            }
        }

        jo.codeide.core.model.TemplateParameterType.TEXT -> {
            null
        }
    }

    /**
     * Contexte des calculs, des visibilités, des états et des règles
     * (sans les options communes) — la variable automatique
     * `plateformeMajeure` y est injectée comme un nombre (ADR 0153 §3).
     */
    private fun contexteBrut(
        template: ProjectTemplate,
        nomProjet: String,
        effectives: Map<String, String>,
        calculees: Map<String, ExpressionValue>,
        plateformeMajeure: Int,
    ): MutableMap<String, ExpressionValue> {
        val contexte = linkedMapOf<String, ExpressionValue>()
        val types = template.parameters.associate { it.id to it.type }
        effectives.forEach { (id, valeur) ->
            contexte[id] = valeurTypee(types[id], valeur)
        }
        calculees.forEach { (nom, valeur) -> contexte[nom] = valeur }
        contexte[NOM_VARIABLE_NOM] = ExpressionValue.Chaine(nomProjet)
        contexte[NOM_VARIABLE_SLUG] = ExpressionValue.Chaine(TemplateFilters.slug(nomProjet))
        contexte[NOM_VARIABLE_PLATEFORME] = ExpressionValue.Nombre(plateformeMajeure)
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
        contexte[NOM_OPTION_GITATTRIBUTES] = ExpressionValue.Booleen(requete.options.includeGitattributes)
        contexte[NOM_OPTION_LICENCE] = ExpressionValue.Chaine(requete.options.license.codeTemplate())
        return contexte
    }

    /**
     * Typage d'une valeur dans les expressions (ADR 0018 amendé) : un
     * `BOOLEAN` est booléen ; une valeur **entier canonique** (sans zéro de
     * tête — « 026 » reste une chaîne, la substitution `{{param}}` ne
     * muterait pas la saisie) est un nombre, pour les comparaisons
     * ordonnées des règles de projet ; tout le reste reste une chaîne.
     */
    private fun valeurTypee(type: jo.codeide.core.model.TemplateParameterType?, valeur: String): ExpressionValue =
        when {
            type == jo.codeide.core.model.TemplateParameterType.BOOLEAN ->
                ExpressionValue.Booleen(valeur.lowercase() == "true")

            ENTIER_CANONIQUE.matches(valeur) && valeur.toIntOrNull() != null ->
                ExpressionValue.Nombre(valeur.toInt())

            else -> ExpressionValue.Chaine(valeur)
        }

    /** Évalue une expression en exigeant un booléen. */
    private fun evaluerBooleen(expression: String, contexte: ContexteExpressions): Boolean {
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
    private fun normaliserFinsDeLigne(chemin: String, texte: String): String {
        val sansBom = texte.removePrefix("\uFEFF")
        val unifie = sansBom.replace("\r\n", "\n").replace('\r', '\n')
        return if (chemin.endsWith(".bat")) unifie.replace("\n", "\r\n") else unifie
    }

    /** Stringification : un nombre restitue exactement sa saisie canonique. */
    private fun stringifier(valeur: ExpressionValue): String = when (valeur) {
        is ExpressionValue.Chaine -> valeur.valeur
        is ExpressionValue.Booleen -> valeur.valeur.toString()
        is ExpressionValue.Nombre -> valeur.valeur.toString()
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

        /** Identifiant de l'étape par défaut « Configuration » (ADR 0155 §1). */
        internal const val ID_ETAPE_DEFAUT = "configuration"

        /** Groupes de fichiers optionnels pilotés par l'étape Fichiers. */
        private val GROUPES_OPTIONNELS =
            setOf(
                TemplateFileGroup.README,
                TemplateFileGroup.GITIGNORE,
                TemplateFileGroup.EDITORCONFIG,
            )

        private const val NOM_VARIABLE_NOM = "projectName"
        private const val NOM_VARIABLE_DESCRIPTION = "description"
        private const val NOM_VARIABLE_AUTEUR = "author"
        private const val NOM_VARIABLE_ANNEE = "year"
        private const val NOM_VARIABLE_SLUG = "slug"
        private const val NOM_VARIABLE_CHEMIN_PACKAGE = "packagePath"
        private const val NOM_VARIABLE_LANGUE_CONTENU = "contentLanguage"
        private const val NOM_VARIABLE_PLATEFORME = "plateformeMajeure"
        private const val NOM_OPTION_README = "includeReadme"
        private const val NOM_OPTION_GITIGNORE = "includeGitignore"
        private const val NOM_OPTION_EDITORCONFIG = "includeEditorconfig"
        private const val NOM_OPTION_GITATTRIBUTES = "includeGitattributes"
        private const val NOM_OPTION_LICENCE = "license"

        /**
         * Entier canonique : sans zéro de tête, signe moins isolé — la
         * stringification d'un [ExpressionValue.Nombre] restitue exactement
         * la saisie (la substitution `{{param}}` ne muterait jamais une
         * valeur).
         */
        private val ENTIER_CANONIQUE = Regex("^(0|[1-9][0-9]{0,9}|-([1-9][0-9]{0,9}))$")

        /** Nom conventionnel du paramètre de package (variable `packagePath`). */
        private const val NOM_PARAMETRE_PACKAGE = "packageName"

        /** Dossier des métadonnées du projet — intouchable au renommage. */
        private const val CHEMIN_DOSSIER_METADATA = ".codeide"

        /** Séparateur de chemin des plans (relatifs, style Unix). */
        private const val SEPARATEUR_CHEMIN = "/"

        /** Séparateur Windows — refusé dans un nouveau nom de segment. */
        private const val SEPARATEUR_WINDOWS = "\\"

        private val JsonMetadata =
            Json {
                prettyPrint = true
            }
    }
}
