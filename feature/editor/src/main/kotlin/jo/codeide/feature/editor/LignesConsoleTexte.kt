package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtapeSyncTooling
import jo.codeide.core.domain.StatutBuild
import java.util.Locale

/**
 * Constructeurs des lignes de la console FLUX BRUT UNIQUE (v0.46.0 — refonte
 * totale de la console hybride v0.42.0, voir ADR 0078) : des événements
 * structurés du tooling vers des [EvenementConsoleTexte.Ligne] — fonctions
 * PURES, la même discipline que le présentateur (aucune chaîne n'y est
 * résolue, les libellés voyagent en [TexteTooling] et se résolvent au
 * rendu).
 *
 * La console ne montre plus QUE du texte, comme la fenêtre Build d'Android
 * Studio : le flux stdout/stderr de Gradle (ses propres lignes
 * « > Task :app:xxx », « BUILD SUCCESSFUL in 6s », les diagnostics de
 * compilation) auquel s'ajoutent les lignes de statut de l'orchestrateur
 * (connexion au daemon, configuration), une ligne par artefact téléchargé
 * et les lignes d'étapes de la vue Sync. Les tâches ne produisent PLUS de
 * lignes dédiées : Gradle écrit les siennes sur le flux — les afficher
 * deux fois (rangée structurée + flux brut) était précisément le problème
 * « les outputs sont affichés dans deux endroits ».
 */
internal object LignesConsoleTexte {
    /**
     * Ligne de départ d'une étape de sync : « Libellé… » — la ligne de fin
     * la conclut plus bas dans le flux (chronologie honnête, comme la
     * console de sync d'Android Studio : les lignes s'accumulent, elles ne
     * se réécrivent pas).
     */
    fun etapeSyncDemarree(etape: EtapeSync): EvenementConsoleTexte.Ligne =
        EvenementConsoleTexte.Ligne(
            canal = CanalTooling.SYNC,
            libelle =
                TexteTooling.Compose(
                    listOf(
                        libelleEtape(etape),
                        TexteTooling.Ressource(R.string.editor_console_etape_suffixe_demarree),
                    ),
                ),
            style = StyleLigne.ETAPE,
        )

    /**
     * Ligne de FIN d'une étape de sync : « Libellé ✓ 34,1s » — plus le
     * volume reçu quand la phase a téléchargé (« · 129,4 Mo reçus ») et le
     * compte d'artefacts quand il existe.
     */
    fun etapeSyncTerminee(affichee: EtapeSyncAffichee): EvenementConsoleTexte.Ligne {
        val duree = DureesLisibles.formater(affichee.dureeMs)
        val suffixe =
            when {
                affichee.octetsRecus > 0 -> {
                    TexteTooling.Ressource(
                        R.string.editor_console_etape_suffixe_terminee_octets,
                        listOf(duree, OctetsLisibles.formater(affichee.octetsRecus)),
                    )
                }

                !affichee.compteur.isNullOrZero() -> {
                    TexteTooling.Ressource(
                        R.string.editor_console_etape_suffixe_terminee_compteur,
                        listOf(duree, affichee.compteur.toString()),
                    )
                }

                else -> {
                    TexteTooling.Ressource(
                        R.string.editor_console_etape_suffixe_terminee,
                        listOf(duree),
                    )
                }
            }
        return EvenementConsoleTexte.Ligne(
            canal = CanalTooling.SYNC,
            libelle = TexteTooling.Compose(listOf(libelleEtape(affichee.etape), suffixe)),
            style = StyleLigne.ETAPE,
        )
    }

    /**
     * Ligne d'un artefact téléchargé pendant le build : « Téléchargé :
     * kotlin-stdlib-1.9.24.jar · 34,2 Mo reçus au total » — UNE ligne par
     * artefact TERMINÉ (le compteur avance), jamais par tick d'octets : le
     * détail d'octets EN VOL vit dans l'en-tête du panneau, la console est
     * l'HISTORIQUE.
     */
    fun telechargementBuild(
        element: String,
        octetsCumules: Long,
    ): EvenementConsoleTexte.Ligne =
        EvenementConsoleTexte.Ligne(
            canal = CanalTooling.BUILD,
            libelle =
                TexteTooling.Ressource(
                    id = R.string.editor_console_telecharge,
                    args = listOf(element, OctetsLisibles.formater(octetsCumules)),
                ),
            style = StyleLigne.TELECHARGEMENT,
        )

    /**
     * Ligne de conclusion de la synchronisation : « Synchronisation
     * terminée en 8,4s — les tâches sont disponibles. » (ou « Projet à
     * jour, rien à télécharger… » quand AUCUN octet n'a été reçu). Le
     * récapitulatif classpath n'y voyage PAS : les stats sont publiées
     * APRÈS le résultat (préparation LSP) — une ligne émise au résultat
     * ne pourrait lire que des stats PÉRIMÉES.
     */
    fun conclusionSync(
        aJour: Boolean,
        dureeMs: Long?,
    ): List<EvenementConsoleTexte.Ligne> {
        val duree = dureeMs?.let(DureesLisibles::formater)
        val conclusion =
            when {
                aJour && duree != null -> {
                    TexteTooling.Ressource(
                        R.string.editor_console_synthese_sync_a_jour_duree,
                        listOf(duree),
                    )
                }

                aJour -> {
                    TexteTooling.Ressource(R.string.editor_console_synthese_sync_a_jour)
                }

                duree != null -> {
                    TexteTooling.Ressource(
                        R.string.editor_console_synthese_sync_terminee_duree,
                        listOf(duree),
                    )
                }

                else -> {
                    TexteTooling.Ressource(R.string.editor_console_synthese_sync_terminee)
                }
            }
        return listOf(
            EvenementConsoleTexte.Ligne(
                canal = CanalTooling.SYNC,
                libelle = conclusion,
                style = StyleLigne.SYNTHESE,
            ),
        )
    }

    /**
     * Ligne de conclusion d'un build ANNULÉ : Gradle n'imprime rien de tel
     * sur son flux après une annulation en pleine configuration — la
     * console le dit elle-même. Les builds réussis ou échoués s'appuient
     * sur les lignes de Gradle (« BUILD SUCCESSFUL in 6s », « N actionable
     * tasks: … », rapport d'échec) : aucune ligne dupliquée, parité
     * Android Studio.
     */
    fun buildAnnule(): EvenementConsoleTexte.Ligne =
        EvenementConsoleTexte.Ligne(
            canal = CanalTooling.BUILD,
            libelle = TexteTooling.Ressource(R.string.editor_console_synthese_annule),
            style = StyleLigne.SYNTHESE,
        )

    /**
     * Le statut de build nécessite-t-il une ligne de conclusion propre ?
     * SEULE l'annulation (cf. [buildAnnule]).
     */
    fun statutConcluParLigne(statut: StatutBuild?): Boolean = statut == StatutBuild.ANNULE

    /** Libellé localisable d'une phase de sync (le plan AFFICHÉ v5). */
    private fun libelleEtape(etape: EtapeSync): TexteTooling =
        TexteTooling.Ressource(LibellesEtapesSync.libelle(EtapeConsoleSync.dePhase(etape)))

    /** Compteur nul ou absent (le détail n'existe pas). */
    private fun Int?.isNullOrZero(): Boolean = this == null || this == 0
}

/**
 * Style VISUEL d'une ligne de la console texte (v0.46.0) : la couleur se
 * résout au RENDU (le fragment), l'événement reste PUR — même discipline
 * que [TexteTooling] pour les libellés.
 */
internal enum class StyleLigne {
    /** Ligne stdout brute de Gradle. */
    SORTIE,

    /** Ligne stderr brute de Gradle (diagnostics de compilation). */
    ERREUR,

    /** Avertissement CONNU et bénin (C5 — le diagnostic natif du daemon). */
    APAISEE,

    /** Étape de synchronisation (vue Sync) : libellé, ✓ et durée. */
    ETAPE,

    /** Artefact téléchargé (vue Build). */
    TELECHARGEMENT,

    /** Conclusion de synchronisation (vue Sync). */
    SYNTHESE,
}

/**
 * Durée lisible pour l'affichage (s au-delà d'une seconde, ms en dessous) —
 * partagée par l'en-tête du panneau (chrono du build) et les lignes de la
 * console (v0.46.0 : déménagée de `PanneauxToolingAdapters` avec la console
 * texte unique — l'adaptateur de rangées a disparu).
 */
internal object DureesLisibles {
    /** Formate [dureeMs] : « 4,2s » au-delà de la seconde, « 350ms » en dessous. */
    fun formater(dureeMs: Long): String =
        if (dureeMs >= SEUIL_SECONDE_MS) {
            String.format(Locale.ROOT, "%.1fs", dureeMs / SECONDE_MS)
        } else {
            String.format(Locale.ROOT, "%dms", dureeMs)
        }

    /** Une durée vaut-elle l'affichage (les tâches éclair restent nues,
     *  comme la vue Build d'Android Studio) ? */
    fun digne(dureeMs: Long?): Boolean = dureeMs != null && dureeMs >= SEUIL_SECONDE_MS

    private const val SEUIL_SECONDE_MS = 1_000L

    private const val SECONDE_MS = 1_000.0
}

/**
 * Taille lisible pour l'affichage des téléchargements (v4, §3.3) — octets
 * reçus/total de la distribution et des dépendances : « 1,2 Mo », « 340 Ko ».
 * v0.46.0 : déménagée de `PanneauxToolingAdapters` (cf. [DureesLisibles]).
 */
internal object OctetsLisibles {
    /** Formate [octets] : Mo au-dessus du méga, Ko au-dessus du kilo. */
    fun formater(octets: Long): String =
        when {
            octets >= OCTETS_PAR_MO -> {
                String.format(Locale.ROOT, "%.1f Mo", octets / OCTETS_PAR_MO.toDouble())
            }

            octets >= OCTETS_PAR_KO -> {
                String.format(Locale.ROOT, "%d Ko", octets / OCTETS_PAR_KO)
            }

            else -> {
                String.format(Locale.ROOT, "%d o", octets)
            }
        }

    private const val OCTETS_PAR_KO = 1_024L

    private const val OCTETS_PAR_MO = 1_024L * 1_024L
}

/**
 * Traduction d'une étape de sync du tooling vers l'état affiché (v0.46.0) :
 * la fenêtre d'étapes de [EtatGradle] ne porte plus de LigneConsole (les
 * rangées structurées ont disparu avec la console flux brut) — l'état
 * reste nécessaire à l'EN-TÊTE du panneau (compteur « étape n/N », détail
 * et progression déterminée de l'étape courante).
 */
internal fun EtapeSyncTooling.versEtatAffiche(): EtapeSyncAffichee =
    EtapeSyncAffichee(
        etape = etape,
        terminee = terminee,
        dureeMs = dureeMs,
        octetsRecus = octetsRecus,
        octetsTotal = octetsTotal,
        element = element,
        compteur = compteur,
        total = total,
    )
