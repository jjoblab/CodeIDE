package jo.codeide.feature.editor

import jo.codeide.lsp.host.BilanDernierPassageIndexation
import jo.codeide.lsp.host.BreakdownIndexeurIndexation
import jo.codeide.lsp.host.EtatIndexation
import jo.codeide.lsp.host.PhaseIndexation

/**
 * Modèle d'affichage de l'indexation (Phase B prompt 2, ADR 0117 §4.4 —
 * lot B4 ; v0.178.0, ADR 0145 : MULTI-MOTEURS). Produit par
 * [PresentationIndexation] à partir de [EtatsMoteursIndexation]
 * (l'agrégat Java + Kotlin + XML de `EditorViewModel`). Le présentateur
 * est PUR — il ne touche ni `Context` ni vue ; la résolution des
 * libellés localisés a lieu au dernier moment par l'appelant (patron
 * [PresentationTooling]).
 *
 * Le modèle transporte des ÉNUMÉRATIONS (jamais de chaînes comparables —
 * règle §5.11 du prompt 2) et des données brutes (nombres, durées en ms).
 * Les libellés localisés sont des identifiants de ressource résolus par
 * l'UI.
 */
internal data class ModeleAffichageIndexation(
    /** État visuel de la pastille de toolbar. */
    val pastille: PastilleIndexation,
    /** État visuel du panneau de détail (bottom sheet). */
    val panneau: PanneauDetailIndexation,
)

/**
 * Pastille de toolbar — indicateur discret dans la barre d'application.
 */
internal data class PastilleIndexation(
    /** État visuel de la pastille (détermine icône + couleur). */
    val etat: EtatPastilleIndexation,
    /** Pourcentage de progression (0-100) pendant la construction,
     *  `null` sinon (pas de chiffre affiché au repos). */
    val pourcentage: Int?,
    /** Masquée sur écran étroit au repos (l'information « tout va bien »
     *  n'a pas besoin d'être visible). */
    val masqueeEtroit: Boolean,
)

/**
 * État visuel de la pastille.
 */
internal enum class EtatPastilleIndexation {
    /** Construction en cours : icône animée + pourcentage. */
    CONSTRUCTION,

    /** Index à jour : coche discrète. */
    PRET,

    /** Index partiel : triangle d'avertissement. */
    PARTIEL,

    /** Index en erreur : icône d'erreur. */
    ERREUR,

    /** Avant tout sync : pas de pastille. */
    INITIAL,
}

/**
 * Panneau de détail (bottom sheet) — affiché au toucher de la pastille.
 * V0.178.0 (ADR 0145) : sections de la maquette ENFIN complètes —
 * vue par moteur MULTI-MOTEURS, entrées écartées, erreurs bloquantes,
 * résumé des reprises, histogramme des coûts.
 */
internal data class PanneauDetailIndexation(
    /** En-tête du panneau (icône + titre + sous-titre + pourcentage). */
    val entete: EnteteDetailIndexation,
    /** Barre de progression (déterminée ou indéterminée). */
    val progression: ProgressionDetailIndexation,
    /** Résumé des reprises pendant la construction. `null` sinon. */
    val resumeReprises: TexteIndexation?,
    /** Liste de travail (entrées en cours), triée. `null` si non applicable. */
    val listeTravail: List<EntreeDetailIndexation>?,
    /** Vue par moteur (Java, Kotlin, XML). `null` si non applicable. */
    val moteurs: List<MoteurDetailIndexation>?,
    /** Entrées écartées (index PARTIEL — jar illisible, AAR sans jar…). */
    val entreesEcartees: List<EntreeEcarteeDetail>,
    /** Erreurs bloquantes (index ERREUR — stockage illisible…). */
    val erreursBloquantes: List<EntreeEcarteeDetail>,
    /** Histogramme « ce qui a coûté le plus de temps » (au repos). */
    val couts: List<CoutDetailIndexation>,
    /** Bilan du dernier passage. `null` si aucun passage. */
    val bilan: BilanDetailIndexation?,
    /** Action(s) proposée(s) à l'utilisateur. */
    val actions: List<ActionDetailIndexation>,
    /** Note d'impact utilisateur (« pendant l'indexation, la complétion
     *  des bibliothèques est limitée »). `null` si non applicable. */
    val noteImpact: NoteImpactIndexation?,
)

/**
 * En-tête du panneau de détail.
 */
internal data class EnteteDetailIndexation(
    /** État visuel de l'icône d'en-tête. */
    val etat: EtatPastilleIndexation,
    /** Titre localisé (identifiant de ressource). */
    val titre: Int,
    /** Sous-titre localisé (identifiant de ressource + arguments). */
    val sousTitre: TexteIndexation,
    /** Pourcentage affiché à droite de l'en-tête pendant la construction,
     *  `null` sinon (maquette §2 : « 62 % »). */
    val pourcentageDroite: Int?,
)

/**
 * Texte localisable produit par le présentateur (patron [TexteTooling]).
 */
internal sealed interface TexteIndexation {
    /**
     * Libellé d'une ressource de chaîne (identifiant + arguments).
     *
     * Les arguments doivent CONFORMER aux substituants de la ressource :
     * un `%1$d` exige un [Int] — v0.178.1 plantait à l'ouverture du
     * panneau (rapport 032311ee, `IllegalFormatConversionException :
     * d != java.lang.String`) parce que les décomptes de reprises
     * arrivaient interpolés en `String`. Ne JAMAIS interpoler un nombre
     * destiné à un `%d` — passer l'entier tel quel.
     */
    data class Ressource(val id: Int, val args: List<Any> = emptyList()) : TexteIndexation

    /** Libellé brut (message technique déjà formulé). */
    data class Brut(val texte: String) : TexteIndexation
}

/**
 * Barre de progression du panneau de détail. VISIBLE seulement pendant
 * la construction (maquette : pas de barre au repos ni en erreur).
 */
internal data class ProgressionDetailIndexation(
    /** `true` pour une barre indéterminée (total inconnu), `false` pour
     *  une barre déterminée. */
    val indeterminee: Boolean,
    /** Fraction de progression (0.0-1.0) pour une barre déterminée. */
    val fraction: Float,
    /** Compteur « n / N » localisé. `null` si non applicable. */
    val compteur: TexteIndexation?,
    /** Estimation du temps restant (ms). `null` si non fiable. */
    val tempsRestantMs: Long?,
    /** La barre est-elle VISIBLE ? (construction seule — v0.178.0). */
    val visible: Boolean,
) {
    public companion object {
        /** Barre cachée (repos, partiel, erreur). */
        public val MASQUEE: ProgressionDetailIndexation =
            ProgressionDetailIndexation(
                indeterminee = false,
                fraction = 1f,
                compteur = null,
                tempsRestantMs = null,
                visible = false,
            )
    }
}

/**
 * Entrée de la liste de travail dans le panneau de détail.
 */
internal data class EntreeDetailIndexation(
    /** Nom lisible de l'entrée (ex. `androidx.core 1.19.0`). */
    val nom: String,
    /** État de l'entrée (détermine l'icône). */
    val etat: EtatEntreeDetail,
)

/**
 * État d'une entrée dans la liste de travail du panneau.
 */
internal enum class EtatEntreeDetail {
    /** En attente de traitement. */
    EN_ATTENTE,

    /** En cours de traitement. */
    ACTIF,

    /** Terminée avec succès. */
    TERMINE,

    /** Reprise sans reconstruction. */
    REPRIS,

    /** Écartée honnêtement. */
    ECARTE,
}

/**
 * Entrée ÉCARTÉE ou ERREUR BLOQUANTE du panneau — message technique déjà
 * formulé par le moteur (brut par nature, jamais un libellé comparable).
 */
internal data class EntreeEcarteeDetail(
    /** Message (ex. `legacy-utils.jar : jar illisible`). */
    val texte: String,
)

/**
 * Vue par moteur dans le panneau de détail (v0.178.0, ADR 0145 : la
 * liste itère l'agrégat, AUCUN moteur nommé — Java/Kotlin/XML vivent
 * dans [MoteurIndexation], un moteur absent de l'agrégat n'a pas de
 * rangée).
 */
internal data class MoteurDetailIndexation(
    /** Nom du moteur (Java, Kotlin, XML — ressource). */
    val nom: Int,
    /** Couleur d'accent du moteur (token de langage, ADR 0059). */
    val couleurAccent: Int,
    /** État du moteur. */
    val etat: EtatMoteurDetail,
    /** Détail localisé (ex. « 118/191 », « Prêt », « En construction »). */
    val detail: TexteIndexation?,
)

/**
 * État d'un moteur dans la vue par moteur.
 */
internal enum class EtatMoteurDetail {
    /** En construction. */
    CONSTRUCTION,

    /** Prêt. */
    PRET,

    /** Partiel. */
    PARTIEL,

    /** Erreur. */
    ERREUR,

    /** En attente de synchronisation. */
    INITIAL,
}

/**
 * Barre de l'histogramme « ce qui a coûté le plus de temps » (au repos,
 * maquette §4 : 3 indexeurs les plus coûteux, largeur relative au max).
 */
internal data class CoutDetailIndexation(
    /** Libellé de l'indexeur (ex. « Noms de classes Java »). */
    val libelle: String,
    /** Durée cumulée (ms). */
    val millisecondes: Long,
    /** Largeur relative (0.0-1.0, 1.0 = indexeur le plus coûteux). */
    val fraction: Float,
)

/**
 * Bilan du dernier passage dans le panneau de détail.
 */
internal data class BilanDetailIndexation(
    /** Durée totale (ms). */
    val dureeMs: Long,
    /** Nombre total d'artefacts. */
    val artefactsTotal: Int,
    /** Nombre d'artefacts repris. */
    val artefactsRepris: Int,
    /** Nombre d'artefacts reconstruits. */
    val artefactsReconstruits: Int,
    /** Nombre de fichiers parcourus (phase sources). */
    val fichiersParcourus: Int,
    /** Nombre de fichiers ré-analysés. */
    val fichiersReanalyses: Int,
    /** Indexeur le plus coûteux (identifiant). `null` si aucun. */
    val indexeurPlusCouteux: String?,
    /** Horodatage du passage (epoch UTC, ms). */
    val horodatageMs: Long,
)

/**
 * Action proposée à l'utilisateur dans le panneau de détail. V0.180.0
 * (ADR 0146) : les TROIS actions sont ENFIN vivantes — [ANNULER] est
 * émis pendant la construction (point d'annulation coopératif livré
 * côté frère v1.8.0, DEMANDES D5 — le « bouton mort » de la maquette
 * devient réel) et [REESSAYER_ECHECS] appelle la reprise CIBLÉE
 * (DEMANDES D4).
 */
internal enum class ActionDetailIndexation {
    /** Réindexer tout (action LOURDE : revalidation + auto-guérison, moteur Java). */
    REINDEXER,

    /** Réessayer les entrées échouées seulement — reprise CIBLÉE sur les
     *  TROIS moteurs (DEMANDES D4 livrée, frère v1.8.0). */
    REESSAYER_ECHECS,

    /** Annuler le passage en cours — émis pendant la CONSTRUCTION
     *  (DEMANDES D5 livrée, frère v1.8.0). */
    ANNULER,
}

/**
 * Note d'impact utilisateur dans le panneau de détail.
 */
internal data class NoteImpactIndexation(
    /** Niveau de la note (info, avertissement, erreur). */
    val niveau: NiveauNoteImpact,
    /** Texte localisé de la note. */
    val texte: TexteIndexation,
)

/**
 * Niveau d'une note d'impact.
 */
internal enum class NiveauNoteImpact {
    /** Information neutre. */
    INFO,

    /** Avertissement (partiel). */
    AVERTISSEMENT,

    /** Erreur bloquante. */
    ERREUR,
}

/**
 * Présentateur PUR de l'écran d'indexation (Phase B prompt 2, ADR 0117
 * §4.4 — lot B4 ; v0.178.0, ADR 0145 : MULTI-MOTEURS). Transforme
 * [EtatsMoteursIndexation] (agrégat des moteurs Java + Kotlin + XML,
 * état global = PIRE phase) en [ModeleAffichageIndexation] (modèle
 * d'affichage immutable, sans aucune référence Android). Testé
 * unitairement — tous les états, y compris multi-moteurs divergents
 * (Java en construction pendant que Kotlin est prêt), partiel avec
 * écarts, erreur bloquante, barre indéterminée, histogramme des coûts.
 *
 * Patron : [PresentationTooling] — le présentateur ne touche ni
 * `Context` ni vue ; la résolution des libellés localisés a lieu au
 * dernier moment par l'appelant.
 *
 * Exemption detekt ciblée (règle 16) : `TooManyFunctions` — chaque
 * fonction traduit une SECTION DISTINCTE du panneau (miroir des
 * champs de [PanneauDetailIndexation]) ; les regrouper masquerait la
 * structure du présentateur. Même précédent que `PresentationTooling`
 * (v4, §3.2).
 */
@Suppress("TooManyFunctions")
internal object PresentationIndexation {
    /**
     * Produit le modèle d'affichage à partir de l'agrégat des moteurs.
     *
     * @param etats agrégat multi-moteurs (Java + Kotlin + XML).
     * @return modèle d'affichage (pastille + panneau de détail).
     */
    fun afficher(etats: EtatsMoteursIndexation): ModeleAffichageIndexation = ModeleAffichageIndexation(
        pastille = pastilleDe(etats),
        panneau = panneauDe(etats),
    )

    /**
     * États RICHES des moteurs présents (v0.180.0, ADR 0146 — D3 livrée :
     * les TROIS langues publient `etatIndexation` ; un pack frère ancien
     * reste `null` et dégrade honnêtement).
     */
    private fun etatsRichesDe(etats: EtatsMoteursIndexation): List<EtatIndexation> =
        etats.moteurs.mapNotNull { it.etat }

    /** Compteurs SOMMÉS des moteurs en construction à total connu (v0.180.0 :
     *  la barre GLOBALE agrège les passes en vol, jamais un moteur nommé). */
    private data class CompteursConstruction(val faits: Int, val total: Int)

    private fun compteursConstructionDe(riches: List<EtatIndexation>): CompteursConstruction {
        val enConstruction = riches.filter { it.phase == PhaseIndexation.CONSTRUCTION && it.total > 0 }
        return CompteursConstruction(
            faits = enConstruction.sumOf(EtatIndexation::faits),
            total = enConstruction.sumOf(EtatIndexation::total),
        )
    }

    /** Pastille de toolbar — l'état global est la PIRE phase (ADR 0145). */
    private fun pastilleDe(etats: EtatsMoteursIndexation): PastilleIndexation = when (val phase = etats.pirePhase) {
        PhaseIndexation.INITIAL -> {
            PastilleIndexation(
                etat = EtatPastilleIndexation.INITIAL,
                pourcentage = null,
                masqueeEtroit = true,
            )
        }

        PhaseIndexation.CONSTRUCTION -> {
            PastilleIndexation(
                etat = EtatPastilleIndexation.CONSTRUCTION,
                pourcentage = pourcentageDe(etatsRichesDe(etats)),
                masqueeEtroit = false,
            )
        }

        PhaseIndexation.PRET -> {
            PastilleIndexation(
                etat = EtatPastilleIndexation.PRET,
                pourcentage = null,
                masqueeEtroit = true,
            )
        }

        PhaseIndexation.PARTIEL -> {
            PastilleIndexation(
                etat = EtatPastilleIndexation.PARTIEL,
                pourcentage = null,
                masqueeEtroit = false,
            )
        }

        PhaseIndexation.ERREUR -> {
            PastilleIndexation(
                etat = EtatPastilleIndexation.ERREUR,
                pourcentage = null,
                masqueeEtroit = false,
            )
        }
    }

    /**
     * Panneau de détail selon l'agrégat. V0.180.0 (ADR 0146) : TOUTES
     * les sections globales lisent les états RICHES de TOUS les
     * moteurs (compteurs sommés, listes fusionnées, bilan le plus
     * récent) — Java n'est plus codé en dur ici non plus.
     */
    private fun panneauDe(etats: EtatsMoteursIndexation): PanneauDetailIndexation {
        val riches = etatsRichesDe(etats)
        return PanneauDetailIndexation(
            entete = enteteDe(etats.pirePhase, riches),
            progression = progressionDe(etats.pirePhase, riches),
            resumeReprises = resumeReprisesDe(etats.pirePhase, riches),
            listeTravail = listeTravailDe(riches),
            moteurs = moteursDe(etats),
            entreesEcartees = entreesEcarteesDe(riches),
            erreursBloquantes = erreursBloquantesDe(riches),
            couts = coutsDe(riches),
            bilan = bilanDe(riches),
            actions = actionsDe(etats.pirePhase, riches),
            noteImpact = noteImpactDe(etats.pirePhase, riches),
        )
    }

    /** En-tête — titre + sous-titre de la phase GLOBALE (pire phase) ;
     *  compteur « x sur y » SOMMÉ sur les moteurs en construction. */
    private fun enteteDe(phase: PhaseIndexation, riches: List<EtatIndexation>): EnteteDetailIndexation = when (phase) {
        PhaseIndexation.INITIAL -> {
            EnteteDetailIndexation(
                etat = EtatPastilleIndexation.INITIAL,
                titre = R.string.editor_indexation_initial_titre,
                sousTitre = TexteIndexation.Ressource(R.string.editor_indexation_initial_soustitre),
                pourcentageDroite = null,
            )
        }

        PhaseIndexation.CONSTRUCTION -> {
            val compteurs = compteursConstructionDe(riches)
            EnteteDetailIndexation(
                etat = EtatPastilleIndexation.CONSTRUCTION,
                titre = R.string.editor_indexation_construction_titre,
                sousTitre =
                if (compteurs.total > 0) {
                    TexteIndexation.Ressource(
                        R.string.editor_indexation_construction_soustitre_n,
                        listOf("${compteurs.faits}", "${compteurs.total}"),
                    )
                } else {
                    TexteIndexation.Ressource(R.string.editor_indexation_construction_soustitre)
                },
                pourcentageDroite = pourcentageDe(riches),
            )
        }

        PhaseIndexation.PRET -> {
            EnteteDetailIndexation(
                etat = EtatPastilleIndexation.PRET,
                titre = R.string.editor_indexation_pret_titre,
                sousTitre = TexteIndexation.Ressource(R.string.editor_indexation_pret_soustitre),
                pourcentageDroite = null,
            )
        }

        PhaseIndexation.PARTIEL -> {
            EnteteDetailIndexation(
                etat = EtatPastilleIndexation.PARTIEL,
                titre = R.string.editor_indexation_partiel_titre,
                sousTitre = sousTitrePartielDe(riches),
                pourcentageDroite = null,
            )
        }

        PhaseIndexation.ERREUR -> {
            EnteteDetailIndexation(
                etat = EtatPastilleIndexation.ERREUR,
                titre = R.string.editor_indexation_erreur_titre,
                sousTitre = TexteIndexation.Ressource(R.string.editor_indexation_erreur_soustitre),
                pourcentageDroite = null,
            )
        }
    }

    /**
     * Sous-titre du PARTIEL : « x sur y » SOMMÉ sur les moteurs en
     * partiel à total connu (v0.180.0, ADR 0146 — le passage
     * interrompu, toutes langues confondues) ; sans décompte sinon.
     */
    private fun sousTitrePartielDe(riches: List<EtatIndexation>): TexteIndexation {
        val enPartiel = riches.filter { it.phase == PhaseIndexation.PARTIEL && it.total > 0 }
        return if (enPartiel.isNotEmpty()) {
            TexteIndexation.Ressource(
                R.string.editor_indexation_partiel_soustitre,
                listOf(
                    "${enPartiel.sumOf(EtatIndexation::faits)}",
                    "${enPartiel.sumOf(EtatIndexation::total)}",
                ),
            )
        } else {
            TexteIndexation.Ressource(R.string.editor_indexation_partiel_soustitre_sans_n)
        }
    }

    /**
     * Progression — VISIBLE pendant la construction SEULE (maquette :
     * pas de barre au repos ni en erreur). Aucun compteur connu → barre
     * INDÉTERMINÉE (pack ancien sans état observable) ; sinon fraction
     * SOMMÉE sur les moteurs en construction (v0.180.0, ADR 0146).
     */
    private fun progressionDe(phase: PhaseIndexation, riches: List<EtatIndexation>): ProgressionDetailIndexation {
        if (phase != PhaseIndexation.CONSTRUCTION) return ProgressionDetailIndexation.MASQUEE
        val compteurs = compteursConstructionDe(riches)
        return if (compteurs.total > 0) {
            ProgressionDetailIndexation(
                indeterminee = false,
                fraction = (compteurs.faits.toFloat() / compteurs.total).coerceIn(0f, 1f),
                compteur =
                TexteIndexation.Ressource(
                    R.string.editor_indexation_compteur_n_sur_n,
                    listOf("${compteurs.faits}", "${compteurs.total}"),
                ),
                tempsRestantMs = null, // pas d'estimation fiable (ADR 0117 Q2)
                visible = true,
            )
        } else {
            ProgressionDetailIndexation(
                indeterminee = true,
                fraction = 0f,
                compteur = null,
                tempsRestantMs = null,
                visible = true,
            )
        }
    }

    /**
     * Résumé des reprises pendant la construction (maquette §2 : « 96
     * reprises, 22 reconstruites ») — SOMME sur les moteurs en
     * construction (v0.180.0, ADR 0146). Libellé v0.178.1 : chaque
     * décompte précède son nombre — un substituant suivi d'un mot
     * déclenche l'heuristique lint PluralsCandidate (correctif CI de
     * v0.178.0). v0.178.2 : les décomptes passent en [Int] — la
     * ressource dit `%1$d`/`%2$d` et des `String` interpolées levaient
     * `IllegalFormatConversionException` (rapport 032311ee).
     */
    private fun resumeReprisesDe(phase: PhaseIndexation, riches: List<EtatIndexation>): TexteIndexation? {
        if (phase != PhaseIndexation.CONSTRUCTION) return null
        val enConstruction = riches.filter { it.phase == PhaseIndexation.CONSTRUCTION && it.faits > 0 }
        val faits = enConstruction.sumOf(EtatIndexation::faits)
        val reprises = enConstruction.sumOf(EtatIndexation::reprises)
        return enConstruction.takeIf(List<EtatIndexation>::isNotEmpty)?.let {
            TexteIndexation.Ressource(
                R.string.editor_indexation_reprises,
                listOf(reprises, faits - reprises),
            )
        }
    }

    /** Liste de travail — FUSION des moteurs en construction (v0.180.0 :
     *  les TROIS langues publient leur liste, ADR 0146). `null` si vide. */
    private fun listeTravailDe(riches: List<EtatIndexation>): List<EntreeDetailIndexation>? {
        val entrees =
            riches
                .filter { it.phase == PhaseIndexation.CONSTRUCTION }
                .flatMap(EtatIndexation::listeTravail)
                .map {
                    EntreeDetailIndexation(
                        nom = it.nom,
                        etat = traduireEtatEntree(it.etat),
                    )
                }
        return entrees.takeIf { it.isNotEmpty() }
    }

    private fun traduireEtatEntree(etat: jo.codeide.lsp.host.EtatEntreeTravailIndexation): EtatEntreeDetail =
        when (etat) {
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.EN_ATTENTE -> EtatEntreeDetail.EN_ATTENTE
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.ACTIF -> EtatEntreeDetail.ACTIF
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.TERMINE -> EtatEntreeDetail.TERMINE
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.REPRIS -> EtatEntreeDetail.REPRIS
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.ECARTE -> EtatEntreeDetail.ECARTE
        }

    /**
     * Vue par moteur — UNE rangée par moteur PRÉSENT de l'agrégat
     * (ADR 0145 : la boucle ignore les moteurs absents, aucun Java
     * codé en dur).
     */
    private fun moteursDe(etats: EtatsMoteursIndexation): List<MoteurDetailIndexation>? = if (etats.moteurs.isEmpty()) {
        null
    } else {
        etats.moteurs.map { present ->
            MoteurDetailIndexation(
                nom = present.moteur.nomRes,
                couleurAccent = present.moteur.couleurAccentRes,
                etat = traduireEtatMoteur(present.phase),
                detail = detailMoteur(present.phase, present.etat),
            )
        }
    }

    private fun traduireEtatMoteur(phase: PhaseIndexation): EtatMoteurDetail = when (phase) {
        PhaseIndexation.INITIAL -> EtatMoteurDetail.INITIAL
        PhaseIndexation.CONSTRUCTION -> EtatMoteurDetail.CONSTRUCTION
        PhaseIndexation.PRET -> EtatMoteurDetail.PRET
        PhaseIndexation.PARTIEL -> EtatMoteurDetail.PARTIEL
        PhaseIndexation.ERREUR -> EtatMoteurDetail.ERREUR
    }

    /**
     * Détail d'UNE rangée moteur (v0.180.0, ADR 0146) : le compteur
     * riche n'est plus réservé à Java — TOUT état observable le publie
     * (D3 livrée) ; un moteur SANS état (pack ancien) garde le verbe
     * synthétique, jamais un compteur inventé.
     */
    private fun detailMoteur(phase: PhaseIndexation, etat: EtatIndexation?): TexteIndexation? = if (etat != null) {
        when (phase) {
            PhaseIndexation.CONSTRUCTION ->
                if (etat.total > 0) {
                    TexteIndexation.Ressource(
                        R.string.editor_indexation_compteur_n_sur_n,
                        listOf("${etat.faits}", "${etat.total}"),
                    )
                } else {
                    null
                }

            PhaseIndexation.PRET -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_pret)

            PhaseIndexation.PARTIEL -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_partiel)

            PhaseIndexation.ERREUR -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_erreur)

            PhaseIndexation.INITIAL -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_en_attente)
        }
    } else {
        // Pack ancien (aucun état observable) : verbes synthétiques —
        // la construction reste INDÉTERMINÉE (fenêtre de nourrissage
        // seule, docs/DEMANDES_CODEIDE_LSP.md D3 tolérance de version).
        when (phase) {
            PhaseIndexation.CONSTRUCTION ->
                TexteIndexation.Ressource(R.string.editor_indexation_moteur_en_construction)

            PhaseIndexation.PRET -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_pret)

            // Jamais produit par les fenêtres — défensif, libellé honnête.
            PhaseIndexation.PARTIEL -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_partiel)

            PhaseIndexation.ERREUR -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_erreur)

            PhaseIndexation.INITIAL -> TexteIndexation.Ressource(R.string.editor_indexation_moteur_en_attente)
        }
    }

    /** Entrées écartées au dernier passage (index PARTIEL — TOUT moteur
     *  observable, v0.180.0 : les écarts Kotlin/XML sont ENFIN visibles). */
    private fun entreesEcarteesDe(riches: List<EtatIndexation>): List<EntreeEcarteeDetail> = riches
        .filter { it.phase == PhaseIndexation.PARTIEL }
        .flatMap(EtatIndexation::avertissements)
        .map(::EntreeEcarteeDetail)

    /** Erreurs bloquantes (index ERREUR — TOUT moteur observable, v0.180.0). */
    private fun erreursBloquantesDe(riches: List<EtatIndexation>): List<EntreeEcarteeDetail> = riches
        .filter { it.phase == PhaseIndexation.ERREUR }
        .flatMap(EtatIndexation::erreurs)
        .map(::EntreeEcarteeDetail)

    /**
     * Histogramme « ce qui a coûté le plus de temps » (au repos —
     * maquette §4) : les 3 indexeurs les plus coûteux, TOUTES langues
     * confondues (breakdowns FUSIONNÉS, v0.180.0), largeur relative au
     * premier.
     */
    private fun coutsDe(riches: List<EtatIndexation>): List<CoutDetailIndexation> {
        val plusChers =
            riches
                .flatMap(EtatIndexation::breakdownIndexeurs)
                .sortedByDescending(BreakdownIndexeurIndexation::millisecondesCumulees)
                .take(NOMBRE_COUTS)
        val max = plusChers.firstOrNull()?.millisecondesCumulees ?: 0L
        return plusChers
            .takeIf { max > 0 }
            ?.map { b ->
                CoutDetailIndexation(
                    libelle = b.libelle,
                    millisecondes = b.millisecondesCumulees,
                    fraction = (b.millisecondesCumulees.toFloat() / max).coerceIn(0f, 1f),
                )
            }
            .orEmpty()
    }

    /**
     * Bilan du PLUS RÉCENT passage TOUTES langues confondues (v0.180.0 :
     * chaque moteur porte son `bilanDernierPassage` horodaté — l'écran
     * montre le dernier ACQUIS, pas toujours celui de Java).
     */
    private fun bilanDe(riches: List<EtatIndexation>): BilanDetailIndexation? = riches
        .mapNotNull(EtatIndexation::bilanDernierPassage)
        .maxByOrNull(BilanDernierPassageIndexation::horodatageMs)
        ?.let(::traduireBilan)

    private fun traduireBilan(b: BilanDernierPassageIndexation): BilanDetailIndexation = BilanDetailIndexation(
        dureeMs = b.dureeMs,
        artefactsTotal = b.artefactsTotal,
        artefactsRepris = b.artefactsRepris,
        artefactsReconstruits = b.artefactsReconstruits,
        fichiersParcourus = b.fichiersParcourus,
        fichiersReanalyses = b.fichiersReanalyses,
        indexeurPlusCouteux = b.indexeurPlusCouteux,
        horodatageMs = b.horodatageMs,
    )

    /**
     * Actions — v0.180.0 (ADR 0146) : « Annuler le passage » ENFIN émis
     * pendant la construction (point d'annulation coopératif livré côté
     * frère v1.8.0, DEMANDES D5) ; « Réessayer les échecs » au PARTIEL
     * dès que N'IMPORTE QUEL moteur observable a des écarts (DEMANDES D4
     * — la reprise ciblée existe maintenant).
     */
    private fun actionsDe(phase: PhaseIndexation, riches: List<EtatIndexation>): List<ActionDetailIndexation> =
        when (phase) {
            PhaseIndexation.CONSTRUCTION -> listOf(ActionDetailIndexation.ANNULER)

            PhaseIndexation.PARTIEL -> {
                if (riches.any { it.phase == PhaseIndexation.PARTIEL && it.avertissements.isNotEmpty() }) {
                    listOf(ActionDetailIndexation.REESSAYER_ECHECS, ActionDetailIndexation.REINDEXER)
                } else {
                    listOf(ActionDetailIndexation.REINDEXER)
                }
            }

            PhaseIndexation.ERREUR -> listOf(ActionDetailIndexation.REINDEXER)

            PhaseIndexation.PRET -> listOf(ActionDetailIndexation.REINDEXER)

            PhaseIndexation.INITIAL -> emptyList()
        }

    private fun noteImpactDe(phase: PhaseIndexation, riches: List<EtatIndexation>): NoteImpactIndexation? =
        when (phase) {
            PhaseIndexation.CONSTRUCTION -> {
                NoteImpactIndexation(
                    niveau = NiveauNoteImpact.INFO,
                    texte = TexteIndexation.Ressource(R.string.editor_indexation_note_construction),
                )
            }

            PhaseIndexation.PARTIEL -> {
                if (riches.any { it.phase == PhaseIndexation.PARTIEL && it.avertissements.isNotEmpty() }) {
                    NoteImpactIndexation(
                        niveau = NiveauNoteImpact.AVERTISSEMENT,
                        texte = TexteIndexation.Ressource(R.string.editor_indexation_note_partiel),
                    )
                } else {
                    null
                }
            }

            PhaseIndexation.ERREUR -> {
                NoteImpactIndexation(
                    niveau = NiveauNoteImpact.ERREUR,
                    texte = TexteIndexation.Ressource(R.string.editor_indexation_note_erreur),
                )
            }

            PhaseIndexation.PRET -> null

            PhaseIndexation.INITIAL -> null
        }

    /**
     * Pourcentage de progression (0-100) — SOMME des moteurs en
     * construction à total connu (v0.180.0, ADR 0146) ; `null` si
     * indéterminé (aucun compteur publié).
     */
    private fun pourcentageDe(riches: List<EtatIndexation>): Int? {
        val compteurs = compteursConstructionDe(riches)
        return if (compteurs.total > 0) {
            ((compteurs.faits.toFloat() / compteurs.total) * POURCENTAGE_MAX).toInt().coerceIn(0, POURCENTAGE_MAX)
        } else {
            null
        }
    }

    /** Plafond du pourcentage de progression (0-100). */
    private const val POURCENTAGE_MAX: Int = 100

    /** Nombre de barres de l'histogramme des coûts (maquette : 3). */
    private const val NOMBRE_COUTS: Int = 3
}
