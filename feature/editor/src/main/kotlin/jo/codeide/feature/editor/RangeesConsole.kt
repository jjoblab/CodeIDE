package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.StatutBuild

/**
 * Filtre de canal de la console du tooling (v4, §3.3 ; v5 — aperçu) : les
 * chips Sync / Build de la barre d'outils filtrent la vue — SYNC montre
 * l'ARBRE des étapes et son pied de conclusion, BUILD les tâches et leur
 * synthèse.
 *
 * Les deux états sont EXCLUSIFS et l'un des deux est TOUJOURS actif : la
 * chronologie brute (v4 — sortie stdout/stderr en liste plate) n'est plus
 * un écran, comme l'aperçu ne la montre pas — les sorties brutes ne
 * mélangent plus l'arbre ni les tâches (les diagnostics vivent dans
 * l'onglet Problèmes, le journal applicatif dans l'onglet Journal).
 *
 * v0.39.1 (correctif n°3) : la visibilité passe de `internal` à `public`
 * — l'état du filtre est désormais piloté par le [EditorViewModel] (un
 * build qui démarre bascule vers BUILD automatiquement) et exposé dans
 * [EtatEditor.filtreConsole] pour que la rotation et les actions
 * utilisateur le traversent. L'`internal` était un oubli de la v5 (le
 * filtre vivait dans le fragment, sans traversal d'état).
 */
enum class FiltreCanalConsole {
    /** Arbre des étapes de sync (§3.3) + détail de téléchargement + pied. */
    SYNC,

    /** Tâches du build (mise à jour en place) + synthèse finale. */
    BUILD,
}

/**
 * Étape AFFICHÉE dans l'arbre de sync (v5 — le plan de l'APERÇU, sept
 * rangées) : le déroulé des PHASES (protocole v4, huit) reste réel, seules
 * deux d'entre elles partagent ici une rangée — MODELE_IDE et DEPENDANCES
 * se produisent pendant la MÊME résolution (les téléchargements alimentent
 * le modèle), et l'ancienne rangée DEPENDANCES restait « ○ à vie » sur une
 * sync sans téléchargement : la rangée fusionnée « Dépendances et modèle
 * IDE » conclut honnêtement dans les deux cas.
 */
internal enum class EtapeConsoleSync(
    /** Phases du déroulé réel rendues par cette rangée. */
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
        /** Rangée d'affichage d'une phase du déroulé réel. */
        fun dePhase(phase: EtapeSync): EtapeConsoleSync = entries.first { phase in it.phases }
    }
}

/**
 * Une rangée de la console STRUCTURÉE (v4, §3.3 ; v5 — aperçu ; v0.42.0,
 * phase 1 du roadmap : console hybride) : l'arbre des étapes de sync
 * (✓ / spinner / ○, durée), le détail de téléchargement sous l'étape
 * active, les TÂCHES du build et la synthèse de fin de build ou de sync —
 * UN modèle aplati pour un `RecyclerView`, l'indentation visuelle porte
 * la hiérarchie.
 *
 * Les lignes stdout/stderr BRUTES ne vivent plus ici : depuis la v0.42.0
 * elles s'affichent dans la zone TEXTE annexée (TextView monospace, append
 * direct O(1) par ligne) alimentée par [EvenementConsoleTexte] — les
 * mélanger dans le `RecyclerView` reconstruisait toutes les rangées à
 * chaque ligne (O(N²) : un build de 725 ms mettait 2 minutes à s'afficher).
 */
internal sealed interface RangeeConsole {
    /** Identité stable de la rangée (DiffUtil — mise à jour en place). */
    val idCle: String

    /**
     * Étape du DÉROULÉ AFFICHÉ de sync (§3.3 ; v5 — plan de l'aperçu) :
     * [etat] consolide les phases sous-jacentes — `null` = jamais
     * atteinte (○ en attente, jamais de durée devinée).
     */
    data class EtapeArbre(
        val etape: EtapeConsoleSync,
        val etat: EtatEtapeArbre?,
    ) : RangeeConsole {
        override val idCle: String
            get() = "etape-${etape.name}"
    }

    /**
     * Détail de téléchargement SOUS l'étape active (§3.3) : barre de
     * progression (octets reçus/total), compteur n/N, artefact courant.
     * La phase (câble) reste sa clé : seule l'étape ACTIVE en porte une.
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

    /**
     * Détail classpath par module SOUS l'étape CLASSPATHS (v0.40.1, prompt
     * de suivi §4) : une sous-ligne par module affichant « :app · 312
     * jars · 4 sources · variante debug ». La clé est le nom du module
     * pour DiffUtil (mise à jour en place si les stats changent).
     */
    data class DetailClasspath(
        val nomModule: String,
        val nbJars: Int?,
        val nbAars: Int?,
        val nbSources: Int?,
        val varianteAndroid: String?,
        val nbDependancesProjet: Int?,
    ) : RangeeConsole {
        override val idCle: String
            get() = "classpath-$nomModule"
    }

    /** Tâche du build (v3 ; v5 — SEULE ligne de la vue Build, les sorties
     *  brutes ne mélangent plus l'écran ; v0.42.0 — phase 1 : les lignes
     *  brutes vivent dans la zone TEXTE annexée) : mise à jour en place à
     *  sa fin. */
    data class Tache(
        val ligne: LigneConsole.Tache,
    ) : RangeeConsole {
        override val idCle: String
            get() = "tache-${ligne.id}"
    }

    /** Synthèse finale du build (§3.3 ; v0.39.1 — correctif n°4 : style
     *  Android Studio) : verdict + durée + compte des tâches actionnables
     *  (« N actionable tasks: M executed[, K up-to-date] »), message
     *  d'échec le cas échéant — la dernière ligne de la vue Build, comme
     *  la synthèse d'Android Studio. */
    data class SyntheseBuild(
        val statut: StatutBuild,
        val dureeMs: Long?,
        val message: String?,
        /** Total des tâches actionnables — `null` si non observé. */
        val tachesActionnables: Int? = null,
        /** Tâches réellement exécutées — `null` si non observé. */
        val tachesExecutees: Int? = null,
        /** Tâches à jour (incrémental) — `null` si Gradle ne l'imprime
         *  pas (build sans cache, premier lancement). */
        val tachesAJour: Int? = null,
    ) : RangeeConsole {
        override val idCle: String
            get() = "synthese-build"
    }

    /** Pied de conclusion de la sync (v5, aperçu ; v0.40.1 prompt de suivi
     *  §4 — récapitulatif classpath) : « Synchronisation terminée » ou
     *  « Projet à jour, rien à télécharger » + disponibilité des tâches
     *  + récapitulatif classpath (total modules / jars / sources / AARs).
     *  Présent SEULEMENT une fois la sync réussie terminée. */
    data class SyntheseSync(
        val aJour: Boolean,
        /** Total modules du projet (v0.40.1 §4) — `null` si non résolu. */
        val nbModules: Int? = null,
        /** Total JARs résolus tous modules confondus (v0.40.1 §4). */
        val nbJars: Int? = null,
        /** Total AARs résolus tous modules confondus (v0.40.1 §4). */
        val nbAars: Int? = null,
        /** Total répertoires sources tous modules confondus (v0.40.1 §4). */
        val nbSources: Int? = null,
    ) : RangeeConsole {
        override val idCle: String
            get() = "synthese-sync"
    }
}

/**
 * État CONSOLIDÉ d'une rangée de l'arbre (v5 ; v6 — prompt de suivi §2 :
 * suppression du champ `sautee`) : fusionne les phases sous-jacentes de
 * l'étape affichée.
 *
 * @property terminee `true` quand TOUTES les phases annoncées sont
 *           terminées (la fusion « Dépendances et modèle IDE » attend les
 *           deux — une phase jamais annoncée n'attend rien).
 * @property dureeMs cumul des durées des phases terminées (la fusion peut
 *           CHEVAUCHER ses phases : le cumul est un temps de travail, pas
 *           une fenêtre calendaire — KDoc de l'aperçu).
 * @property octetsRecus octets reçus cumulés de la phase porteuse.
 * @property octetsTotal octets totaux si connus — `null` sinon.
 * @property element élément courant (artefact, projet).
 * @property compteur éléments terminés de la phase porteuse (n).
 * @property total éléments totaux de la phase porteuse (N).
 */
internal data class EtatEtapeArbre(
    val terminee: Boolean,
    val dureeMs: Long,
    val octetsRecus: Long = 0,
    val octetsTotal: Long? = null,
    val element: String? = null,
    val compteur: Int? = null,
    val total: Int? = null,
)

/**
 * Construit les rangées STRUCTURÉES de la console selon le filtre (§3.3 ;
 * v5 — aperçu ; v0.42.0 — phase 1 : les sorties brutes vivent dans la zone
 * texte annexée) : FONCTION PURE — même discipline de test que le
 * présentateur, aucune chaîne n'y est construite (les libellés se
 * résolvent au rendu).
 *
 * - SYNC : l'arbre des étapes affichées + le pied de conclusion — RIEN
 *   d'autre (l'ancienne chronologie brute ne se mélange plus) ; l'arbre
 *   reste ABSENT tant qu'aucune sync n'a été annoncée (l'état vide de la
 *   console parle à sa place) ;
 * - BUILD : les tâches (une ligne par tâche) + la synthèse — les sorties
 *   brutes du build s'affichent dans la zone TEXTE annexée, pas ici.
 */
internal fun construireRangeesConsole(
    etat: EtatGradle,
    filtre: FiltreCanalConsole,
): List<RangeeConsole> =
    when (filtre) {
        FiltreCanalConsole.SYNC -> {
            arbreEtapesSync(etat) + piedSync(etat)
        }

        FiltreCanalConsole.BUILD -> {
            // v0.42.0 (phase 1 du roadmap) : les lignes stdout/stderr
            // brutes ne traversent PLUS le RecyclerView — la zone TEXTE
            // annexée les reçoit par append direct (O(1) par ligne). La
            // vue Build ne garde que les tâches structurées et la
            // synthèse, comme la vue Build d'Android Studio.
            etat.lignes
                .filterIsInstance<LigneConsole.Tache>()
                .map { ligne -> RangeeConsole.Tache(ligne) } + syntheseBuild(etat)
        }
    }

/**
 * L'arbre des étapes (§3.3 ; v5 — aperçu ; v0.41.1 — étapes PROGRESSIVES :
 * une étape n'apparaît que quand Gradle l'atteint, pas tout l'arbre d'un
 * coup. Les étapes terminées restent visibles (l'utilisateur voit le
 * chemin parcouru), les étapes non encore atteintes n'existent pas dans
 * la liste). Le détail de téléchargement s'insère sous l'étape ACTIVE.
 * v0.42.0 (phase 1) : plus de lignes brutes sous les étapes — elles vivent
 * dans la zone TEXTE annexée (la pompe du canal Sync n'existe pas, seul
 * le build alimente la zone texte).
 */
private fun arbreEtapesSync(etat: EtatGradle): List<RangeeConsole> {
    val annoncees = etat.etapesAffichees.associateBy { it.etape }
    if (annoncees.isEmpty()) return emptyList()
    val phaseActive = etat.etapeCourante?.takeIf { etat.synchronisationEnCours && !it.terminee }
    val rangees = mutableListOf<RangeeConsole>()
    for (etape in EtapeConsoleSync.entries) {
        // v0.41.1 : étapes PROGRESSIVES — ne montrer QUE les étapes qui
        // ont été annoncées par le serveur. Les étapes non encore
        // atteintes (état `null`) n'apparaissent pas — l'arbre grandit
        // au fur et à mesure, comme Android Studio.
        val etatConsolide = consolider(etape, annoncees) ?: continue
        rangees += RangeeConsole.EtapeArbre(etape, etatConsolide)

        // Détail de téléchargement SOUS l'étape active.
        if (phaseActive != null && EtapeConsoleSync.dePhase(phaseActive.etape) == etape) {
            rangees +=
                RangeeConsole.DetailTelechargement(
                    phase = phaseActive.etape,
                    octetsRecus = phaseActive.octetsRecus,
                    octetsTotal = phaseActive.octetsTotal,
                    compteur = phaseActive.compteur,
                    total = phaseActive.total,
                    element = phaseActive.element,
                )
        }

        // v0.40.1 (prompt de suivi §4) : sous-lignes classpath par module
        // sous l'étape CLASSPATHS.
        if (etape == EtapeConsoleSync.CLASSPATHS && etat.statsClasspath != null) {
            rangees +=
                etat.statsClasspath.map { module ->
                    RangeeConsole.DetailClasspath(
                        nomModule = module.nom,
                        nbJars = module.nbJars,
                        nbAars = module.nbAars,
                        nbSources = module.nbSources,
                        varianteAndroid = module.varianteAndroid,
                        nbDependancesProjet = module.nbDependancesProjet,
                    )
                }
        }
    }
    return rangees
}

/** Consolide l'état d'une rangée depuis ses phases annoncées — `null`
 *  quand aucune n'a été annoncée (rangée en attente ○). v6 (prompt de
 *  suivi §2) : plus de `sautee` — une phase qui n'a pas lieu n'est pas
 *  annoncée du tout, donc pas présente dans [annoncees]. */
private fun consolider(
    etape: EtapeConsoleSync,
    annoncees: Map<EtapeSync, EtapeSyncAffichee>,
): EtatEtapeArbre? {
    val sousJacentes = etape.phases.mapNotNull { annoncees[it] }
    if (sousJacentes.isEmpty()) return null
    // Phase PORTEUSE des détails : la VIVANTE d'abord (le téléchargement
    // en cours), sinon la dernière phase qui en portait (le compteur
    // final des dépendances reste lisible une fois conclue).
    val porteuse =
        sousJacentes.lastOrNull { !it.terminee }
            ?: sousJacentes.lastOrNull { it.octetsRecus > 0 || it.compteur != null }
            ?: sousJacentes.last()
    return EtatEtapeArbre(
        terminee = sousJacentes.all { it.terminee },
        dureeMs = sousJacentes.filter { it.terminee }.sumOf { it.dureeMs },
        octetsRecus = porteuse.octetsRecus,
        octetsTotal = porteuse.octetsTotal,
        element = porteuse.element,
        compteur = porteuse.compteur,
        total = porteuse.total,
    )
}

/** Pied de conclusion de la sync (v5, aperçu ; v0.40.1 §4 — récapitulatif
 *  classpath) : absent en vol, à l'échec ou sans résultat ; « Projet à
 *  jour, rien à télécharger » honnête quand AUCUN octet n'a été reçu +
 *  récapitulatif classpath (total modules / jars / sources / AARs). */
private fun piedSync(etat: EtatGradle): List<RangeeConsole.SyntheseSync> {
    val reussie = etat.synchronisationReussie
    return if (reussie?.reussie == true && !etat.synchronisationEnCours) {
        // v0.40.1 (prompt de suivi §4) : récapitulatif classpath —
        // agrégation des stats par module en totaux.
        val stats = etat.statsClasspath
        val nbModules = stats?.size
        val nbJars = stats?.sumOf { it.nbJars ?: 0 }
        val nbAars = stats?.sumOf { it.nbAars ?: 0 }
        val nbSources = stats?.sumOf { it.nbSources ?: 0 }
        listOf(
            RangeeConsole.SyntheseSync(
                aJour = etat.etapesAffichees.none { it.octetsRecus > 0 },
                nbModules = nbModules,
                nbJars = nbJars,
                nbAars = nbAars,
                nbSources = nbSources,
            ),
        )
    } else {
        emptyList()
    }
}

/** Synthèse de fin de build : absente en vol, présente une fois terminé.
 *  v0.39.1 (correctif n°4) : la synthèse porte AUSSI les comptes de
 *  tâches actionnables/exécutées/à jour extraits côté serveur de la
 *  dernière ligne stdout de Gradle — la console les affiche sous le
 *  verdict comme Android Studio. */
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
                    tachesActionnables = etat.tachesActionnablesBuild,
                    tachesExecutees = etat.tachesExecuteesBuild,
                    tachesAJour = etat.tachesAJourBuild,
                ),
            )
        }

        null,
        StatutBuild.EN_COURS,
        -> {
            emptyList()
        }
    }
