package jo.codeide.tools.generateur

import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.templates.EmbeddedTemplatesProvider
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.domain.templates.TemplateProjectPlanner
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.License
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplatePlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Harnais de génération sur disque (étape 9, ADR 0019).
 *
 * Produit de vrais projets depuis les **vrais** assets embarqués du dépôt
 * (`app/src/main/assets`) en réutilisant le moteur de templates de
 * `core:domain` : ce que cet outil écrit est exactement ce que l'application
 * écrirait, à l'octet près — le plan figé de [PlanProjectCreationUseCase]
 * (ADR 0017) simplement déversé sur disque au lieu du SAF.
 *
 * Piloté par `scripts/verify-templates.sh`, qui fournit les combinaisons à
 * couvrir puis compile, teste et exécute chaque projet généré.
 *
 * Protocole de sortie (une ligne par combinaison, lisible par le script) :
 * `OK <id>` ou `ECHEC <id> : <détail>`, puis `GENERE <ok>/<total>`.
 */
public class GenerateurModeles {
    /**
     * Point d'entrée de l'outil.
     *
     * @param args arguments de ligne de commande (voir [USAGE]).
     * @return 0 si toutes les combinaisons sont générées, 1 sinon, 2 si les
     * arguments sont invalides.
     */
    public fun generer(args: Array<String>): Int {
        val options =
            analyserArguments(args)
                ?: run {
                    System.err.println(USAGE)
                    return CODE_ERREUR_USAGE
                }
        return runBlocking { genererCombinaisons(options) }
    }

    /** Génère toutes les combinaisons et rapporte chaque résultat. */
    @Suppress("ReturnCount") // Deux issues : arguments ou combinaisons illisibles (règle 16).
    private suspend fun genererCombinaisons(options: OptionsOutil): Int {
        val combinaisons =
            try {
                Json.decodeFromString<List<Combinaison>>(options.fichierCombinaisons.readText())
            } catch (erreur: IllegalArgumentException) {
                System.err.println("combinaisons illisibles : ${erreur.message}")
                return CODE_ERREUR
            }
        if (combinaisons.isEmpty()) {
            System.err.println("aucune combinaison fournie")
            return CODE_ERREUR
        }
        options.repertoireSortie.mkdirs()

        val assets = FichierTemplateAssetsSource(options.repertoireAssets)
        val planificateur =
            TemplateProjectPlanner(
                TemplateEngine(assets),
                setOf(EmbeddedTemplatesProvider(assets)),
                ReglagesFixes(options.auteur),
                TimeProvider { options.instantFixe },
                GeneratorVersionFixe(options.versionGenerateur),
            )
        val planifier = PlanProjectCreationUseCase(planificateur)

        var echecs = 0
        for (combinaison in combinaisons) {
            when (val resultat = planifier(requete(combinaison))) {
                is AppResult.Success -> {
                    ecrire(resultat.value, File(options.repertoireSortie, combinaison.id))
                    System.out.println("OK ${combinaison.id} (${resultat.value.fichiers.size} fichiers)")
                }

                is AppResult.Failure -> {
                    echecs++
                    System.out.println("ECHEC ${combinaison.id} : ${resultat.error}")
                }
            }
        }
        System.out.println("GENERE ${combinaisons.size - echecs}/${combinaisons.size}")
        return if (echecs == 0) CODE_SUCCES else CODE_ERREUR
    }

    /** Déverse le plan figé dans [racine] : texte en UTF-8, binaire octet à octet. */
    @Suppress("ReturnCount") // Clauses de garde du déversement (règle 16).
    internal fun ecrire(
        plan: TemplatePlan,
        racine: File,
    ) {
        val racineCanonique = racine.canonicalFile
        for (fichier in plan.fichiers) {
            val cible = racine.resolve(fichier.chemin)
            if (!cible.canonicalFile.startsWith(racineCanonique)) {
                error("chemin hors du projet : ${fichier.chemin}")
            }
            if (!cible.parentFile.mkdirs() && cible.parentFile?.isDirectory != true) {
                error("impossible de créer ${cible.parentFile}")
            }
            when (val contenu = fichier.contenu) {
                is PlannedContent.Texte -> cible.writeText(contenu.texte, Charsets.UTF_8)
                is PlannedContent.Binaire -> cible.writeBytes(contenu.octets)
            }
        }
    }

    /** Traduit une combinaison en demande de création complète. */
    private fun requete(combinaison: Combinaison): CreateProjectRequest =
        CreateProjectRequest(
            templateId = TemplateId(combinaison.templateId),
            name = combinaison.nom,
            description = combinaison.description,
            parentLocation = EMPLACEMENT,
            parameterValues = combinaison.parametres,
            manuallySetParameters = combinaison.modifiesManuellement,
            options =
                TemplateOptions(
                    includeReadme = combinaison.options.includeReadme,
                    includeGitignore = combinaison.options.includeGitignore,
                    includeEditorconfig = combinaison.options.includeEditorconfig,
                    license = licence(combinaison.options.license),
                    contentLanguage = combinaison.options.contentLanguage,
                ),
        )

    /** Analyse les arguments nommés de l'outil. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Options validées en clauses de garde (règle 16).
    private fun analyserArguments(args: Array<String>): OptionsOutil? {
        val lus = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val cle = args[index]
            val valeur = args.getOrNull(index + 1)
            if (!cle.startsWith("--") || valeur == null) return null
            if (lus.put(cle.removePrefix("--"), valeur) != null) return null
            index += 2
        }
        val assets = lus["assets"]?.let(::File)?.takeIf { it.isDirectory } ?: return null
        val sortie = lus["sortie"]?.let(::File) ?: return null
        val combos = lus["combos"]?.let(::File)?.takeIf { it.isFile } ?: return null
        val annee = lus["annee"]?.toIntOrNull() ?: return null
        val auteur = lus["auteur"] ?: return null
        val generateur = lus["generateur"] ?: return null
        return OptionsOutil(assets, sortie, combos, auteur, annee, generateur)
    }

    /** Paramètres applicatifs figés : seul l'auteur alimente le moteur. */
    private class ReglagesFixes(
        auteur: String,
    ) : SettingsRepository {
        private val reglages = AppSettings(authorName = auteur)

        public override fun observeSettings(): Flow<AppSettings> = flowOf(reglages)

        public override suspend fun getSettings(): AppResult<AppSettings> = AppResult.Success(reglages)

        public override suspend fun updateSettings(update: (AppSettings) -> AppSettings): AppResult<Unit> =
            AppResult.Failure(AppError.Validation("l'outil ne modifie pas les paramètres"))

        public override suspend fun setWorkspace(location: StorageLocation?): AppResult<Unit> =
            AppResult.Failure(AppError.Validation("l'outil ne modifie pas les paramètres"))
    }

    /** Version du générateur inscrite dans `.codeide/project.json`. */
    private class GeneratorVersionFixe(
        version: String,
    ) : GeneratorVersion {
        public override val value: String = "CodeIDE $version"
    }

    /** Options de l'outil une fois analysées et validées. */
    internal data class OptionsOutil(
        val repertoireAssets: File,
        val repertoireSortie: File,
        val fichierCombinaisons: File,
        val auteur: String,
        val annee: Int,
        val versionGenerateur: String,
    ) {
        /** Instant figé (15 juin de l'année demandée, midi UTC). */
        internal val instantFixe: Long =
            LocalDate
                .of(annee, MOIS_FIXE, JOUR_FIXE)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
    }

    private companion object {
        const val CODE_SUCCES = 0
        const val CODE_ERREUR = 1
        const val CODE_ERREUR_USAGE = 2
        const val MOIS_FIXE = 6
        const val JOUR_FIXE = 15

        /** Emplacement factice : le plan ne touche pas au disque. */
        val EMPLACEMENT =
            StorageLocation(
                grantUri = "file://generation",
                documentUri = "file://generation",
                displayPath = "generation",
            )

        /** Aide de l'outil. */
        const val USAGE =
            "Usage : generateur --assets <dir> --sortie <dir> --combos <fichier.json> " +
                "--annee <AAAA> --auteur <nom> --generateur <version>"

        /** Traduit un code de licence en modèle. */
        fun licence(code: String): License =
            when (code) {
                "none" -> License.NONE
                "mit" -> License.MIT
                "apache-2.0" -> License.APACHE_2_0
                "gpl-3.0" -> License.GPL_3_0
                "bsd-3-clause" -> License.BSD_3_CLAUSE
                else -> throw IllegalArgumentException("licence inconnue : $code")
            }
    }
}

/** Point d'entrée : délègue à [GenerateurModeles] et propage le code de sortie. */
public fun main(args: Array<String>) {
    val code = GenerateurModeles().generer(args)
    if (code != 0) {
        kotlin.system.exitProcess(code)
    }
}
