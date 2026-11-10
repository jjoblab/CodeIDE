package jo.codeide.feature.sorties

/**
 * Les FAMILLES de sources du Centre de sorties (lot L4, ADR 0162) — le
 * lecteur du composant est LE MÊME pour toutes ; chaque famille a SON
 * flux derrière le port [FluxSorties] :
 *
 * - [GRADLE] : le câble tooling du serveur de build (lot L3 —
 *   `FluxSortiesCentre` côté feature:editor, routage banc ↔ réel) ;
 * - [LOGCAT] : les journaux de l'application exécutée (R3/R4, lot L4 —
 *   le pont `PontJournauxApplications`, sans adb) ;
 * - [JOURNAL] : le journal applicatif de CodeIDE (lot L4 —
 *   `LogRepository`).
 *
 * Les familles sont des FLUX SÉPARÉS, jamais un routage dynamique
 * partagé : deux lecteurs simultanés (onglet Logcat et onglet Journal
 * du même panneau) ne peuvent pas se contaminer mutuellement — un
 * tampon ne voit QUE les lignes de sa famille.
 */
public enum class FamilleSorties {
    /** Sorties du serveur de build Gradle (sync, build, listage). */
    GRADLE,

    /** Logcat de l'application exécutée (pont sans adb, R3/R4). */
    LOGCAT,

    /** Journal applicatif de CodeIDE (`AppLogger`). */
    JOURNAL,
}

/**
 * La fabrique du flux PAR FAMILLE (lot L4) — le seul câblage que le
 * lecteur connaît désormais : le ViewModel choisit SA famille à la
 * construction et reçoit le flux correspondant. L'implémentation est
 * liée côté feature:editor (`DiSortiesEditeur`) ; l'INTERFACE vit ici
 * pour que tout consommateur du lecteur (feature:diagnostics inclus)
 * l'injecte sans dépendre de feature:editor (le graphe Hilt est global,
 * les arêtes de compilation restent propres — ADR 0162).
 */
public interface FabriqueFluxSorties {
    /** Le flux de la famille [famille] — une instance singleton par
     *  famille (les collecteurs vivent process-wide, comme leurs
     *  câbles : pompe tooling, pont Binder, dépôt de journaux). */
    public fun flux(famille: FamilleSorties): FluxSorties
}

/** La clé de l'argument du fragment hôte ([FragmentLecteurSorties]) qui
 *  porte la famille au ViewModel (via `SavedStateHandle`, lot L4) —
 *  survit à la rotation (les arguments du fragment persistent). */
internal const val CLE_FAMILLE = "famille_sorties"

/** Fenêtre du tampon pour la famille LOGCAT — parité exacte avec le
 *  miroir d'affichage de l'ancien onglet (`CAPACITE_TAMPON`, R3) et le
 *  tampon du pont (`CAPACITE_LIGNES`). */
public const val FENETRE_LOGCAT: Int = 5_000

/** Fenêtre du tampon pour la famille JOURNAL — couvre l'historique
 *  typique d'une rétention de 7 jours (`LogConfig`) ; au-delà, la
 *  troncature de tête est comptée et annoncée (honnêteté règle 14). */
public const val FENETRE_JOURNAL: Int = 10_000
