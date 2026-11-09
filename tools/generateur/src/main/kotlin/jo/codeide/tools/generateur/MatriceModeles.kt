package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.EtatParametre
import jo.codeide.core.model.MessageRegle
import jo.codeide.core.model.SeveriteRegle
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateParameterEvaluation
import jo.codeide.core.model.TemplateParameterType

/**
 * Dérivation de la **matrice des combinaisons** depuis les manifestes
 * (lot L2, ADR 0157 §1) — plus aucune combinaison codée à la main dans le
 * script : ajouter une option à un modèle signale immédiatement le trou de
 * couverture.
 *
 * Principe : l'espace couvert est lu sur l'évaluation du formulaire
 * ([EvaluateTemplateFormUseCase] — la même sortie unique que le wizard,
 * ADR 0153 §5) :
 *
 * - paramètres **énumérables** : `BOOLEAN` (true/false) et `CHOICE` (leurs
 *   choix) ; les `TEXT` ne multiplient pas les cas (ils gardent leur valeur
 *   dérivée ou par défaut) ;
 * - un paramètre **masqué** (`visibleWhen` faux) ou **désactivé**
 *   (`enabledWhen` faux) ne multiplie pas les cas : deux candidats ne
 *   différant que par lui sont équivalents (même signature d'évaluation) et
 *   n'émettent qu'un cas ;
 * - un candidat déclenchant une règle `ERREUR` ou invalidant un paramètre
 *   visible est **exclu et signalé** (JSONL, non bloquant) ;
 * - les stratégies [StrategieMatrice.EXHAUSTIF] (tout l'espace, petit),
 *   [StrategieMatrice.PAIRES] (couverture de toutes les paires de valeurs —
 *   algorithme glouton maison, aucune dépendance nouvelle, règle 9) et
 *   [StrategieMatrice.BORNES] (chaque valeur au moins une fois) sélectionnent
 *   les cas retenus.
 *
 * La dérivation est **déterministe** : même manifeste + même stratégie +
 * même seuil → même matrice (propriété testée).
 */
public class GenerateurMatrice(private val evaluerFormulaire: EvaluateTemplateFormUseCase) {
    /**
     * Dérive la matrice des modèles demandés.
     *
     * @param idsModeles identifiants des modèles couverts (ordre de sortie).
     * @param options stratégie et réglages de dérivation.
     * @return les combinaisons retenues, les exclusions signalées et les
     * bilans par modèle.
     * @throws IllegalArgumentException espace plus grand que le seuil en
     * `EXHAUSTIF`, ou énumération au-delà du plafond de candidats.
     * @throws IllegalStateException manifeste cassé (expression invalide)
     * ou paire atteignable non couverte — ne doit jamais arriver, gardes
     * explicites.
     */
    public suspend fun deriver(idsModeles: List<TemplateId>, options: OptionsMatrice): ResultatMatrice {
        val combinaisons = mutableListOf<Combinaison>()
        val exclusions = mutableListOf<ExclusionMatrice>()
        val bilans = mutableListOf<BilanMatrice>()
        for (id in idsModeles) {
            val derivation = deriverModele(id, options)
            combinaisons += derivation.combinaisons
            exclusions += derivation.exclusions
            bilans += derivation.bilan
        }
        return ResultatMatrice(combinaisons.toList(), exclusions.toList(), bilans.toList())
    }

    /** Dérive la matrice d'un modèle. */
    @Suppress("ReturnCount") // Clauses de garde explicites du seuil et du plafond (règle 16).
    private suspend fun deriverModele(
        id: TemplateId,
        options: OptionsMatrice,
    ): DerivationModele {
        val espace = espaceCandidats(evaluerFormulaire, id, options)
        val enumerables = espace.reference.parameters.filter { it.type != TemplateParameterType.TEXT }
        val tailleEspace = enumerables.fold(1L) { produit, parametre -> produit * parametre.domaine.size }
        if (options.strategie == StrategieMatrice.EXHAUSTIF && tailleEspace > options.seuilExhaustif) {
            throw IllegalArgumentException(
                "modèle ${id.value} : espace de $tailleEspace combinaisons > seuil exhaustif " +
                    "${options.seuilExhaustif} — utilisez la stratégie paires (ou bornes), " +
                    "ou augmentez --seuil",
            )
        }
        if (tailleEspace > options.plafondCandidats) {
            throw IllegalArgumentException(
                "modèle ${id.value} : énumération de $tailleEspace candidats > plafond " +
                    "${options.plafondCandidats} — réduisez l'espace (manifeste) ou augmentez --plafond",
            )
        }

        val candidats = espace.candidats
        val valides = mutableListOf<InstantaneCandidat>()
        val exclusions = mutableListOf<ExclusionMatrice>()
        for (candidat in candidats) {
            if (candidat.estValide) {
                valides += candidat
            } else {
                exclusions += ExclusionMatrice(
                    templateId = id.value,
                    valeurs = candidat.valeursChoisies,
                    raison = candidat.raisonInvalidite(),
                )
            }
        }
        val retenus = dedupliquer(valides)
        val selection =
            when (options.strategie) {
                StrategieMatrice.EXHAUSTIF -> retenus
                StrategieMatrice.PAIRES -> selectionnerPaires(retenus, enumerables)
                StrategieMatrice.BORNES -> selectionnerBornes(retenus, espace.reference, enumerables)
            }
        val paires = pairesAtteignables(retenus, enumerables)
        return DerivationModele(
            combinaisons = emission(id, selection, enumerables, options),
            exclusions = exclusions,
            bilan =
            BilanMatrice(
                templateId = id.value,
                strategie = options.strategie,
                candidats = candidats.size,
                valides = valides.size,
                retenues = selection.size,
                exclues = exclusions.size,
                pairesAtteignables = paires.size,
                pairesCouvertes = pairesCouvertesPar(selection, enumerables).size,
            ),
        )
    }

    /**
     * Déduplique par **signature d'évaluation** (valeurs effectives des
     * paramètres visibles et actifs) : deux candidats ne différant que par
     * des paramètres masqués ou désactivés génèrent le même projet — un
     * seul cas est retenu (le premier dans l'ordre d'énumération).
     */
    private fun dedupliquer(candidats: List<InstantaneCandidat>): List<InstantaneCandidat> {
        val vus = mutableSetOf<String>()
        return candidats.filter { candidat -> vus.add(candidat.signature) }
    }

    /**
     * Stratégie `paires` (pairwise, ADR 0157 §1) : sélection gloutonne
     * couvrant **toutes les paires de valeurs atteignables** — chaque
     * itération retient le candidat couvrant le plus de paires restées
     * découvertes, au plus petit rang d'énumération (déterminisme). La
     * garde finale garantit la propriété : toute paire atteignable est
     * couverte.
     */
    private fun selectionnerPaires(
        candidats: List<InstantaneCandidat>,
        enumerables: List<TemplateParameterEvaluation>,
    ): List<InstantaneCandidat> {
        val pairesParCandidat = candidats.associateWith { it.paires(enumerables).toSet() }
        val restantes = pairesParCandidat.values.flatten().toMutableSet()
        val selection = mutableListOf<InstantaneCandidat>()
        while (restantes.isNotEmpty()) {
            var meilleure: InstantaneCandidat? = null
            var meilleurGain = 0
            for (candidat in candidats) {
                val gain = pairesParCandidat.getValue(candidat).count { it in restantes }
                if (gain > meilleurGain) {
                    meilleure = candidat
                    meilleurGain = gain
                }
            }
            checkNotNull(meilleure) { "paire atteignable sans candidat : $restantes" }
            check(meilleurGain > 0) { "aucun progrès de couverture possible : $restantes" }
            selection += meilleure
            restantes -= pairesParCandidat.getValue(meilleure)
        }
        return selection
    }

    /**
     * Stratégie `bornes` (ADR 0157 §1) : la combinaison par défaut d'abord
     * (le projet « tout par défaut » doit toujours générer), puis chaque
     * valeur atteignable au moins une fois — candidat de plus petit rang
     * d'énumération réalisant la valeur.
     */
    private fun selectionnerBornes(
        candidats: List<InstantaneCandidat>,
        reference: TemplateFormEvaluation,
        enumerables: List<TemplateParameterEvaluation>,
    ): List<InstantaneCandidat> {
        val selection = mutableListOf<InstantaneCandidat>()
        val couvertes = mutableSetOf<String>()
        fun couvrir(candidat: InstantaneCandidat) {
            selection += candidat
            couvertes += candidat.valeursActives.entries.map { "${it.key}=${it.value}" }
        }
        // Le candidat « tout par défaut » : valeurs choisies = valeurs effectives par défaut.
        val valeursDefaut =
            reference.parameters
                .filter { it.type != TemplateParameterType.TEXT }
                .associate { it.parameterId to it.effectiveValue }
        candidats.firstOrNull { it.valeursChoisies == valeursDefaut }?.let(::couvrir)
        for (parametre in enumerables) {
            for (valeur in parametre.domaine) {
                val cle = "${parametre.parameterId}=$valeur"
                if (cle in couvertes) continue
                candidats
                    .firstOrNull { it.valeursActives[parametre.parameterId] == valeur }
                    ?.takeIf { realisant -> selection.none { it.signature == realisant.signature } }
                    ?.let(::couvrir)
            }
        }
        return selection
    }

    /** Paires de valeurs atteignables (présentes dans au moins un candidat). */
    private fun pairesAtteignables(
        candidats: List<InstantaneCandidat>,
        enumerables: List<TemplateParameterEvaluation>,
    ): Set<PaireValeurs> = candidats.flatMap { it.paires(enumerables) }.toSet()

    /** Paires couvertes par une sélection de candidats. */
    private fun pairesCouvertesPar(
        selection: List<InstantaneCandidat>,
        enumerables: List<TemplateParameterEvaluation>,
    ): Set<PaireValeurs> = selection.flatMap { it.paires(enumerables) }.toSet()

    /** Émet les combinaisons retenues (identifiants et étiquettes stables). */
    private fun emission(
        id: TemplateId,
        selection: List<InstantaneCandidat>,
        enumerables: List<TemplateParameterEvaluation>,
        options: OptionsMatrice,
    ): List<Combinaison> = selection.mapIndexed { rang, candidat ->
        val actifs =
            enumerables.mapNotNull { parametre ->
                candidat.valeursActives[parametre.parameterId]?.let { parametre.parameterId to it }
            }
        Combinaison(
            id = "mx-${id.value}-${rang + 1}",
            templateId = id.value,
            nom = options.nomProjet,
            description = "",
            parametres = actifs.toMap(),
            modifiesManuellement = actifs.map { it.first }.toSortedSet(),
            options =
            OptionsCombinaison(
                license = options.licence,
                contentLanguage = options.langueContenu,
            ),
            etiquette =
            "${options.strategie.etiquette} · " +
                actifs.joinToString(", ") { "${it.first}=${it.second}" }.ifEmpty { "défauts" },
        )
    }
}

/**
 * Espace des candidats d'un modèle : l'évaluation de référence (valeurs par
 * défaut) et tous les candidats énumérés, dans l'ordre déterministe.
 */
internal data class EspaceCandidats(
    public val reference: TemplateFormEvaluation,
    public val candidats: List<InstantaneCandidat>,
)

/**
 * Évalue la référence puis énumère l'espace complet des candidats (produit
 * cartésien des paramètres énumérables, dans l'ordre de déclaration), en
 * évaluant chacun. L'ordre d'énumération est l'ordre de sortie — le
 * déterminisme de la matrice en dépend. Partagé par la matrice (ADR 0157
 * §1) et le lint `valider-manifestes` (choix atteignables, expressions).
 *
 * @throws IllegalStateException modèle inconnu.
 * @throws jo.codeide.core.domain.templates.TemplateManifestException
 * manifeste cassé (expression invalide, identifiant inconnu) — le lint le
 * transforme en écart, la matrice échoue explicitement.
 */
internal suspend fun espaceCandidats(
    evaluerFormulaire: EvaluateTemplateFormUseCase,
    id: TemplateId,
    options: OptionsMatrice,
): EspaceCandidats {
    suspend fun evaluer(valeurs: Map<String, String>): TemplateFormEvaluation {
        // Aucun paramètre « modifié à la main » : la matrice simule un
        // formulaire laissé au moteur — les dépendances automatiques
        // (`autoDefinit`, ADR 0153 §4) peuvent donc forcer leurs cibles, et
        // les valeurs choisies des `BOOLEAN`/`CHOICE` sont prises telles
        // quelles (aucune dérivation sur ces types).
        val resultat = evaluerFormulaire(id, options.nomProjet, valeurs, emptySet(), options.langueContenu)
        return when (resultat) {
            is AppResult.Success -> resultat.value

            is AppResult.Failure ->
                error("modèle ${id.value} illisible pour la matrice : ${resultat.error}")
        }
    }

    val reference = evaluer(emptyMap())
    val enumerables = reference.parameters.filter { it.type != TemplateParameterType.TEXT }
    val candidats = mutableListOf<InstantaneCandidat>()
    val curseurs = IntArray(enumerables.size)
    while (true) {
        val valeurs = LinkedHashMap<String, String>()
        enumerables.forEachIndexed { position, parametre ->
            valeurs[parametre.parameterId] = parametre.domaine[curseurs[position]]
        }
        val evaluation = evaluer(valeurs)
        candidats += InstantaneCandidat(valeurs, evaluation)
        // Odomètre : le domaine qui déborde se remet à zéro et reporte.
        var rang = enumerables.size - 1
        while (rang >= 0) {
            curseurs[rang]++
            if (curseurs[rang] < enumerables[rang].domaine.size) break
            curseurs[rang] = 0
            rang--
        }
        if (rang < 0) break
    }
    return EspaceCandidats(reference, candidats.toList())
}

/**
 * Une paire de valeurs couvrable : (paramètre1, valeur1) et
 * (paramètre2, valeur2) simultanément visibles et actifs.
 */
internal data class PaireValeurs(
    public val parametre1: String,
    public val valeur1: String,
    public val parametre2: String,
    public val valeur2: String,
)

/**
 * Un candidat évalué : valeurs choisies des paramètres énumérables, valeurs
 * effectives des paramètres **visibles et actifs** (la signature), validité
 * et messages des règles déclenchées.
 */
internal class InstantaneCandidat(
    public val valeursChoisies: Map<String, String>,
    evaluation: TemplateFormEvaluation,
) {
    /** Paramètres énumérables visibles et actifs → valeur effective. */
    public val valeursActives: Map<String, String> = evaluation.parameters
        .filter { parametre ->
            parametre.type != TemplateParameterType.TEXT &&
                parametre.visible &&
                parametre.etat !is EtatParametre.Desactive
        }.associate { it.parameterId to it.effectiveValue }

    /** Tous les paramètres visibles et actifs → valeur effective (signature complète). */
    private val effectifsActifs: Map<String, String> = evaluation.parameters
        .filter { it.visible && it.etat !is EtatParametre.Desactive }
        .associate { it.parameterId to it.effectiveValue }

    /** Clés des règles `ERREUR` déclenchées (exclusion signalée). */
    private val reglesErreur: List<String> =
        evaluation.messages
            .filter { it.severite == SeveriteRegle.ERREUR }
            .map(MessageRegle::cle)

    /** Paramètres visibles et actifs invalides (« id : raison »). */
    private val parametresInvalides: List<String> = evaluation.parameters
        .filter { it.visible && it.etat !is EtatParametre.Desactive && it.error != null }
        .map { "${it.parameterId} : ${it.error ?: ""}" }

    /** Le candidat est-il générable (aucune règle ERREUR, aucun paramètre invalide) ? */
    public val estValide: Boolean = reglesErreur.isEmpty() && parametresInvalides.isEmpty()

    /**
     * Signature d'équivalence : deux candidats de même signature génèrent
     * le même projet (les masqués/désactivés retombent sur leur défaut).
     */
    public val signature: String =
        effectifsActifs.entries.joinToString("|") { "${it.key}=${it.value}" }

    /** Paires de valeurs couvertes par ce candidat (paramètres actifs deux à deux). */
    public fun paires(enumerables: List<TemplateParameterEvaluation>): List<PaireValeurs> {
        val actifs =
            enumerables.mapNotNull { parametre ->
                valeursActives[parametre.parameterId]?.let { parametre.parameterId to it }
            }
        val paires = mutableListOf<PaireValeurs>()
        for (gauche in actifs.indices) {
            for (droite in gauche + 1 until actifs.size) {
                paires +=
                    PaireValeurs(
                        actifs[gauche].first,
                        actifs[gauche].second,
                        actifs[droite].first,
                        actifs[droite].second,
                    )
            }
        }
        return paires
    }

    /** Raison lisible d'exclusion (règle ERREUR déclenchée ou validation). */
    public fun raisonInvalidite(): String = buildList {
        if (reglesErreur.isNotEmpty()) add("règle ERREUR : ${reglesErreur.joinToString(", ")}")
        if (parametresInvalides.isNotEmpty()) {
            add("paramètre invalide : ${parametresInvalides.joinToString(", ")}")
        }
    }.joinToString(" ; ").ifEmpty { "invalide (raison inconnue)" }
}

/** Options de dérivation de la matrice (ADR 0157 §1). */
public data class OptionsMatrice(
    public val strategie: StrategieMatrice,
    /** Seuil de l'espace en `EXHAUSTIF` (défaut ADR 0157 : ≤ 64). */
    public val seuilExhaustif: Int = 64,
    /** Plafond de sécurité de l'énumération (évaluation pure, par modèle). */
    public val plafondCandidats: Long = 500_000,
    /** Nom du projet simulé pour tous les cas (les dérivations s'y accrochent). */
    public val nomProjet: String = NOM_DEFAUT,
    /** Langue du contenu généré (les cas nommés couvrent l'autre langue). */
    public val langueContenu: String = "fr",
    /** Licence des cas dérivés (les cas nommés couvrent les licences). */
    public val licence: String = "none",
) {
    internal companion object {
        /** Nom neutre du projet simulé (dérivations exercées dessus). */
        internal const val NOM_DEFAUT = "Projet Verification"
    }
}

/** Stratégies de couverture de la matrice (ADR 0157 §1). */
public enum class StrategieMatrice {
    /** Tout l'espace valide (espace petit — seuil configuré, défaut ≤ 64). */
    EXHAUSTIF,

    /** Toutes les paires de valeurs atteignables (glouton, propriété testée). */
    PAIRES,

    /** Chaque valeur de chaque paramètre au moins une fois. */
    BORNES,
    ;

    /** Étiquette lisible du cas (préfixe). */
    internal val etiquette: String
        get() = name.lowercase()
}

/** Exclusion d'un candidat de la matrice (signalée en JSONL, non bloquante). */
@kotlinx.serialization.Serializable
public data class ExclusionMatrice(
    public val templateId: String,
    public val valeurs: Map<String, String>,
    public val raison: String,
)

/** Bilan de dérivation d'un modèle (compteurs de la ligne résumé). */
public data class BilanMatrice(
    public val templateId: String,
    public val strategie: StrategieMatrice,
    public val candidats: Int,
    public val valides: Int,
    public val retenues: Int,
    public val exclues: Int,
    public val pairesAtteignables: Int,
    public val pairesCouvertes: Int,
) {
    /** La couverture des paires est-elle complète (garde de la stratégie) ? */
    public val pairesCouvertesEntierement: Boolean
        get() = strategie != StrategieMatrice.PAIRES || pairesAtteignables == pairesCouvertes
}

/** Résultat complet de la dérivation : sorties consommées par le script. */
public data class ResultatMatrice(
    public val combinaisons: List<Combinaison>,
    public val exclusions: List<ExclusionMatrice>,
    public val bilans: List<BilanMatrice>,
)

/** Domaine de valeurs énumérables d'un paramètre évalué (ordre du manifeste). */
internal val TemplateParameterEvaluation.domaine: List<String>
    get() =
        when (type) {
            TemplateParameterType.BOOLEAN -> listOf("false", "true")
            TemplateParameterType.CHOICE -> choices
            TemplateParameterType.TEXT -> emptyList()
        }

/** Résultat interne de la dérivation d'un modèle. */
private data class DerivationModele(
    val combinaisons: List<Combinaison>,
    val exclusions: List<ExclusionMatrice>,
    val bilan: BilanMatrice,
)
