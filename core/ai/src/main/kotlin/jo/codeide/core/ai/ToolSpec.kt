package jo.codeide.core.ai

/**
 * Déclaration d'un outil mis à la disposition du modèle (mission IA,
 * plan IA-1 ; utilisée par l'agent en IA-7, ADR 0125).
 *
 * Le schéma est transporté **tel quel** (JSON Schema sérialisé) : le
 * domaine ne l'interprète pas — seuls les adaptateurs l'encapsulent dans
 * le format du fournisseur (`input_schema` Anthropic, `parameters`
 * compatible OpenAI).
 *
 * @property name nom de l'outil, unique dans une requête (le modèle le
 * rappelle dans les appels d'outils).
 * @property description description fonctionnelle, destinée au modèle.
 * @property parametersJsonSchema schéma des paramètres, JSON Schema
 * sérialisé (objet `{ "type": "object", … }`).
 */
public data class ToolSpec(
    public val name: String,
    public val description: String,
    public val parametersJsonSchema: String,
)
