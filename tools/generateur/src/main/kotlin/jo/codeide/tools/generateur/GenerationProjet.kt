package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.model.AppResult
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

// Commande `generer` — génération sur disque (ADR 0019 ; lot L2, ADR 0157) :
// lot de combinaisons (`--combos`, protocole historique) ou projet unitaire
// (`--modele` + `--param k=v`). Extraites de [GenerateurModeles] pour garder
// le dispatcher léger — la logique est la même que l'application : plan figé
// de `PlanProjectCreationUseCase` déversé tel quel.

/** Génération d'un lot de combinaisons (protocole historique). */
@Suppress("ReturnCount") // Clauses de garde du lot (JSON cassé, lot vide) — règle 16.
internal suspend fun genererLot(
    planifier: PlanProjectCreationUseCase,
    fichierCombinaisons: File,
    repertoireSortie: File,
): Int {
    val combinaisons =
        try {
            GenerateurModeles.jsonLigne.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Combinaison.serializer()),
                fichierCombinaisons.readText(),
            )
        } catch (erreur: IllegalArgumentException) {
            // JSON cassé -> échec explicite de la commande (jamais avalé).
            System.err.println("combinaisons illisibles : ${erreur.message}")
            return GenerateurModeles.CODE_ERREUR
        }
    if (combinaisons.isEmpty()) {
        System.err.println("aucune combinaison fournie")
        return GenerateurModeles.CODE_ERREUR
    }
    repertoireSortie.mkdirs()
    var echecs = 0
    for (combinaison in combinaisons) {
        if (!genererUne(planifier, combinaison, repertoireSortie)) echecs++
    }
    System.out.println("GENERE ${combinaisons.size - echecs}/${combinaisons.size}")
    return if (echecs == 0) GenerateurModeles.CODE_SUCCES else GenerateurModeles.CODE_ERREUR
}

/** Génération d'un projet unitaire par arguments (`--modele`, `--param k=v`). */
internal suspend fun genererUnitaire(
    planifier: PlanProjectCreationUseCase,
    modele: String,
    arguments: Arguments,
    repertoireSortie: File,
): Int {
    val parametres = LinkedHashMap<String, String>()
    for (paire in arguments.valeursDe("param")) {
        val separee = paire.split('=', limit = 2)
        require(separee.size == 2) { "--param attend k=v, reçu « $paire »" }
        parametres[separee[0]] = separee[1]
    }
    val combinaison =
        Combinaison(
            id = arguments.valeur("id") ?: modele,
            templateId = modele,
            nom = arguments.valeur("nom") ?: "Projet",
            description = arguments.valeur("description") ?: "",
            parametres = parametres,
            modifiesManuellement = parametres.keys,
            options =
            OptionsCombinaison(
                includeReadme = arguments.drapeauBooleen("readme", defaut = true),
                includeGitignore = arguments.drapeauBooleen("gitignore", defaut = true),
                includeEditorconfig = arguments.drapeauBooleen("editorconfig", defaut = true),
                license = arguments.valeur("licence") ?: "none",
                contentLanguage = arguments.valeur("langue") ?: "en",
            ),
        )
    repertoireSortie.mkdirs()
    val reussi = genererUne(planifier, combinaison, repertoireSortie)
    return if (reussi) GenerateurModeles.CODE_SUCCES else GenerateurModeles.CODE_ERREUR
}

/** Génère une combinaison dans son répertoire et rapporte `OK`/`ECHEC`. */
internal suspend fun genererUne(
    planifier: PlanProjectCreationUseCase,
    combinaison: Combinaison,
    repertoireSortie: File,
): Boolean = when (val resultat = planifier(requeteDe(combinaison))) {
    is AppResult.Success -> {
        deverserPlan(resultat.value, File(repertoireSortie, combinaison.id))
        System.out.println("OK ${combinaison.id} (${resultat.value.fichiers.size} fichiers)")
        true
    }

    is AppResult.Failure -> {
        System.out.println("ECHEC ${combinaison.id} : ${resultat.error}")
        false
    }
}
