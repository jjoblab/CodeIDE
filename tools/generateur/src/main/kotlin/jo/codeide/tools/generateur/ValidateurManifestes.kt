package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.LoadedTemplate
import jo.codeide.core.domain.templates.ProjectTemplateProvider
import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateParameterType

/**
 * Lint des manifestes `valider-manifestes` (lot L2, ADR 0157 §1) : c'est lui
 * qui sécurise les lots de modèles L3-L5 — tout écart sort en JSONL et fait
 * échouer la commande (code non nul).
 *
 * Contrôles :
 * - **i18n** : chaque clé i18n **référencée** (modèle, libellés et aides des
 *   paramètres, règles, groupes, raisons de désactivation) existe dans
 *   `i18n/en.json` **et** `i18n/fr.json` ; les clés optionnelles présentes
 *   d'un côté le sont de l'autre (jamais d'asymétrie linguistique) ;
 * - **expressions** : chaque `visibleWhen`/`enabledWhen`/`when` des règles
 *   et variables calculées évalue sans erreur (identifiant inconnu, type
 *   inattendu, borne dépassée → écart) ;
 * - **sources** : chaque `source` déclaré existe dans le répertoire du
 *   modèle (`commun:` — ADR 0156 — entrera ici à son implémentation L3/L4) ;
 * - **chemins dupliqués** : déjà refusés au **chargement** du manifeste
 *   (contrôle du parseur — échec explicite du catalogue, surfaced par la
 *   commande) ; le lint n'a rien à redoubler ;
 * - **choix atteignables** : tout choix d'un paramètre `CHOICE` est
 *   visible et actif dans au moins un contexte de l'espace énuméré
 *   (évaluation totale, plafonnée) — aucun choix rendu impossible par les
 *   visibilités combinées.
 */
public class ValidateurManifestes(
    private val assets: TemplateAssetsSource,
    private val fournisseur: ProjectTemplateProvider,
    private val evaluerFormulaire: EvaluateTemplateFormUseCase,
) {
    /**
     * Valide les modèles demandés (tous le catalogue si liste vide).
     *
     * @param idsModeles identifiants ciblés ; vide = tous les modèles chargés.
     * @param options réglages de l'évaluation d'énumération.
     * @return la liste des écarts (vide = manifestes sains).
     */
    public suspend fun valider(idsModeles: List<TemplateId>, options: OptionsMatrice): List<EcartManifeste> {
        val cibles = cibles(idsModeles)
        val ecarts = mutableListOf<EcartManifeste>()
        for (charge in cibles) {
            ecarts += validerModele(charge, options)
        }
        return dedupliquer(ecarts)
    }

    /** Résout les modèles cibles (liste demandée, sinon tout le catalogue). */
    private suspend fun cibles(idsModeles: List<TemplateId>): List<LoadedTemplate> {
        val catalogue =
            when (val fourniture = fournisseur.provide()) {
                is AppResult.Success -> fourniture.value
                is AppResult.Failure -> error("catalogue illisible : ${fourniture.error}")
            }
        if (idsModeles.isEmpty()) return catalogue.sortedBy { it.template.id.value }
        val demandes = idsModeles.map { it.value }.toSet()
        val trouves = catalogue.filter { it.template.id.value in demandes }
        val manquants = demandes - trouves.map { it.template.id.value }.toSet()
        check(manquants.isEmpty()) { "modèles inconnus : ${manquants.sorted().joinToString(", ")}" }
        return trouves.sortedBy { it.template.id.value }
    }

    /** Valide un modèle chargé et rapporte ses écarts. */
    private suspend fun validerModele(charge: LoadedTemplate, options: OptionsMatrice): List<EcartManifeste> {
        val id = charge.template.id.value
        val ecarts = mutableListOf<EcartManifeste>()
        ecarts += validerI18n(charge)
        ecarts += validerExpressionsEtChoix(id, options)
        ecarts += validerSources(charge)
        return ecarts
    }

    /** Clés i18n obligatoires et asymétries de clés optionnelles. */
    private fun validerI18n(charge: LoadedTemplate): List<EcartManifeste> {
        val ecarts = mutableListOf<EcartManifeste>()
        val anglais = charge.dictionaries.getValue("en")
        val francais = charge.dictionaries["fr"]
        if (francais == null) {
            ecarts += EcartManifeste(
                charge.template.id.value,
                "i18n",
                "dictionnaire français absent (i18n/fr.json) — les libellés doivent exister en fr et en",
            )
            return ecarts
        }
        val template = charge.template
        // Clés RÉFÉRENCÉES par le manifeste : obligatoires en fr et en.
        // Clés optionnelles des choix : dès qu'une existe, elle existe des deux
        // côtés (jamais d'asymétrie) — l'absence même de `libelle` reste permise
        // (repli sur la valeur brute, ADR 0154 §1) tant que l'i18n de choix
        // n'est pas généralisé aux manifestes (lots L3/L4).
        val obligatoires = mutableListOf(template.nameKey, template.descriptionKey)
        val optionnelles = mutableListOf<String>()
        for (parametre in template.parameters) {
            obligatoires += parametre.labelKey
            parametre.helpKey?.let(optionnelles::add)
            parametre.disabledReasonKey?.let(obligatoires::add)
            parametre.groupe?.let { groupe -> obligatoires += "groupe.$groupe.titre" }
            optionnelles += clesDeChoix(parametre)
        }
        template.constraints.forEach { obligatoires += it.messageKey }
        template.autoDefinit.forEach { regle -> regle.messageKey?.let(obligatoires::add) }
        for (cle in obligatoires) {
            if (cle !in anglais) {
                ecarts += EcartManifeste(charge.template.id.value, "i18n", "clé absente de en : $cle")
            }
            if (cle !in francais) {
                ecarts += EcartManifeste(charge.template.id.value, "i18n", "clé absente de fr : $cle")
            }
        }
        for (cle in optionnelles) {
            val enAnglais = cle in anglais
            val enFrancais = cle in francais
            if (enAnglais != enFrancais) {
                ecarts += EcartManifeste(
                    charge.template.id.value,
                    "i18n",
                    "clé optionnelle asymétrique (en=${if (enAnglais) "oui" else "non"}, " +
                        "fr=${if (enFrancais) "oui" else "non"}) : $cle",
                )
            }
        }
        return ecarts
    }

    /** Clés optionnelles des choix d'un paramètre (`CHOICE`), symétriques fr/en. */
    private fun clesDeChoix(parametre: jo.codeide.core.model.TemplateParameter): List<String> =
        if (parametre.type != TemplateParameterType.CHOICE) {
            emptyList()
        } else {
            parametre.choices.flatMap { valeur ->
                listOf("libelle", "sousTitre", "description", "icone")
                    .map { suffixe -> "param.${parametre.id}.choix.$valeur.$suffixe" }
            }
        }

    /**
     * Expressions évaluables et choix atteignables : l'énumération de
     * l'espace (partagée avec la matrice) exerce toutes les expressions —
     * une [Exception] du moteur est un écart ; tout choix de `CHOICE`
     * jamais visible ni actif est un écart.
     */
    @Suppress("TooGenericExceptionCaught") // Le catch EST le livrable : l'expression cassée devient un écart.
    private suspend fun validerExpressionsEtChoix(
        id: String,
        options: OptionsMatrice,
    ): List<EcartManifeste> {
        val ecarts = mutableListOf<EcartManifeste>()
        val espace =
            try {
                espaceCandidats(evaluerFormulaire, TemplateId(id), options)
            } catch (erreur: Exception) {
                return listOf(EcartManifeste(id, "expression", erreur.message ?: "expression invalide"))
            }
        val parametresChoix =
            espace.reference.parameters
                .filter { it.type == TemplateParameterType.CHOICE }
        val atteints = mutableSetOf<String>()
        for (candidat in espace.candidats) {
            atteints += candidat.valeursActives.entries.map { "${it.key}=${it.value}" }
        }
        for (parametre in parametresChoix) {
            for (valeur in parametre.choices) {
                if ("${parametre.parameterId}=$valeur" !in atteints) {
                    ecarts += EcartManifeste(
                        id,
                        "choix-non-atteignable",
                        "aucun contexte rend « ${parametre.parameterId} » visible et actif avec « $valeur »",
                    )
                }
            }
        }
        return ecarts
    }

    /**
     * Chaque source déclaré existe dans le répertoire du modèle — les
     * chemins de sortie dupliqués sont, eux, refusés au chargement du
     * manifeste (parseur) : un catalogue qui arrive ici n'en contient pas.
     */
    private suspend fun validerSources(charge: LoadedTemplate): List<EcartManifeste> {
        val ecarts = mutableListOf<EcartManifeste>()
        for (fichier in charge.template.fichiers) {
            if (fichier.source.startsWith("commun:")) continue // ADR 0156 — implémentation L3/L4.
            val lecture = assets.readTemplateFile(charge.template.id.value, fichier.source)
            if (lecture is AppResult.Failure &&
                (lecture.error as? AppError.Storage)?.reason == AppError.StorageReason.NotFound
            ) {
                ecarts += EcartManifeste(
                    charge.template.id.value,
                    "source-absente",
                    "« ${fichier.source} » (vers « ${fichier.chemin} ») introuvable",
                )
            }
        }
        return ecarts
    }

    /** Déduplique les écarts identiques (même modèle, même règle, même détail). */
    private fun dedupliquer(ecarts: List<EcartManifeste>): List<EcartManifeste> {
        val vus = mutableSetOf<String>()
        return ecarts.filter { ecart -> vus.add("${ecart.templateId}#${ecart.regle}#${ecart.detail}") }
    }
}

/** Un écart du lint (JSONL, code de sortie non nul). */
@kotlinx.serialization.Serializable
public data class EcartManifeste(public val templateId: String, public val regle: String, public val detail: String)
