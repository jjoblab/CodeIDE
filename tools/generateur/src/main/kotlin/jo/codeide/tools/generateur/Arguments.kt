package jo.codeide.tools.generateur

import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.templates.EmbeddedTemplatesProvider
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.LoadedTemplate
import jo.codeide.core.domain.templates.ProjectTemplateProvider
import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.domain.templates.TemplateProjectPlanner
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Analyse des arguments nommés de l'outil (lot L2, ADR 0157) : une
 * **commande** en tête (`lister`, `generer`, `matrice`, `valider-manifestes`,
 * `determinisme`) suivie d'options `--cle valeur` et de drapeaux `--cle`.
 *
 * Contrairement au parseur historique (strictement des paires), une même
 * clé peut être répétée (`--param k=v --param k2=v2`) : les valeurs
 * multiples s'accumulent dans l'ordre d'apparition.
 *
 * @property commande nom de la commande (premier argument sans `--`).
 */
internal class Arguments(args: Array<String>) {
    public val commande: String? = args.firstOrNull()?.takeIf { !it.startsWith("--") }

    private val valeurs = LinkedHashMap<String, MutableList<String>>()
    private val drapeaux = mutableSetOf<String>()

    public var erreur: String? = null
        private set

    init {
        var index = if (commande == null) 0 else 1
        var invalide = false
        while (index < args.size && !invalide) {
            val cle = args[index]
            if (!cle.startsWith("--") || cle.length < LONGUEUR_CLE_MINIMALE) {
                erreur = "argument inattendu : « $cle » (attendu --cle valeur)"
                invalide = true
            } else {
                val nom = cle.removePrefix("--")
                val suivante = args.getOrNull(index + 1)
                if (suivante != null && !suivante.startsWith("--")) {
                    valeurs.getOrPut(nom) { mutableListOf() } += suivante
                    index += 2
                } else {
                    drapeaux += nom
                    index += 1
                }
            }
        }
    }

    /** Première valeur de [cle], ou `null`. */
    public fun valeur(cle: String): String? = valeurs[cle]?.firstOrNull()

    /** Toutes les valeurs de [cle], dans l'ordre (vide si absente). */
    public fun valeursDe(cle: String): List<String> = valeurs[cle].orEmpty()

    /** `true` si le drapeau [cle] est présent. */
    public fun drapeau(cle: String): Boolean = cle in drapeaux

    /**
     * Lit [cle] comme un entier strictement positif.
     *
     * @throws IllegalArgumentException si absent ou illisible.
     */
    public fun entierPositif(cle: String): Int = valeur(cle)?.let { brut ->
        brut.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("--$cle attend un entier positif, reçu « $brut »")
    } ?: throw IllegalArgumentException("--$cle manquant")

    /**
     * Lit [cle] comme un texte obligatoire.
     *
     * @throws IllegalArgumentException si absent.
     */
    public fun requisTexte(cle: String): String = valeur(cle) ?: throw IllegalArgumentException("--$cle manquant")

    /**
     * Lit [cle] comme un répertoire existant.
     *
     * @throws IllegalArgumentException si absent ou inexistant.
     */
    public fun requisRepertoire(cle: String): File {
        val brut = requisTexte(cle)
        return File(brut).takeIf { it.isDirectory }
            ?: throw IllegalArgumentException("--$cle : répertoire introuvable : $brut")
    }

    /**
     * Lit [cle] comme un répertoire de sortie (créé si absent).
     *
     * @throws IllegalArgumentException si absent ou non créable.
     */
    public fun requisRepertoireCree(cle: String): File {
        val brut = requisTexte(cle)
        val repertoire = File(brut)
        require(repertoire.mkdirs() || repertoire.isDirectory) {
            "--$cle : répertoire non créable : $brut"
        }
        return repertoire
    }

    /**
     * Lit [cle] comme un fichier existant.
     *
     * @throws IllegalArgumentException si absent ou inexistant.
     */
    public fun requisFichier(cle: String): File {
        val brut = requisTexte(cle)
        return File(brut).takeIf { it.isFile }
            ?: throw IllegalArgumentException("--$cle : fichier introuvable : $brut")
    }

    /**
     * Lit [cle] comme un booléen `true`/`false` (valeur par défaut sinon).
     *
     * @throws IllegalArgumentException si la valeur présente n'est pas
     * `true` ni `false`.
     */
    public fun drapeauBooleen(cle: String, defaut: Boolean): Boolean = when (val brut = valeur(cle)) {
        null -> defaut
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("--$cle attend true|false, reçu « $brut »")
    }

    private companion object {
        /** Longueur minimale d'un nom d'option (`--c`). */
        const val LONGUEUR_CLE_MINIMALE = 3
    }
}

/**
 * Fournisseur memoïsé (lot L2, ADR 0157) : la lecture des manifestes et des
 * dictionnaires n'est faite **qu'une fois** par répertoire d'assets, puis
 * servie depuis le cache — la matrice évalue des milliers de candidats et
 * l'usage [EvaluateTemplateFormUseCase] relance
 * [ProjectTemplateProvider.provide] à chaque évaluation.
 *
 * L'outil est séquentiel (un seul fil, `runBlocking` en tête de `main`) :
 * un champ simple suffit, aucun verrou.
 */
internal class FournisseurMemoise(private val delegate: ProjectTemplateProvider) : ProjectTemplateProvider {
    private var cache: AppResult<List<LoadedTemplate>>? = null

    public override suspend fun provide(): AppResult<List<LoadedTemplate>> {
        cache?.let { return it }
        val frais = delegate.provide()
        cache = frais
        return frais
    }
}

/** Réglages applicatifs figés de l'outil : seul l'auteur alimente le moteur. */
internal class ReglagesOutil(auteur: String) : SettingsRepository {
    private val reglages = AppSettings(authorName = auteur)

    public override fun observeSettings(): Flow<AppSettings> = flowOf(reglages)

    public override suspend fun getSettings(): AppResult<AppSettings> = AppResult.Success(reglages)

    public override suspend fun updateSettings(update: (AppSettings) -> AppSettings): AppResult<Unit> =
        AppResult.Failure(AppError.Validation("l'outil ne modifie pas les paramètres"))

    public override suspend fun setWorkspace(location: StorageLocation?): AppResult<Unit> =
        AppResult.Failure(AppError.Validation("l'outil ne modifie pas les paramètres"))
}

/**
 * Montage partagé de toutes les commandes : port d'assets sur fichiers,
 * fournisseur memoïsé, planificateur complet (moteur + réglages + horloge +
 * version du générateur) — exactement le cheminement de l'application.
 *
 * @property assets port d'assets, partagé entre le moteur et le validateur.
 * @property fournisseur fournisseur memoïsé (catalogue chargé une fois).
 * @property planificateur planificateur complet sur ces assets.
 */
internal class MontageOutil(
    repertoireAssets: File,
    auteur: String,
    instantFixeMillis: Long,
    versionGenerateur: String,
) {
    public val assets: TemplateAssetsSource = FichierTemplateAssetsSource(repertoireAssets)

    public val fournisseur: ProjectTemplateProvider = FournisseurMemoise(EmbeddedTemplatesProvider(assets))

    public val planificateur: TemplateProjectPlanner = TemplateProjectPlanner(
        TemplateEngine(assets),
        setOf(fournisseur),
        ReglagesOutil(auteur),
        TimeProvider { instantFixeMillis },
        object : GeneratorVersion {
            public override val value: String = "CodeIDE $versionGenerateur"
        },
    )
}
