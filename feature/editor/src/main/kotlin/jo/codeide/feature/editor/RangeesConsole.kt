package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync

/**
 * Filtre de canal de la console du tooling (v4, §3.3 ; v5 — aperçu ;
 * v0.46.0 — console flux brut, ADR 0078) : les chips Sync / Build de la
 * barre d'outils choisissent la console VISIBLE — SYNC montre les lignes
 * des étapes de synchronisation et leur conclusion, BUILD le flux
 * stdout/stderr complet de Gradle (ses propres lignes « > Task », le
 * verdict « BUILD SUCCESSFUL », les diagnostics de compilation) enrichi
 * des statuts de l'orchestrateur.
 *
 * Les deux états sont EXCLUSIFS et l'un des deux est TOUJOURS actif : la
 * chronologie brute est DEVENUE l'affichage (v0.46.0) — les diagnostics
 * vivent dans le flux ET dans l'onglet Problèmes, le journal applicatif
 * dans l'onglet Journal.
 *
 * v0.39.1 (correctif n°3) : la visibilité passe de `internal` à `public`
 * — l'état du filtre est désormais piloté par le [EditorViewModel] (un
 * build qui démarre bascule vers BUILD automatiquement) et exposé dans
 * [EtatEditor.filtreConsole] pour que la rotation et les actions
 * utilisateur le traversent. L'`internal` était un oubli de la v5 (le
 * filtre vivait dans le fragment, sans traversal d'état).
 */
enum class FiltreCanalConsole {
    /** Lignes des étapes de sync + conclusion (console du canal Sync). */
    SYNC,

    /** Flux brut stdout/stderr de Gradle (console du canal Build). */
    BUILD,
}

/**
 * Étape AFFICHÉE dans la console de sync (v5 — le plan de l'APERÇU, sept
 * lignes ; v0.46.0 : plus un arbre à rangées — chaque étape est une LIGNE
 * du flux, « Libellé… » à son annonce puis « Libellé ✓ durée » à sa
 * conclusion) : le déroulé des PHASES (protocole v4, huit) reste réel,
 * seules deux d'entre elles partagent ici une ligne — MODELE_IDE et
 * DEPENDANCES se produisent pendant la MÊME résolution (les
 * téléchargements alimentent le modèle), et l'ancienne ligne DEPENDANCES
 * restait « ○ à vie » sur une sync sans téléchargement : la ligne fusionnée
 * « Dépendances et modèle IDE » conclut honnêtement dans les deux cas.
 */
internal enum class EtapeConsoleSync(
    /** Phases du déroulé réel rendues par cette ligne. */
    val phases: List<EtapeSync>,
) {
    OUTILS(listOf(EtapeSync.OUTILS)),
    DISTRIBUTION(listOf(EtapeSync.DISTRIBUTION)),
    DAEMON(listOf(EtapeSync.DAEMON)),
    CONFIGURATION(listOf(EtapeSync.CONFIGURATION)),
    MODELE_TACHES(listOf(EtapeSync.MODELE_TACHES)),
    DEPENDANCES_MODELE(listOf(EtapeSync.MODELE_IDE, EtapeSync.DEPENDANCES)),
    CLASSPATHS(listOf(EtapeSync.CLASSPATHS)),
    ;

    companion object {
        /** Ligne d'affichage d'une phase du déroulé réel. */
        fun dePhase(phase: EtapeSync): EtapeConsoleSync = entries.first { phase in it.phases }
    }
}
