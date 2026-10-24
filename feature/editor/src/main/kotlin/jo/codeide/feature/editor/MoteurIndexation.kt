package jo.codeide.feature.editor

import androidx.annotation.StringRes
import jo.codeide.lsp.host.EtatIndexation
import jo.codeide.lsp.host.PhaseIndexation

/**
 * Moteur d'indexation — UN par langue LSP de l'espace (v0.178.0,
 * ADR 0145 — généralisation de l'écran d'indexation au-delà du seul
 * Java, ADR 0117 lot B3 « reporté » enfin levé).
 *
 * GÉNÉRIQUE PAR CONSTRUCTION : ajouter une langue = UNE entrée
 * d'énumération (nom + accent de langage ADR 0059) + UNE source
 * d'état dans `EditorViewModel.etatsMoteursIndexation`. Le
 * présentateur itère la LISTE, jamais un moteur nommé.
 */
internal enum class MoteurIndexation(
    /** Libellé localisé du moteur (ligne de la vue par moteur). */
    @field:StringRes val nomRes: Int,
    /** Accent de langage du moteur (point de couleur, ADR 0059). */
    val couleurAccentRes: Int,
) {
    /** Moteur Java — état RICHE transporté par `SessionLsp.etatIndexation`. */
    JAVA(R.string.editor_indexation_moteur_java, jo.codeide.core.ui.R.color.codeide_langage_java),

    /** Moteur Kotlin — état RICHE `SessionLspKotlin.etatIndexation` depuis
     *  le frère v1.8.0 (D3 livrée, ADR 0146) ; la fenêtre de nourrissage
     *  hôte ne survit que comme témoin d'échec / repli pack ancien. */
    KOTLIN(R.string.editor_indexation_moteur_kotlin, jo.codeide.core.ui.R.color.codeide_langage_kotlin),

    /** Moteur XML — état RICHE `SessionLspXml.etatIndexation` depuis le
     *  frère v1.8.0 (même motif que Kotlin). */
    XML(R.string.editor_indexation_moteur_xml, jo.codeide.core.ui.R.color.codeide_langage_xml),
}

/**
 * État d'UN moteur dans l'agrégat de l'écran d'indexation.
 *
 * @param moteur identité du moteur (nom, accent).
 * @param phase phase COURANTE du moteur (INITIAL/CONSTRUCTION/PRET/
 *        PARTIEL/ERREUR) — la pire phase des moteurs décide l'état de
 *        la pastille. V0.180.0 (ADR 0146) : pour Kotlin/XML c'est la
 *        PIRE du flux réel et de la fenêtre de nourrissage hôte.
 * @param etat état RICHE du moteur (v1.8.0 : les TROIS langues — liste
 *        de travail, avertissements, erreurs, breakdown, bilan) ;
 *        `null` pour un moteur sans état observable (pack ancien) —
 *        le présentateur dégrade honnêtement.
 */
internal data class MoteurIndexationPresent(
    val moteur: MoteurIndexation,
    val phase: PhaseIndexation,
    val etat: EtatIndexation?,
)

/**
 * Agrégat multi-moteurs consommé par l'écran d'indexation (pastille +
 * panneau de détail). Liste ORDONNÉE des moteurs PRÉSENTS (une session
 * fermée retire sa rangée) ; VIDE = aucun moteur = pas de pastille
 * (miroir de l'historique `EtatIndexation.INITIAL`).
 */
internal data class EtatsMoteursIndexation(val moteurs: List<MoteurIndexationPresent> = emptyList()) {
    /** La pire phase des moteurs présents (INITIAL si aucun). */
    val pirePhase: PhaseIndexation
        get() = moteurs.maxByOrNull { ordrePhase(it.phase) }?.phase ?: PhaseIndexation.INITIAL

    /** L'état RICHE du moteur Java, `null` si absent. */
    val etatJava: EtatIndexation?
        get() = moteurs.firstOrNull { it.moteur == MoteurIndexation.JAVA }?.etat

    internal companion object {
        /** Sévérités croissantes — la plus haute l'emporte. */
        private const val ORDRE_PHASE_INITIAL: Int = 0
        private const val ORDRE_PHASE_PRET: Int = 1
        private const val ORDRE_PHASE_CONSTRUCTION: Int = 2
        private const val ORDRE_PHASE_PARTIEL: Int = 3
        private const val ORDRE_PHASE_ERREUR: Int = 4

        private fun ordrePhase(phase: PhaseIndexation): Int = when (phase) {
            PhaseIndexation.INITIAL -> ORDRE_PHASE_INITIAL
            PhaseIndexation.PRET -> ORDRE_PHASE_PRET
            PhaseIndexation.CONSTRUCTION -> ORDRE_PHASE_CONSTRUCTION
            PhaseIndexation.PARTIEL -> ORDRE_PHASE_PARTIEL
            PhaseIndexation.ERREUR -> ORDRE_PHASE_ERREUR
        }

        /**
         * La pire de DEUX phases (v0.180.0, ADR 0146) — fusion du
         * flux RÉEL `etatIndexation` et de la fenêtre de nourrissage
         * hôte pour Kotlin/XML : la fenêtre ne peut que SURÉLEVER
         * (ERREUR d'une exception de nourrissage côté CodeIDE, repli
         * CONSTRUCTION/PRET d'un pack frère ancien), jamais masquer
         * l'état réel.
         */
        internal fun pirePhaseDe(a: PhaseIndexation, b: PhaseIndexation): PhaseIndexation =
            if (ordrePhase(a) >= ordrePhase(b)) a else b
    }
}
