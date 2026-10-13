package jo.codeide.feature.editor

import jo.codeide.lsp.host.BilanDernierPassageIndexation
import jo.codeide.lsp.host.EtatIndexation
import jo.codeide.lsp.host.PhaseDetailleeIndexation
import jo.codeide.lsp.host.PhaseIndexation

/**
 * Modèle d'affichage de l'indexation (Phase B prompt 2, ADR 0117 §4.4 —
 * lot B4). Produit par [PresentationIndexation] à partir de
 * [EtatIndexation] (lsp:host). Le présentateur est PUR — il ne touche
 * ni `Context` ni vue ; la résolution des libellés localisés a lieu au
 * dernier moment par l'appelant (patron [PresentationTooling]).
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
 */
internal data class PanneauDetailIndexation(
    /** En-tête du panneau (icône + titre + sous-titre). */
    val entete: EnteteDetailIndexation,
    /** Barre de progression (déterminée ou indéterminée). */
    val progression: ProgressionDetailIndexation,
    /** Liste de travail (entrées en cours), triée. `null` si non applicable. */
    val listeTravail: List<EntreeDetailIndexation>?,
    /** Vue par moteur (Java, Kotlin, etc.). `null` si non applicable. */
    val moteurs: List<MoteurDetailIndexation>?,
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
)

/**
 * Texte localisable produit par le présentateur (patron [TexteTooling]).
 */
internal sealed interface TexteIndexation {
    /** Libellé d'une ressource de chaîne (identifiant + arguments). */
    data class Ressource(
        val id: Int,
        val args: List<String> = emptyList(),
    ) : TexteIndexation

    /** Libellé brut (message technique déjà formulé). */
    data class Brut(
        val texte: String,
    ) : TexteIndexation
}

/**
 * Barre de progression du panneau de détail.
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
)

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
 * Vue par moteur dans le panneau de détail.
 */
internal data class MoteurDetailIndexation(
    /** Nom du moteur (Java, Kotlin, etc.). */
    val nom: Int,
    /** Couleur d'accent du moteur (token de couleur). */
    val couleurAccent: Int,
    /** État du moteur. */
    val etat: EtatMoteurDetail,
    /** Détail localisé (ex. « 118/191 », « Prêt », « Partiel »). */
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
}

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
 * Action proposée à l'utilisateur dans le panneau de détail.
 */
internal enum class ActionDetailIndexation {
    /** Réindexer tout. */
    REINDEXER,

    /** Réessayer les entrées échouées seulement. */
    REESSAYER_ECHECS,

    /** Annuler le passage en cours. */
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
 * §4.4 — lot B4). Transforme [EtatIndexation] (lsp:host) en
 * [ModeleAffichageIndexation] (modèle d'affichage immutable, sans
 * aucune référence Android). Testé unitairement — tous les états, y
 * compris partiel avec avertissements, erreur, passage vide, total
 * inconnu, estimation non fiable.
 *
 * Patron : [PresentationTooling] — le présentateur ne touche ni
 * `Context` ni vue ; la résolution des libellés localisés a lieu au
 * dernier moment par l'appelant.
 *
 * Exemption detekt ciblée (règle 16) : `TooManyFunctions` — 15
 * fonctions, seuil à 11. Chaque fonction traduit un CHAMP distinct de
 * `EtatIndexation` ; les regrouper masquerait la structure du
 * présentateur. Même précédent que `PresentationTooling` (v4, §3.2).
 */
@Suppress("TooManyFunctions")
internal object PresentationIndexation {
    /**
     * Produit le modèle d'affichage à partir de l'état d'indexation.
     *
     * @param etat état d'indexation (Java, transmis par l'activité).
     * @return modèle d'affichage (pastille + panneau de détail).
     */
    fun afficher(etat: EtatIndexation): ModeleAffichageIndexation =
        ModeleAffichageIndexation(
            pastille = pastilleDe(etat),
            panneau = panneauDe(etat),
        )

    /** Pastille de toolbar selon l'état. */
    private fun pastilleDe(etat: EtatIndexation): PastilleIndexation =
        when (etat.phase) {
            PhaseIndexation.INITIAL -> {
                PastilleIndexation(
                    etat = EtatPastilleIndexation.INITIAL,
                    pourcentage = null,
                    masqueeEtroit = true,
                )
            }

            PhaseIndexation.CONSTRUCTION -> {
                val pct = pourcentageDe(etat.faits, etat.total)
                PastilleIndexation(
                    etat = EtatPastilleIndexation.CONSTRUCTION,
                    pourcentage = pct,
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

    /** Panneau de détail selon l'état. */
    @Suppress("LongMethod") // traduction exhaustive de tous les états
    private fun panneauDe(etat: EtatIndexation): PanneauDetailIndexation =
        PanneauDetailIndexation(
            entete = enteteDe(etat),
            progression = progressionDe(etat),
            listeTravail = listeTravailDe(etat),
            moteurs = moteursDe(etat),
            bilan = bilanDe(etat),
            actions = actionsDe(etat),
            noteImpact = noteImpactDe(etat),
        )

    private fun enteteDe(etat: EtatIndexation): EnteteDetailIndexation =
        when (etat.phase) {
            PhaseIndexation.INITIAL -> {
                EnteteDetailIndexation(
                    etat = EtatPastilleIndexation.INITIAL,
                    titre = R.string.editor_indexation_initial_titre,
                    sousTitre = TexteIndexation.Ressource(R.string.editor_indexation_initial_soustitre),
                )
            }

            PhaseIndexation.CONSTRUCTION -> {
                EnteteDetailIndexation(
                    etat = EtatPastilleIndexation.CONSTRUCTION,
                    titre = R.string.editor_indexation_construction_titre,
                    sousTitre =
                        if (etat.total > 0) {
                            TexteIndexation.Ressource(
                                R.string.editor_indexation_construction_soustitre_n,
                                listOf("${etat.faits}", "${etat.total}"),
                            )
                        } else {
                            TexteIndexation.Ressource(R.string.editor_indexation_construction_soustitre)
                        },
                )
            }

            PhaseIndexation.PRET -> {
                EnteteDetailIndexation(
                    etat = EtatPastilleIndexation.PRET,
                    titre = R.string.editor_indexation_pret_titre,
                    sousTitre = TexteIndexation.Ressource(R.string.editor_indexation_pret_soustitre),
                )
            }

            PhaseIndexation.PARTIEL -> {
                EnteteDetailIndexation(
                    etat = EtatPastilleIndexation.PARTIEL,
                    titre = R.string.editor_indexation_partiel_titre,
                    sousTitre =
                        TexteIndexation.Ressource(
                            R.string.editor_indexation_partiel_soustitre,
                            listOf("${etat.faits}", "${etat.total}"),
                        ),
                )
            }

            PhaseIndexation.ERREUR -> {
                EnteteDetailIndexation(
                    etat = EtatPastilleIndexation.ERREUR,
                    titre = R.string.editor_indexation_erreur_titre,
                    sousTitre = TexteIndexation.Ressource(R.string.editor_indexation_erreur_soustitre),
                )
            }
        }

    private fun progressionDe(etat: EtatIndexation): ProgressionDetailIndexation =
        when (etat.phase) {
            PhaseIndexation.CONSTRUCTION -> {
                val indeterminee = etat.total <= 0
                val fraction = if (etat.total > 0) etat.faits.toFloat() / etat.total else 0f
                val compteur =
                    if (etat.total > 0) {
                        TexteIndexation.Ressource(
                            R.string.editor_indexation_compteur_n_sur_n,
                            listOf("${etat.faits}", "${etat.total}"),
                        )
                    } else {
                        null
                    }
                ProgressionDetailIndexation(
                    indeterminee = indeterminee,
                    fraction = fraction,
                    compteur = compteur,
                    tempsRestantMs = null, // pas d'estimation fiable dans B4
                )
            }

            else -> {
                ProgressionDetailIndexation(
                    indeterminee = false,
                    fraction = 1f,
                    compteur = null,
                    tempsRestantMs = null,
                )
            }
        }

    private fun listeTravailDe(etat: EtatIndexation): List<EntreeDetailIndexation>? =
        if (etat.phase == PhaseIndexation.CONSTRUCTION && etat.listeTravail.isNotEmpty()) {
            etat.listeTravail.map {
                EntreeDetailIndexation(
                    nom = it.nom,
                    etat = traduireEtatEntree(it.etat),
                )
            }
        } else {
            null
        }

    private fun traduireEtatEntree(etat: jo.codeide.lsp.host.EtatEntreeTravailIndexation): EtatEntreeDetail =
        when (etat) {
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.EN_ATTENTE -> EtatEntreeDetail.EN_ATTENTE
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.ACTIF -> EtatEntreeDetail.ACTIF
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.TERMINE -> EtatEntreeDetail.TERMINE
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.REPRIS -> EtatEntreeDetail.REPRIS
            jo.codeide.lsp.host.EtatEntreeTravailIndexation.ECARTE -> EtatEntreeDetail.ECARTE
        }

    /** Vue par moteur — pour l'instant seul Java est géré (B3 note :
     *  SessionLspKotlin.etatIndexation est reporté). */
    private fun moteursDe(etat: EtatIndexation): List<MoteurDetailIndexation>? =
        if (etat.phase != PhaseIndexation.INITIAL) {
            listOf(
                MoteurDetailIndexation(
                    nom = R.string.editor_indexation_moteur_java,
                    couleurAccent = jo.codeide.core.ui.R.color.codeide_langage_java,
                    etat = traduireEtatMoteur(etat.phase),
                    detail = detailMoteurJava(etat),
                ),
            )
        } else {
            null
        }

    private fun traduireEtatMoteur(phase: PhaseIndexation): EtatMoteurDetail =
        when (phase) {
            PhaseIndexation.INITIAL -> EtatMoteurDetail.CONSTRUCTION
            PhaseIndexation.CONSTRUCTION -> EtatMoteurDetail.CONSTRUCTION
            PhaseIndexation.PRET -> EtatMoteurDetail.PRET
            PhaseIndexation.PARTIEL -> EtatMoteurDetail.PARTIEL
            PhaseIndexation.ERREUR -> EtatMoteurDetail.ERREUR
        }

    private fun detailMoteurJava(etat: EtatIndexation): TexteIndexation? =
        when (etat.phase) {
            PhaseIndexation.CONSTRUCTION -> {
                if (etat.total > 0) {
                    TexteIndexation.Ressource(
                        R.string.editor_indexation_compteur_n_sur_n,
                        listOf("${etat.faits}", "${etat.total}"),
                    )
                } else {
                    null
                }
            }

            PhaseIndexation.PRET -> {
                TexteIndexation.Ressource(R.string.editor_indexation_moteur_pret)
            }

            PhaseIndexation.PARTIEL -> {
                TexteIndexation.Ressource(R.string.editor_indexation_moteur_partiel)
            }

            PhaseIndexation.ERREUR -> {
                TexteIndexation.Ressource(R.string.editor_indexation_moteur_erreur)
            }

            PhaseIndexation.INITIAL -> {
                null
            }
        }

    private fun bilanDe(etat: EtatIndexation): BilanDetailIndexation? = etat.bilanDernierPassage?.let(::traduireBilan)

    private fun traduireBilan(b: BilanDernierPassageIndexation): BilanDetailIndexation =
        BilanDetailIndexation(
            dureeMs = b.dureeMs,
            artefactsTotal = b.artefactsTotal,
            artefactsRepris = b.artefactsRepris,
            artefactsReconstruits = b.artefactsReconstruits,
            fichiersParcourus = b.fichiersParcourus,
            fichiersReanalyses = b.fichiersReanalyses,
            indexeurPlusCouteux = b.indexeurPlusCouteux,
            horodatageMs = b.horodatageMs,
        )

    private fun actionsDe(etat: EtatIndexation): List<ActionDetailIndexation> =
        when (etat.phase) {
            PhaseIndexation.CONSTRUCTION -> {
                listOf(ActionDetailIndexation.ANNULER)
            }

            PhaseIndexation.PARTIEL -> {
                if (etat.avertissements.isNotEmpty()) {
                    listOf(ActionDetailIndexation.REESSAYER_ECHECS, ActionDetailIndexation.REINDEXER)
                } else {
                    listOf(ActionDetailIndexation.REINDEXER)
                }
            }

            PhaseIndexation.ERREUR -> {
                listOf(ActionDetailIndexation.REINDEXER)
            }

            PhaseIndexation.PRET -> {
                listOf(ActionDetailIndexation.REINDEXER)
            }

            PhaseIndexation.INITIAL -> {
                emptyList()
            }
        }

    private fun noteImpactDe(etat: EtatIndexation): NoteImpactIndexation? =
        when (etat.phase) {
            PhaseIndexation.CONSTRUCTION -> {
                NoteImpactIndexation(
                    niveau = NiveauNoteImpact.INFO,
                    texte = TexteIndexation.Ressource(R.string.editor_indexation_note_construction),
                )
            }

            PhaseIndexation.PARTIEL -> {
                if (etat.avertissements.isNotEmpty()) {
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

            PhaseIndexation.PRET -> {
                null
            }

            PhaseIndexation.INITIAL -> {
                null
            }
        }

    /** Calcule le pourcentage de progression (0-100), `null` si indéterminé. */
    private fun pourcentageDe(
        faits: Int,
        total: Int,
    ): Int? =
        if (total > 0) {
            ((faits.toFloat() / total) * POURCENTAGE_MAX).toInt().coerceIn(0, POURCENTAGE_MAX)
        } else {
            null
        }

    /** Plafond du pourcentage de progression (0-100). */
    private const val POURCENTAGE_MAX: Int = 100
}
