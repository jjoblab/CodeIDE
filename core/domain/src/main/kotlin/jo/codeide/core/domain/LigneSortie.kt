package jo.codeide.core.domain

/**
 * Source d'une ligne du Centre de sorties (ADR 0160 § Décision 1) — la
 * famille à laquelle appartient la ligne, pour le sélecteur de source et
 * le filtre unifié.
 */
public enum class SourceSortie {
    /** Sorties d'un build Gradle (tâches, compilation, échecs). */
    BUILD,

    /** Sorties d'une synchronisation de projet (ADR 0079 : flux du câble). */
    SYNC,

    /** Sorties du listage des tâches du projet (sélecteur « Exécuter »). */
    TACHES,

    /** Journaux du serveur de build embarqué (tag `gradle-server`). */
    SERVEUR,

    /** Logcat de l'application exécutée (pont sans adb, ADR 0103). */
    LOGCAT,

    /** Journal applicatif de CodeIDE (`AppLogger`, ADR 0025). */
    JOURNAL,
}

/**
 * Niveau NORMALISÉ d'une ligne de sortie (ADR 0160 § Décision 1) — une
 * échelle unique à sept valeurs qui aligne les trois modèles existants :
 * les six niveaux de [NiveauJournal] (miroir V/D/I/W/E/F) plus
 * [INDETERMINE] pour les lignes de flux sans sévérité (le stdout de
 * Gradle n'est ni une info ni un avertissement).
 *
 * Projection implicite depuis `LogLevel` (journal applicatif) :
 * DEBUG→[DEBOGAGE], INFO→[INFO], WARN→[AVERTISSEMENT], ERROR→[ERREUR],
 * ASSERT/FATAL→[ASSERT]. L'ordre des valeurs EST l'ordre de sévérité
 * croissante ; [INDETERMINE] est en DERNIER : un filtre par niveau
 * minimal ne masque JAMAIS une ligne qu'il ne sait pas graduer (règle
 * 14 — jamais de perte silencieuse), seule une sélection explicite
 * d'ensemble ([FiltreSorties.niveaux]) peut la masquer.
 */
public enum class NiveauSortie {
    /** Verbose (V) — miroir [NiveauJournal.VERBEUX]. */
    VERBEUX,

    /** Débogage (D) — miroir [NiveauJournal.DEBOGAGE]. */
    DEBOGAGE,

    /** Information (I) — miroir [NiveauJournal.INFO]. */
    INFO,

    /** Avertissement (W) — miroir [NiveauJournal.AVERTISSEMENT]. */
    AVERTISSEMENT,

    /** Erreur (E) — miroir [NiveauJournal.ERREUR]. */
    ERREUR,

    /** Fatal / assertion (F) — miroir [NiveauJournal.ASSERT]. */
    ASSERT,

    /** Sans sévérité déterminable — ligne de flux Gradle purement
     *  informative (SORTIE ou TELECHARGEMENT côté console actuelle). */
    INDETERMINE,
}

/**
 * Gravité d'une position de source extraite du flux (ADR 0160) — un
 * caractère cliquable du lecteur se colore selon la gravité, jamais la
 * couleur seule (spec SORTIES § 2, jetons réels).
 */
public enum class GravitePosition {
    /** Erreur de compilation ou de configuration (`e:`, `error:`). */
    ERREUR,

    /** Avertissement de compilation (`w:`, `warning:`). */
    AVERTISSEMENT,
}

/**
 * Une position de source EXTRAITE au passage par [ParseurFluxGradle]
 * (ADR 0160 § Décision 1) — `e: file://…:l:c` (Kotlin),
 * `fichier:l[:c]: error:` (Java), `Build file '…' line: l` (* Where:).
 * Consommée par le saut au source (même contrat que l'onglet Problèmes,
 * spec SORTIES § 4.3) : pas de second endroit pour la même information.
 *
 * @property fichier chemin du fichier (le préfixe `file://` est retiré
 *           par le parseur — un chemin nu, prêt pour le saut).
 * @property ligne numéro de ligne (1-based) tel qu'imprimé par l'outil.
 * @property colonne numéro de colonne si l'outil l'imprime, sinon `null`.
 * @property gravite gravité annoncée par le marqueur (`e:`/`error:` ou
 *           `w:`/`warning:`), jamais déduite du contenu.
 */
public data class PositionSource(
    public val fichier: String,
    public val ligne: Int,
    public val colonne: Int? = null,
    public val gravite: GravitePosition = GravitePosition.ERREUR,
)

/**
 * LE modèle de ligne unique du Centre de sorties (ADR 0160 § Décision 1,
 * lot L1) — type pur JVM, testé, SANS dépendance Android : le tampon du
 * lecteur (L2) en stocke, le filtre ([FiltreSorties]) en travaille sur
 * l'index, la projection regroupée (L3) en est une dérivation.
 *
 * Discipline héritée de `PresentationTooling` : aucun libellé résolu
 * dans le modèle — les textes localisables voyagent en ressource et se
 * résolvent au rendu ; [message] n'est ici QUE du contenu technique
 * (sorties d'outils, messages de journaux), jamais un libellé d'interface.
 *
 * Une ligne SANS horodatage est horodatée à la réception côté domaine
 * (jamais côté UI, ADR 0160) — `horodatageMs = 0` reste un état
 * transitoire de construction, l'adaptateur d'ingestion le remplit.
 *
 * @property source famille de sorties (voir [SourceSortie]).
 * @property message contenu technique de la ligne, sans terminaison.
 * @property horodatageMs instant Unix d'émission (0 = à horodater à
 *           l'ingestion — l'adaptateur le fait, jamais l'UI).
 * @property identiteSession identité de la session de journaux (ADR 0103 :
 *           `(paquet, pid, processus)`) OU du lancement de CodeIDE
 *           (journal applicatif, UUID) — `null` pour un flux Gradle brut.
 * @property idExecution référence à l'exécution Gradle concernée
 *           (ADR 0161) — `null` hors build/sync/tâches.
 * @property niveau niveau normalisé (voir [NiveauSortie]).
 * @property etiquette étiquette courte (tag logcat, canal tooling, tag
 *           journal, nom d'artefact téléchargé) — `null` si sans objet.
 * @property tache chemin Gradle de la tâche à laquelle la ligne est
 *           rattachée (`:app:compileDebugKotlin`) — posé par les
 *           ÉVÉNEMENTS `EtatTacheBuild` ou l'annonce texte `> Task :…`,
 *           JAMAIS deviné d'une ligne de contenu (Q6, spec SORTIES § 4.2).
 * @property statutTache statut de la tâche porté par l'ÉVÉNEMENT
 *           (`EtatTacheBuild`, durées serveur) — jamais déduit du texte.
 * @property dureeMs durée portée par l'événement ou le verdict de fin
 *           (`EtatTacheBuild`, `TelechargementBuild`) — jamais mesurée
 *           côté client pour un fait serveur.
 * @property octetsRecus octets reçus d'un téléchargement (arbitrage Q12 :
 *           la µ-jauge à la ligne rend ce que le protocole fournit
 *           réellement — total connu ou cumul de phase, § 4.7) — `null`
 *           hors ligne de téléchargement.
 * @property octetsTotal octets totaux du téléchargement si le protocole
 *           les connaît (sonde `.part`), sinon `null` — JAMAIS de
 *           pourcentage inventé (honnêteté, arbitrage Q12).
 * @property pid identifiant du processus émetteur (Logcat, § 5.4 —
 *           affichable sur option du lecteur) ; `null` hors Logcat.
 * @property tid identifiant du fil émetteur (Logcat, § 5.4) ; `null`
 *           hors Logcat.
 * @property positionsSource positions cliquables extraites au passage par
 *           [ParseurFluxGradle] — vide pour la plupart des lignes.
 */
public data class LigneSortie(
    public val source: SourceSortie,
    public val message: String,
    public val horodatageMs: Long = 0,
    public val identiteSession: String? = null,
    public val idExecution: String? = null,
    public val niveau: NiveauSortie = NiveauSortie.INDETERMINE,
    public val etiquette: String? = null,
    public val tache: String? = null,
    public val statutTache: StatutTache? = null,
    public val dureeMs: Long? = null,
    public val octetsRecus: Long? = null,
    public val octetsTotal: Long? = null,
    public val pid: Int? = null,
    public val tid: Int? = null,
    public val positionsSource: List<PositionSource> = emptyList(),
)
