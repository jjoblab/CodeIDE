package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.StatutBuild

/**
 * Filtre de canal de la console du tooling (v4, §3.3) : les chips Sync /
 * Build de la barre d'outils filtrent la vue — TOUS (aucune chip active)
 * garde la chronologie brute, SYNC montre l'ARBRE des étapes, BUILD les
 * tâches et leur synthèse.
 */
internal enum class FiltreCanalConsole {
    /** Chronologie brute : chaque ligne se lit dans son ordre d'arrivée. */
    TOUS,

    /** Arbre des étapes de sync (§3.3) + sorties brutes de la sync. */
    SYNC,

    /** Tâches du build (mise à jour en place) + synthèse finale. */
    BUILD,
}

/**
 * Une rangée de la console du tooling (v4, §3.3) : l'arbre des étapes de
 * sync (✓ / spinner / ○ avec durée), les détails de téléchargement sous
 * l'étape active, les lignes existantes (sortie brute, tâche, étape
 * annoncée) et la synthèse de fin de build — UN modèle aplati pour un
 * RecyclerView, l'indentation visuelle porte la hiérarchie.
 */
internal sealed interface RangeeConsole {
    /** Identité stable de la rangée (DiffUtil — mise à jour en place). */
    val idCle: String

    /**
     * Étape du DÉROULÉ de sync (§3.3) : la phase du plan v4, avec son
     * état annoncé ([annoncee] — `null` = pas encore atteinte : ○ en
     * attente, jamais de durée devinée).
     */
    data class EtapeArbre(
        val phase: EtapeSync,
        val annoncee: EtapeSyncAffichee?,
    ) : RangeeConsole {
        override val idCle: String
            get() = "etape-${phase.name}"
    }

    /**
     * Détail de téléchargement SOUS l'étape active (§3.3) : barre de
     * progression (octets reçus/total), compteur n/N, artefact courant.
     */
    data class DetailTelechargement(
        val phase: EtapeSync,
        val octetsRecus: Long,
        val octetsTotal: Long?,
        val compteur: Int?,
        val total: Int?,
        val element: String?,
    ) : RangeeConsole {
        override val idCle: String
            get() = "detail-${phase.name}"
    }

    /** Ligne existante (sortie brute, tâche, étape annoncée) — chronologie. */
    data class Ligne(
        val ligne: LigneConsole,
    ) : RangeeConsole {
        override val idCle: String
            get() = "ligne-${ligne.id}"
    }

    /** Synthèse finale du build (§3.3) : verdict, durée, message. */
    data class SyntheseBuild(
        val statut: StatutBuild,
        val dureeMs: Long?,
        val message: String?,
    ) : RangeeConsole {
        override val idCle: String
            get() = "synthese-build"
    }
}

/**
 * Construit les rangées de la console selon le filtre (§3.3) : FONCTION
 * PURE — même discipline de test que le présentateur, aucune chaîne n'y
 * est construite (les libellés se résolvent au rendu).
 */
internal fun construireRangeesConsole(
    etat: EtatGradle,
    filtre: FiltreCanalConsole,
): List<RangeeConsole> =
    when (filtre) {
        FiltreCanalConsole.TOUS -> {
            etat.lignes.map { RangeeConsole.Ligne(it) }
        }

        FiltreCanalConsole.SYNC -> {
            arbreEtapesSync(etat) +
                etat.lignes
                    .filter { it.canal == CanalTooling.SYNC && it is LigneConsole.Sortie }
                    .map { RangeeConsole.Ligne(it) }
        }

        FiltreCanalConsole.BUILD -> {
            etat.lignes
                .filter { it.canal == CanalTooling.BUILD }
                .map { RangeeConsole.Ligne(it) } + syntheseBuild(etat)
        }
    }

/**
 * L'arbre des étapes (§3.3) : les 8 phases du plan v4 dans l'ordre, chacune
 * avec son état annoncé — ○ pour les non atteintes, jamais supprimées
 * (l'utilisateur voit le CHEMIN complet, comme la vue Build d'Android
 * Studio). Le détail de téléchargement s'insère sous l'étape ACTIVE.
 */
private fun arbreEtapesSync(etat: EtatGradle): List<RangeeConsole> {
    val annoncees = etat.etapesAffichees.associateBy { it.etape }
    val active = etat.etapeCourante?.takeIf { etat.synchronisationEnCours && !it.terminee }
    val rangees = mutableListOf<RangeeConsole>()
    for (phase in EtapeSync.entries) {
        rangees += RangeeConsole.EtapeArbre(phase, annoncees[phase])
        if (active?.etape == phase) {
            rangees +=
                RangeeConsole.DetailTelechargement(
                    phase = phase,
                    octetsRecus = active.octetsRecus,
                    octetsTotal = active.octetsTotal,
                    compteur = active.compteur,
                    total = active.total,
                    element = active.element,
                )
        }
    }
    return rangees
}

/** Synthèse de fin de build : absente en vol, présente une fois terminé. */
private fun syntheseBuild(etat: EtatGradle): List<RangeeConsole.SyntheseBuild> =
    when (etat.statutBuild) {
        StatutBuild.REUSSI,
        StatutBuild.ECHOUE,
        StatutBuild.ANNULE,
        -> {
            listOf(
                RangeeConsole.SyntheseBuild(
                    statut = etat.statutBuild,
                    dureeMs = etat.dureeBuildMs,
                    message = etat.messageEchecBuild,
                ),
            )
        }

        null,
        StatutBuild.EN_COURS,
        -> {
            emptyList()
        }
    }
