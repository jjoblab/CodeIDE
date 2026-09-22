package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppResult

/**
 * Modèle chargé : le [jo.codeide.core.model.ProjectTemplate] validé et ses
 * dictionnaires i18n (`i18n/en.json` requis, `i18n/fr.json` optionnel).
 *
 * Les dictionnaires voyagent avec le modèle car le moteur en a besoin au
 * rendu (`{{t:clé}}`) et à la résolution des libellés — l'UI, elle, ne
 * reçoit que des libellés déjà résolus ([jo.codeide.core.model.TemplateSummary]).
 *
 * @property template le modèle validé.
 * @property dictionaries dictionnaires par langue (`"en"`, `"fr"`) ; le
 * repli sur l'anglais est appliqué par [dictionnaireEffectif].
 */
public data class LoadedTemplate(
    public val template: jo.codeide.core.model.ProjectTemplate,
    public val dictionaries: Map<String, Map<String, String>>,
) {
    init {
        require("en" in dictionaries) {
            "Le dictionnaire anglais (repli) est requis pour tout modèle chargé."
        }
    }

    /**
     * Dictionnaire effectif pour [langue] : celui de la langue, complété par
     * l'anglais pour les clés absentes (repli documenté, section 11).
     */
    public fun dictionnaireEffectif(langue: String): Map<String, String> {
        val repli = dictionaries.getValue("en")
        val demande = dictionaries[langue.lowercase().takeWhile { it != '-' }] ?: return repli
        return repli + demande
    }
}

/**
 * Fournisseur de modèles de projet (étape 8 — extension, section 11).
 *
 * Les fournisseurs s'enregistrent en **multibinding Hilt** (`@IntoSet`) :
 * ajouter des modèles ne modifie ni le moteur ni le code existant — le
 * fournisseur embarqué lit `assets/templates/` ; les futurs plugins
 * apporteront les leurs sans recompilation du moteur.
 */
public interface ProjectTemplateProvider {
    /**
     * Fournit les modèles chargés et validés.
     *
     * @return la liste des modèles, ou l'échec typé (un manifeste corrompu
     * du lot échoue explicitement — pas de catalogue silencieusement amputé).
     */
    public suspend fun provide(): AppResult<List<LoadedTemplate>>
}
