package jo.codeide.core.domain.templates

import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.TemplateEtapeEvaluee
import jo.codeide.core.model.TemplateFileGroup
import jo.codeide.core.model.TemplatePresetResolu

/**
 * Moteur du **bloc `wizard`** du manifeste (ADR 0155 — lot L5a) : calcul
 * des étapes de modèle **effectives** et résolution de leurs libellés.
 *
 * Extrait de [TemplateEngine] pour rester sous les bornes detekt
 * (LargeClass / LongMethod) — le moteur délègue. Les décisions :
 * - une étape déclarée dont `when` est faux **n'existe pas** (jamais
 *   d'écran vide ni de « Suivant » inutile) ;
 * - un paramètre `CONFIGURATION` cité dans une étape aujourd'hui filtrée
 *   n'est **pas** déplacé : son étape réapparaîtra si la condition
 *   redevient vraie — seul un paramètre jamais rangé rejoint l'étape par
 *   défaut « Configuration » ;
 * - les titres et icônes sont résolus dans la langue demandée
 *   (dictionnaire du modèle) ; l'étape par défaut porte un titre vide —
 *   son libellé est une ressource **applicative**.
 */
internal object EtapesWizardMoteur {
    /** Identifiant de l'étape par défaut « Configuration » (ADR 0155 §1). */
    internal const val ID_ETAPE_DEFAUT = "configuration"

    /** Groupes de fichiers optionnels pilotés par l'étape Fichiers. */
    private val GROUPES_OPTIONNELS =
        setOf(
            TemplateFileGroup.README,
            TemplateFileGroup.GITIGNORE,
            TemplateFileGroup.EDITORCONFIG,
        )

    /**
     * Calcule la liste des étapes de modèle effectives (ADR 0155 §1/§3) :
     * étapes déclarées dont `when` est vrai (dans l'ordre du manifeste),
     * puis l'étape par défaut « Configuration » si des paramètres
     * `CONFIGURATION` ne sont rangés dans aucune étape **déclarée**.
     */
    fun effectives(template: ProjectTemplate, contexteFinal: ContexteExpressions): List<EtapeEffective> {
        val declarees =
            template.etapesWizard.mapNotNull { etape ->
                val condition = etape.quand
                val presente =
                    condition == null ||
                        condition(condition, contexteFinal, "when de l'étape « ${etape.id} »")
                if (presente) EtapeEffective(etape.id, etape.parametres, defaut = false) else null
            }
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

    /**
     * Résout les étapes effectives en sortie affichable : titres et icônes
     * du dictionnaire (repli sur la clé brute — repli théorique, le
     * fournisseur embarqué refuse les clés manquantes au chargement).
     */
    fun resolues(
        template: ProjectTemplate,
        effectives: List<EtapeEffective>,
        dictionnaire: Map<String, String>,
    ): List<TemplateEtapeEvaluee> = effectives.map { etape ->
        val declaree = template.etapesWizard.firstOrNull { it.id == etape.id }
        TemplateEtapeEvaluee(
            id = etape.id,
            // Étape par défaut : titre applicatif (vide).
            titre = if (etape.defaut) {
                ""
            } else {
                dictionnaire[declaree?.titreKey ?: "etape.${etape.id}.titre"]
                    ?: etape.id
            },
            icone = declaree?.iconeKey?.let { dictionnaire[it] } ?: "",
            parametres = etape.parametres,
            defaut = etape.defaut,
        )
    }

    /** Préréglages résolus (ADR 0155 §1, B-8). */
    fun presetsResolus(template: ProjectTemplate, dictionnaire: Map<String, String>): List<TemplatePresetResolu> =
        template.presets.map { preset ->
            TemplatePresetResolu(
                id = preset.id,
                nom = dictionnaire[preset.nomKey] ?: preset.nomKey,
                description = preset.descriptionKey?.let { dictionnaire[it] } ?: "",
                valeurs = preset.valeurs,
            )
        }

    /** Groupes de fichiers optionnels réellement déclarés (ADR 0155 §2). */
    fun groupesOptionnels(template: ProjectTemplate): Set<TemplateFileGroup> =
        template.fichiers.map { it.group }.filter { it in GROUPES_OPTIONNELS }.toSet()

    /** Évalue une condition déclarative en exigeant un booléen. */
    @Suppress("SwallowedException") // Transformée en échec explicite du manifeste (jamais avalée).
    private fun condition(
        expression: String,
        contexte: ContexteExpressions,
        origine: String,
    ): Boolean {
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
}
