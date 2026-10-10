package jo.codeide.core.domain

/**
 * Port de l'archive des sessions de journaux TERMINÉES (mission
 * « Exécuter » R3, spec § 4.3 « Sessions ») : la raison de fin et la
 * DERNIÈRE ligne de chaque session restent consultables ENTRE DEUX
 * démarrages de CodeIDE — bornées, dans le stockage PRIVÉ.
 *
 * Ce qui est archivé (et rien d'autre — pas de contenu de tampon, la
 * politique mobile honnête reste la règle) :
 *
 * - l'identité (paquet, pid, nom de processus) ;
 * - la raison de fin et son horodatage ;
 * - le nombre de lignes reçues et perdues ;
 * - la DERNIÈRE ligne (l'aperçu du sélecteur de processus).
 *
 * Implémentation Android : JSON borné dans `filesDir` (module `app`) ;
 * tests : faux en mémoire.
 */
public interface ArchiveSessionsJournal {
    /**
     * Charge les sessions archivées, la plus récente d'abord.
     * Un stockage absent, vide ou corrompu se lit comme une liste vide —
     * jamais d'exception (c'est un bonus de consultation, pas une donnée
     * critique).
     */
    public suspend fun charger(): List<SessionJournalArchivee>

    /**
     * Archive (ou ré-archive) une session terminée : la plus ancienne est
     * sacrifiée quand la borne d'entrée est atteinte.
     *
     * @param session la session VENUE de se terminer (état TERMINEE)
     * @param derniereLigne la dernière ligne reçue, ou `null` (aucune)
     */
    public suspend fun archiver(
        session: SessionJournal,
        derniereLigne: LigneJournal?,
    )
}

/**
 * Une session de journaux terminée, telle que conservée entre deux
 * démarrages de CodeIDE.
 *
 * @property session l'état final de la session (identité, fin, compteurs)
 * @property derniereLigne la dernière ligne reçue — l'aperçu, ou `null`
 */
public data class SessionJournalArchivee(
    public val session: SessionJournal,
    public val derniereLigne: LigneJournal?,
)
