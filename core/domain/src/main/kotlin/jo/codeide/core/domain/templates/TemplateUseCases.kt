package jo.codeide.core.domain.templates

import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.model.TemplateSummary
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Cas d'usage « lister les modèles » (étape 8 — API domaine, section 11).
 *
 * Agrège les fournisseurs en multibinding, résume chaque modèle pour la
 * langue demandée et trie par identifiant — l'ordre du catalogue est
 * déterministe (l'ordre du `Set` Hilt ne l'est pas).
 */
public class ListTemplatesUseCase
    @Inject
    constructor(
        private val fournisseurs: Set<@JvmSuppressWildcards ProjectTemplateProvider>,
        private val moteur: TemplateEngine,
    ) {
        /**
         * Liste le catalogue.
         *
         * @param langue langue d'affichage des libellés (repli anglais).
         * @return les résumés triés par identifiant, ou l'échec typé (un
         * manifeste corrompu d'un fournisseur échoue explicitement).
         */
        @Suppress("ReturnCount") // Échec fournisseur puis doublon (règle 16).
        public suspend operator fun invoke(
            langue: String = TemplateOptions.LANGUE_DEFAUT,
        ): AppResult<List<TemplateSummary>> {
            val charges = mutableListOf<LoadedTemplate>()
            val ids = mutableSetOf<String>()
            for (fournisseur in fournisseurs) {
                val lot =
                    fournisseur.provide().getOrNull()
                        ?: return AppResult.Failure(AppError.Template("un fournisseur de modèles a échoué"))
                for (charge in lot) {
                    if (!ids.add(charge.template.id.value)) {
                        return AppResult.Failure(
                            AppError.Template(
                                "modèle en double entre fournisseurs : « ${charge.template.id.value} »",
                            ),
                        )
                    }
                    charges += charge
                }
            }
            return AppResult.Success(charges.map { moteur.resumer(it, langue) }.sortedBy { it.id.value })
        }
    }

/** Cas d'usage « valider le nom de projet » (section 12.3, champ commun du wizard). */
public class ValidateProjectNameUseCase
    @Inject
    constructor() {
        /**
         * Valide un nom de projet.
         *
         * @param nom valeur saisie.
         * @return le succès, ou `Validation` avec le détail (français).
         */
        public operator fun invoke(nom: String): AppResult<Unit> {
            val erreur =
                TemplateValidators.valider("project-name", nom)
                    ?: return AppResult.Success(Unit)
            return AppResult.Failure(AppError.Validation("nom de projet : $erreur"))
        }
    }

/** Cas d'usage « valider le nom de package » (section 12.3, champ commun du wizard). */
public class ValidatePackageNameUseCase
    @Inject
    constructor() {
        /**
         * Valide un nom de package.
         *
         * @param nomPackage valeur saisie.
         * @return le succès, ou `Validation` avec le détail (français).
         */
        public operator fun invoke(nomPackage: String): AppResult<Unit> {
            val erreur =
                TemplateValidators.valider("package-name", nomPackage)
                    ?: return AppResult.Success(Unit)
            return AppResult.Failure(AppError.Validation("nom de package : $erreur"))
        }
    }

/**
 * Cas d'usage « évaluer le formulaire d'un modèle » (étape 8 — section 12.2).
 *
 * Alimente le rendu dynamique du wizard à chaque frappe : visibilité
 * (`visibleWhen`), valeurs dérivées (`defaultFrom`) qui suivent leurs
 * sources tant que le champ n'est pas modifié à la main, validité de tous
 * les paramètres, libellés résolus.
 */
public class EvaluateTemplateFormUseCase
    @Inject
    constructor(
        private val planificateur: TemplateProjectPlanner,
    ) {
        /**
         * Évalue le formulaire courant.
         *
         * @param templateId modèle concerné.
         * @param nomProjet nom du projet saisi (source des dérivations).
         * @param valeursParametres valeurs saisies.
         * @param modifiesManuellement identifiants modifiés à la main.
         * @param langue langue des libellés.
         * @return l'évaluation complète, ou `NotFound`/`Template`.
         */
        public suspend operator fun invoke(
            templateId: TemplateId,
            nomProjet: String,
            valeursParametres: Map<String, String>,
            modifiesManuellement: Set<String> = emptySet(),
            langue: String = TemplateOptions.LANGUE_DEFAUT,
        ): AppResult<TemplateFormEvaluation> {
            val charge =
                planificateur.trouver(templateId).getOrNull()
                    ?: return AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.NotFound, "modèle ${templateId.value}"),
                    )
            val parametres = planificateur.parametresCourants()
            return AppResult.Success(
                planificateur.moteur.evaluerFormulaire(
                    charge,
                    nomProjet,
                    parametres.authorName,
                    valeursParametres,
                    modifiesManuellement,
                    langue,
                ),
            )
        }
    }

/**
 * Cas d'usage « planifier la création » (dry-run, section 12.4) : l'aperçu
 * de l'arborescence du récapitulatif — **sans rien écrire**.
 */
public class PlanProjectCreationUseCase
    @Inject
    constructor(
        private val planificateur: TemplateProjectPlanner,
    ) {
        /**
         * Produit le plan complet de la création demandée.
         *
         * @param requete demande complète (l'emplacement parent est ignoré
         * ici : le plan ne touche pas au disque).
         * @return le plan (chemins + contenus finaux), ou l'échec typé.
         */
        public suspend operator fun invoke(requete: CreateProjectRequest): AppResult<TemplatePlan> =
            planificateur.planifier(requete)
    }

/**
 * Planificateur partagé des créations (étape 8) : trouve le modèle parmi
 * les fournisseurs, assemble les entrées (auteur des paramètres, année de
 * l'horloge injectée, version du générateur) et délègue au moteur.
 *
 * `PlanProjectCreationUseCase` (aperçu) et `CreateProjectUseCase` (écriture)
 * partagent exactement ce cheminement — le récapitulatif reflète ce qui sera
 * écrit, à l'octet près. Le type est public car injecté dans les
 * constructeurs publics de ces cas d'usage ; il n'a pas vocation à être
 * consommé au-delà.
 */
public class TemplateProjectPlanner
    @Inject
    constructor(
        internal val moteur: TemplateEngine,
        private val fournisseurs: Set<@JvmSuppressWildcards ProjectTemplateProvider>,
        private val parametres: SettingsRepository,
        private val horloge: TimeProvider,
        private val generateur: GeneratorVersion,
    ) {
        /** Cherche un modèle chargé par identifiant. */
        public suspend fun trouver(id: TemplateId): AppResult<LoadedTemplate> {
            for (fournisseur in fournisseurs) {
                val lot = fournisseur.provide().getOrNull() ?: continue
                val trouve = lot.firstOrNull { it.template.id == id }
                if (trouve != null) return AppResult.Success(trouve)
            }
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "modèle ${id.value}"))
        }

        /** Paramètres applicatifs courants (auteur, licence par défaut…). */
        internal suspend fun parametresCourants() = parametres.observeSettings().first()

        /** Planifie la création correspondant à [requete]. */
        internal suspend fun planifier(requete: CreateProjectRequest): AppResult<TemplatePlan> {
            val charge =
                trouver(requete.templateId).getOrNull()
                    ?: return AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.NotFound, "modèle ${requete.templateId.value}"),
                    )
            val reglages = parametresCourants()
            val requeteGeneration =
                RequeteGeneration(
                    nom = requete.name,
                    description = requete.description,
                    valeursParametres = requete.parameterValues,
                    modifiesManuellement = requete.manuallySetParameters,
                    options = requete.options,
                    auteur = reglages.authorName,
                    annee = anneeCourante(),
                    versionGenerateur = generateur.value,
                )
            return moteur.planifier(charge, requeteGeneration)
        }

        /** Année civile de l'instant injecté (zone du système). */
        private fun anneeCourante(): String =
            Instant
                .ofEpochMilli(horloge.nowMillis())
                .atZone(ZoneId.systemDefault())
                .year
                .toString()
    }
