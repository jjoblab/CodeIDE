package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.LoadedTemplate
import jo.codeide.core.domain.templates.ProjectTemplateProvider
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Listing de l'état réel des manifestes `lister` (lot L2, ADR 0157 §1) :
 * modèles, paramètres (type, défaut, choix, visibilité, activité, section,
 * groupe, avance, persistance), variables calculées, contraintes,
 * dépendances automatiques, étapes de modèle et préréglages du bloc
 * `wizard` (schéma lu dès L3 — ADR 0155) et fichiers — plus aucun résumé
 * écrit à la main.
 *
 * Le listing est extensible sans rupture de protocole (`PROTOCOLE=2`).
 */
public class ListeurModeles(private val fournisseur: ProjectTemplateProvider) {
    /**
     * Charge les modèles à lister.
     *
     * @param idFiltre modèle unique demandé, ou `null` pour tout le
     * catalogue (ordre alphabétique).
     * @return les modèles chargés.
     * @throws IllegalStateException catalogue ou modèle illisible.
     */
    public suspend fun lister(idFiltre: TemplateId?): List<LoadedTemplate> {
        val catalogue =
            when (val fourniture = fournisseur.provide()) {
                is AppResult.Success -> fourniture.value
                is AppResult.Failure -> error("catalogue illisible : ${fourniture.error}")
            }
        val triee = catalogue.sortedBy { it.template.id.value }
        if (idFiltre == null) return triee
        val trouve =
            triee.firstOrNull { it.template.id == idFiltre }
                ?: throw IllegalStateException(
                    "modèle inconnu : ${idFiltre.value} " +
                        "(catalogue : ${triee.joinToString(", ") { it.template.id.value }})",
                )
        return listOf(trouve)
    }

    /** Représentation JSON complète d'un modèle chargé. */
    public fun enJson(charge: LoadedTemplate): JsonElement = enJson(charge.template)

    /** Représentation JSON complète d'un manifeste validé. */
    public fun enJson(template: ProjectTemplate): JsonElement = buildJsonObject {
        put("id", template.id.value)
        put("templateVersion", template.templateVersion)
        put("nameKey", template.nameKey)
        put("descriptionKey", template.descriptionKey)
        put("category", template.category)
        put("iconKey", template.iconKey)
        put("tags", chaine(template.tags))
        put("parametres", parametresJson(template))
        put("calculees", calculeesJson(template))
        put("contraintes", contraintesJson(template))
        put("autoDefinit", autoDefinitJson(template))
        put("wizard", wizardJsonDe(template))
        put("fichiers", fichiersJson(template))
    }

    /** Paramètres du manifeste, dans l'ordre de déclaration. */
    private fun parametresJson(template: ProjectTemplate): JsonArray = buildJsonArray {
        template.parameters.forEach { parametre ->
            add(
                buildJsonObject {
                    put("id", parametre.id)
                    put("type", parametre.type.name)
                    put("labelKey", parametre.labelKey)
                    parametre.helpKey?.let { put("helpKey", it) }
                    if (parametre.choices.isNotEmpty()) put("choices", chaine(parametre.choices))
                    parametre.defaultValue?.let { put("default", it) }
                    parametre.defaultFrom?.let { put("defaultFrom", it) }
                    parametre.validator?.let { put("validator", it) }
                    parametre.visibleWhen?.let { put("visibleWhen", it) }
                    parametre.enabledWhen?.let { put("enabledWhen", it) }
                    parametre.disabledReasonKey?.let { put("disabledReasonKey", it) }
                    parametre.rendu?.let { put("rendu", it.name) }
                    parametre.groupe?.let { put("groupe", it) }
                    put("avance", parametre.avance)
                    put("section", parametre.section.name)
                    put("persist", parametre.persist)
                },
            )
        }
    }

    /** Variables calculées. */
    private fun calculeesJson(template: ProjectTemplate): JsonArray = buildJsonArray {
        template.computedVariables.forEach { variable ->
            add(
                buildJsonObject {
                    put("name", variable.name)
                    put("expression", variable.expression)
                },
            )
        }
    }

    /** Règles de projet (contraintes). */
    private fun contraintesJson(template: ProjectTemplate): JsonArray = buildJsonArray {
        template.constraints.forEach { regle ->
            add(
                buildJsonObject {
                    put("when", regle.quand)
                    put("severity", regle.severite.name)
                    put("messageKey", regle.messageKey)
                },
            )
        }
    }

    /** Dépendances automatiques. */
    private fun autoDefinitJson(template: ProjectTemplate): JsonArray = buildJsonArray {
        template.autoDefinit.forEach { regle ->
            add(
                buildJsonObject {
                    put("when", regle.quand)
                    put("definit", JsonObject(regle.definit.mapValues { JsonPrimitive(it.value) }))
                    regle.messageKey?.let { put("messageKey", it) }
                },
            )
        }
    }

    /** Entrées de fichiers. */
    private fun fichiersJson(template: ProjectTemplate): JsonArray = buildJsonArray {
        template.fichiers.forEach { fichier ->
            add(
                buildJsonObject {
                    put("path", fichier.chemin)
                    put("source", fichier.source)
                    put("binary", fichier.binary)
                    fichier.whenExpression?.let { put("when", it) }
                    put("group", fichier.group.name)
                },
            )
        }
    }

    /** Représentation texte lisible d'un modèle chargé. */
    public fun enTexte(charge: LoadedTemplate): String = buildString {
        val template = charge.template
        appendLine("${template.id.value} ${template.templateVersion} (${template.category}, ${template.iconKey})")
        appendLine("  ${template.nameKey} — ${template.descriptionKey} ${template.tags}")
        appendLine("  parametres (${template.parameters.size}) :")
        template.parameters.forEach { parametre -> appendLine(ligneParametre(parametre)) }
        if (template.computedVariables.isNotEmpty()) {
            appendLine("  calculees : ${template.computedVariables.joinToString(", ") { it.name }}")
        }
        template.constraints.forEach { regle ->
            appendLine("  contrainte [${regle.severite.name}] ${regle.quand} → ${regle.messageKey}")
        }
        template.autoDefinit.forEach { regle ->
            appendLine("  autoDefinit ${regle.quand} → ${regle.definit}")
        }
        if (template.etapesWizard.isNotEmpty()) {
            appendLine(
                "  wizard etapes : " +
                    template.etapesWizard.joinToString(", ") { etape ->
                        "${etape.id}(${etape.parametres.joinToString("|")})"
                    },
            )
        }
        if (template.presets.isNotEmpty()) {
            appendLine("  wizard presets : ${template.presets.joinToString(", ") { it.id }}")
        }
        appendLine("  fichiers : ${template.fichiers.size}")
    }

    /** Ligne lisible d'un paramètre (type, domaine, défaut, dérivation, rendu). */
    private fun ligneParametre(parametre: jo.codeide.core.model.TemplateParameter): String {
        val domaine = if (parametre.type == TemplateParameterType.CHOICE) {
            "[${parametre.choices.joinToString("|")}]"
        } else {
            ""
        }
        val attributs = buildList {
            add("defaut=${parametre.defaultValue ?: "-"}")
            add("from=${parametre.defaultFrom ?: "-"}")
            add("section=${parametre.section.name}")
            add("persist=${parametre.persist}")
            if (parametre.avance) add("avance")
            parametre.groupe?.let { add("groupe=$it") }
            parametre.rendu?.let { add("rendu=${it.name}") }
        }
        return "    ${parametre.id} ${parametre.type.name}$domaine ${attributs.joinToString(" ")}"
    }
}

/** Tableau JSON de chaînes. */
private fun chaine(valeurs: List<String>): JsonArray = JsonArray(valeurs.map(::JsonPrimitive))

private fun wizardJsonDe(template: ProjectTemplate): JsonElement = buildJsonObject {
    put(
        "etapes",
        buildJsonArray {
            template.etapesWizard.forEach { etape ->
                add(
                    buildJsonObject {
                        put("id", etape.id)
                        put("titreKey", etape.titreKey)
                        etape.iconeKey?.let { put("iconeKey", it) }
                        etape.quand?.let { put("when", it) }
                        put("parametres", chaine(etape.parametres))
                    },
                )
            }
        },
    )
    put(
        "presets",
        buildJsonArray {
            template.presets.forEach { preset ->
                add(
                    buildJsonObject {
                        put("id", preset.id)
                        put("nomKey", preset.nomKey)
                        preset.descriptionKey?.let { put("descriptionKey", it) }
                        put("valeurs", JsonObject(preset.valeurs.mapValues { JsonPrimitive(it.value) }))
                    },
                )
            }
        },
    )
}
