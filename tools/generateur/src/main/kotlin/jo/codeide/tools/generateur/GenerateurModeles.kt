package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.License
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Outil en ligne de commande des modèles (ADR 0019 ; multi-commandes au lot
 * L2, ADR 0157) : il produit sur disque des projets depuis les **vrais**
 * assets embarqués du dépôt (`app/src/main/assets`) en réutilisant tel quel
 * le moteur de templates de `core:domain` — ce que l'outil écrit est
 * exactement ce que l'application écrirait, à l'octet près.
 *
 * Cinq commandes (protocole de sortie `PROTOCOLE=2`, première ligne de
 * toute exécution) :
 *
 * - `lister` — l'état réel des manifestes (JSON ou texte) ;
 * - `generer` — génération sur disque d'un lot (`--combos`, protocole
 *   historique `OK`/`ECHEC`/`GENERE` conservé) ou d'un projet unitaire
 *   (`--modele` + `--param k=v`) ;
 * - `matrice` — dérivation des combinaisons depuis les manifestes
 *   (stratégies exhaustif/paires/bornes, exclusions signalées en JSONL) ;
 * - `valider-manifestes` — lint i18n/expressions/sources/chemins/choix ;
 * - `determinisme` — deux générations comparées octet pour octet.
 *
 * Piloté par `scripts/verify-templates.sh` : la matrice vient de `matrice`,
 * la génération de `generer`, le contrôle du plan figé de `determinisme`.
 */
public class GenerateurModeles {
    /**
     * Point d'entrée de l'outil.
     *
     * @param args arguments de ligne de commande (voir [USAGE]).
     * @return 0 si la commande réussit, 1 en cas d'échec explicite, 2 si les
     * arguments sont invalides.
     */
    public fun executer(args: Array<String>): Int {
        val arguments = Arguments(args)
        val commande = arguments.commande
        if (commande == null || arguments.erreur != null) {
            arguments.erreur?.let(System.err::println)
            System.err.println(USAGE)
            return CODE_ERREUR_USAGE
        }
        return try {
            runBlocking { executer(commande, arguments) }
        } catch (erreur: IllegalArgumentException) {
            // Arguments bien formés mais invalides (seuil, stratégie…) : usage.
            System.err.println("argument invalide : ${erreur.message}")
            System.err.println(USAGE)
            CODE_ERREUR_USAGE
        } catch (erreur: IllegalStateException) {
            System.err.println(erreur.message ?: "échec inconnu")
            CODE_ERREUR
        }
    }

    /** Aiguillage des commandes. */
    private suspend fun executer(commande: String, arguments: Arguments): Int = when (commande) {
        "lister" -> lister(arguments)

        "generer" -> generer(arguments)

        "matrice" -> matrice(arguments)

        "valider-manifestes" -> validerManifestes(arguments)

        "determinisme" -> determinisme(arguments)

        else -> {
            System.err.println("commande inconnue : « $commande »")
            System.err.println(USAGE)
            CODE_ERREUR_USAGE
        }
    }

    // ------------------------------------------------------------- lister

    /** `lister [--modele <id>] [--json]` : l'état réel des manifestes. */
    private suspend fun lister(arguments: Arguments): Int {
        val montage = monterMontage(arguments, reglagesExigees = false)
        val listeur = ListeurModeles(montage.fournisseur)
        System.out.println(LIGNE_PROTOCOLE)
        val charges = listeur.lister(arguments.valeur("modele")?.let(::TemplateId))
        if (arguments.drapeau("json")) {
            val tableau = JsonArray(charges.map { listeur.enJson(it) })
            System.out.println(jsonPretty.encodeToString(JsonArray.serializer(), tableau))
        } else {
            charges.forEach { charge -> System.out.println(listeur.enTexte(charge)) }
        }
        System.out.println("LISTE ${charges.size}")
        return CODE_SUCCES
    }

    // ------------------------------------------------------------ generer

    /**
     * `generer` : génération sur disque — lot (`--combos`) ou projet
     * unitaire (`--modele`). Le protocole historique
     * (`OK <id>` / `ECHEC <id> : <détail>`, puis `GENERE <ok>/<total>` en
     * mode lot) est conservé.
     */
    private suspend fun generer(arguments: Arguments): Int {
        val combos = arguments.valeur("combos")
        val modele = arguments.valeur("modele")
        require(combos == null || modele == null) {
            "--combos et --modele sont exclusifs l'un de l'autre"
        }
        require(combos != null || modele != null) {
            "fournissez --combos (lot) ou --modele (projet unitaire)"
        }
        val montage = monterMontage(arguments, reglagesExigees = true)
        val repertoireSortie = arguments.requisRepertoireCree("sortie")
        val planifier = PlanProjectCreationUseCase(montage.planificateur)
        System.out.println(LIGNE_PROTOCOLE)

        return if (combos != null) {
            require(File(combos).isFile) { "--combos : fichier introuvable : $combos" }
            genererLot(planifier, File(combos), repertoireSortie)
        } else {
            genererUnitaire(planifier, requisNonNull(modele), arguments, repertoireSortie)
        }
    }

    // ------------------------------------------------------------ matrice

    /**
     * `matrice [--strategie …] [--modele <id>] [--sortie combos.json]` :
     * dérivation des combinaisons depuis les manifestes (ADR 0157 §1) ;
     * exclusions en JSONL sur la sortie d'erreur, combinaisons en JSON
     * (fichier ou sortie standard), gardes de couverture en fin.
     */
    private suspend fun matrice(arguments: Arguments): Int {
        val montage = monterMontage(arguments, reglagesExigees = false)
        val options =
            OptionsMatrice(
                strategie = strategie(arguments),
                seuilExhaustif = arguments.valeur("seuil")?.toIntOrNull() ?: 64,
                plafondCandidats = arguments.valeur("plafond")?.toLongOrNull() ?: 500_000L,
                nomProjet = arguments.valeur("nom") ?: OptionsMatrice.NOM_DEFAUT,
                langueContenu = arguments.valeur("langue") ?: "fr",
                licence = arguments.valeur("licence") ?: "none",
            )
        val listeur = ListeurModeles(montage.fournisseur)
        val ids = listeur.lister(arguments.valeur("modele")?.let(::TemplateId)).map { it.template.id }
        val evaluer = EvaluateTemplateFormUseCase(montage.planificateur)
        val resultat = GenerateurMatrice(evaluer).deriver(ids, options)

        for (exclusion in resultat.exclusions) {
            System.err.println(jsonLigne.encodeToString(ExclusionMatrice.serializer(), exclusion))
        }
        val fichierSortie = arguments.valeur("sortie")
        if (fichierSortie != null) {
            val fichier = File(fichierSortie)
            fichier.parentFile?.mkdirs()
            fichier.writeText(
                jsonPretty.encodeToString(ListSerializer(Combinaison.serializer()), resultat.combinaisons),
            )
        } else {
            System.out.println(
                jsonLigne.encodeToString(ListSerializer(Combinaison.serializer()), resultat.combinaisons),
            )
        }
        val candidats = resultat.bilans.sumOf { it.candidats }
        System.out.println(
            "MATRICE ${ids.size} $candidats ${resultat.combinaisons.size} ${resultat.exclusions.size}",
        )
        return quandMatriceSaine(resultat)
    }

    /** Gardes de saine couverture : aucune paire oubliée, aucun modèle vide. */
    private fun quandMatriceSaine(resultat: ResultatMatrice): Int {
        val incomplets = resultat.bilans.filter { !it.pairesCouvertesEntierement }
        val vides = resultat.bilans.filter { it.retenues == 0 }
        return when {
            incomplets.isNotEmpty() -> {
                System.err.println(
                    "couverture des paires incomplète : " +
                        incomplets.joinToString(", ") {
                            "${it.templateId} (${it.pairesCouvertes}/${it.pairesAtteignables})"
                        },
                )
                CODE_ERREUR
            }

            vides.isNotEmpty() -> {
                System.err.println(
                    "aucune combinaison valide pour : ${vides.joinToString(", ") { it.templateId }} " +
                        "(trou de couverture — corrigez les exclusions signalées)",
                )
                CODE_ERREUR
            }

            else -> CODE_SUCCES
        }
    }

    // ------------------------------------------------- valider-manifestes

    /** `valider-manifestes` : lint des manifestes (JSONL des écarts). */
    private suspend fun validerManifestes(arguments: Arguments): Int {
        val montage = monterMontage(arguments, reglagesExigees = false)
        val options =
            OptionsMatrice(
                strategie = StrategieMatrice.PAIRES,
                nomProjet = arguments.valeur("nom") ?: OptionsMatrice.NOM_DEFAUT,
                plafondCandidats = arguments.valeur("plafond")?.toLongOrNull() ?: 500_000L,
            )
        val evaluer = EvaluateTemplateFormUseCase(montage.planificateur)
        val validateur = ValidateurManifestes(montage.assets, montage.fournisseur, evaluer)
        System.out.println(LIGNE_PROTOCOLE)
        val cibles = arguments.valeur("modele")?.let { listOf(TemplateId(it)) } ?: emptyList()
        val ecarts = validateur.valider(cibles, options)
        for (ecart in ecarts) {
            System.out.println(jsonLigne.encodeToString(EcartManifeste.serializer(), ecart))
        }
        val modeles = montage.fournisseur.provide().getOrNull()?.size ?: 0
        System.out.println("VALIDATION $modeles ${ecarts.size}")
        return if (ecarts.isEmpty()) CODE_SUCCES else CODE_ERREUR
    }

    // ------------------------------------------------------- determinisme

    /**
     * `determinisme --combos <fichier> --sortie <dir>` : deux générations
     * successives comparées octet pour octet (JSONL des bilans).
     */
    @Suppress("ReturnCount") // Clauses de garde du lot (JSON cassé, lot vide) — règle 16.
    private suspend fun determinisme(arguments: Arguments): Int {
        val montage = monterMontage(arguments, reglagesExigees = true)
        val fichierCombinaisons = arguments.requisFichier("combos")
        val repertoireSortie = arguments.requisRepertoireCree("sortie")
        System.out.println(LIGNE_PROTOCOLE)
        val combinaisons =
            try {
                jsonLigne.decodeFromString(ListSerializer(Combinaison.serializer()), fichierCombinaisons.readText())
            } catch (erreur: IllegalArgumentException) {
                // JSON cassé -> échec explicite de la commande (jamais avalé).
                System.err.println("combinaisons illisibles : ${erreur.message}")
                return CODE_ERREUR
            }
        if (combinaisons.isEmpty()) {
            System.err.println("aucune combinaison fournie")
            return CODE_ERREUR
        }
        val planifier = PlanProjectCreationUseCase(montage.planificateur)
        val bilans =
            VerificateurDeterminisme(planifier).verifier(
                combinaisons,
                File(repertoireSortie, "generation-A"),
                File(repertoireSortie, "generation-B"),
            )
        for (bilan in bilans) {
            System.out.println(jsonLigne.encodeToString(BilanDeterminisme.serializer(), bilan))
        }
        val ok = bilans.count { it.identique }
        System.out.println("DETERMINISME $ok/${bilans.size}")
        return if (ok == bilans.size) CODE_SUCCES else CODE_ERREUR
    }

    // ------------------------------------------------------------- commun

    /**
     * Montage commun : assets obligatoires (répertoire existant) ;
     * [reglagesExigees] force `--annee`, `--auteur` et `--generateur`
     * (commandes qui génèrent : `generer`, `determinisme`).
     */
    private fun monterMontage(arguments: Arguments, reglagesExigees: Boolean): MontageOutil {
        val repertoire = arguments.requisRepertoire("assets")
        val annee =
            if (reglagesExigees) {
                arguments.entierPositif("annee")
            } else {
                arguments.valeur("annee")?.toIntOrNull() ?: ANNEE_DEFAUT
            }
        val auteur =
            if (reglagesExigees) arguments.requisTexte("auteur") else arguments.valeur("auteur") ?: AUTEUR_DEFAUT
        val versionGenerateur =
            if (reglagesExigees) {
                arguments.requisTexte("generateur")
            } else {
                arguments.valeur("generateur") ?: VERSION_DEFAUT
            }
        return MontageOutil(
            repertoireAssets = repertoire,
            auteur = auteur,
            instantFixeMillis =
            LocalDate
                .of(annee, MOIS_FIXE, JOUR_FIXE)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
            versionGenerateur = versionGenerateur,
        )
    }

    /** Stratégie de matrice demandée (défaut : paires). */
    private fun strategie(arguments: Arguments): StrategieMatrice =
        when (val brute = arguments.valeur("strategie") ?: "paires") {
            "exhaustif" -> StrategieMatrice.EXHAUSTIF
            "paires" -> StrategieMatrice.PAIRES
            "bornes" -> StrategieMatrice.BORNES
            else -> throw IllegalArgumentException("--strategie attend exhaustif|paires|bornes, reçu « $brute »")
        }

    internal companion object {
        const val CODE_SUCCES = 0
        const val CODE_ERREUR = 1
        const val CODE_ERREUR_USAGE = 2
        const val MOIS_FIXE = 6
        const val JOUR_FIXE = 15

        /** Réglages par défaut du montage quand la commande n'en exige pas. */
        const val AUTEUR_DEFAUT = "CodeIDE"
        const val ANNEE_DEFAUT = 2026
        const val VERSION_DEFAUT = "outil"

        /** Première ligne de toute exécution (version du protocole, ADR 0157). */
        const val LIGNE_PROTOCOLE = "PROTOCOLE=2"

        /** JSON compact pour les rapports JSONL et les sorties machines. */
        val jsonLigne = Json { ignoreUnknownKeys = true }

        /** JSON imprimé pour les sorties lisibles (fichiers de combinaisons). */
        val jsonPretty = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        /** Emplacement factice : le plan ne touche pas au disque. */
        val EMPLACEMENT =
            StorageLocation(
                grantUri = "file://generation",
                documentUri = "file://generation",
                displayPath = "generation",
            )

        /** Aide de l'outil. */
        val USAGE =
            """
            Usage : generateur <commande> [options]

            Commandes (protocole PROTOCOLE=2, ADR 0157) :
              lister --assets <dir> [--modele <id>] [--json]
              generer --assets <dir> --sortie <dir> --combos <fichier.json>
                      --annee <AAAA> --auteur <nom> --generateur <version>
              generer --assets <dir> --modele <id> --sortie <dir>
                      --annee <AAAA> --auteur <nom> --generateur <version>
                      [--nom <nom>] [--description <texte>] [--param k=v]…
                      [--langue fr|en] [--licence none|mit|apache-2.0|gpl-3.0|bsd-3-clause]
                      [--readme true|false] [--gitignore true|false] [--editorconfig true|false]
                      [--id <id-sortie>]
              matrice --assets <dir> [--strategie exhaustif|paires|bornes] [--modele <id>]
                      [--seuil <n>] [--plafond <n>] [--nom <nom>] [--langue fr|en]
                      [--licence <code>] [--sortie combos.json]
              valider-manifestes --assets <dir> [--modele <id>] [--plafond <n>]
              determinisme --assets <dir> --combos <fichier.json> --sortie <dir>
                      --annee <AAAA> --auteur <nom> --generateur <version>
            """.trimIndent()

        /** Traduit un code de licence en modèle. */
        fun licence(code: String): License = when (code) {
            "none" -> License.NONE
            "mit" -> License.MIT
            "apache-2.0" -> License.APACHE_2_0
            "gpl-3.0" -> License.GPL_3_0
            "bsd-3-clause" -> License.BSD_3_CLAUSE
            else -> throw IllegalArgumentException("licence inconnue : $code")
        }
    }
}

/** Rejette explicitement une option obligatoire restée `null`. */
private fun requisNonNull(valeur: String?): String =
    valeur ?: throw IllegalArgumentException("option obligatoire manquante")

/** Traduit une combinaison en demande de création complète. */
internal fun requeteDe(combinaison: Combinaison): CreateProjectRequest = CreateProjectRequest(
    templateId = TemplateId(combinaison.templateId),
    name = combinaison.nom,
    description = combinaison.description,
    parentLocation = GenerateurModeles.EMPLACEMENT,
    parameterValues = combinaison.parametres,
    manuallySetParameters = combinaison.modifiesManuellement,
    cheminsRenommes = combinaison.renommages,
    options =
    TemplateOptions(
        includeReadme = combinaison.options.includeReadme,
        includeGitignore = combinaison.options.includeGitignore,
        includeEditorconfig = combinaison.options.includeEditorconfig,
        license = GenerateurModeles.licence(combinaison.options.license),
        contentLanguage = combinaison.options.contentLanguage,
    ),
)

/** Déverse le plan figé dans [racine] : texte en UTF-8, binaire octet à octet. */
@Suppress("ReturnCount") // Clauses de garde du déversement (règle 16).
internal fun deverserPlan(
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

/** Point d'entrée : délègue à [GenerateurModeles] et propage le code de sortie. */
public fun main(args: Array<String>) {
    val code = GenerateurModeles().executer(args)
    if (code != 0) {
        kotlin.system.exitProcess(code)
    }
}
