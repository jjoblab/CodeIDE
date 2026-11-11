package jo.codeide.core.session

/**
 * Raison d'une fermeture de session de projet (prompt 3 §5.2 — la
 * politique des transitions).
 *
 * La raison est portée au [ContexteFermeture] remis à chaque [Fermable] :
 * une ressource peut adapter sa libération (le flush des sauvegardes
 * n'attend pas la même chose qu'un arrêt de surveillance).
 */
public enum class RaisonFermeture {
    /** Sortie explicite de l'éditeur demandée par l'utilisateur (retour, dialogue « Quitter »). */
    Utilisateur,

    /** Ouverture d'un autre projet : la session courante se ferme D'ABORD (I1, gestionnaire). */
    ChangementDeProjet,

    /** Fermeture administrative : échec d'ouverture, nettoyage de test, arrêt du processus. */
    Nettoyage,
}

/**
 * Contexte remis à [Fermable.fermer] — la seule information dont une
 * ressource a besoin pour se libérer correctement.
 *
 * @property raison pourquoi la session se ferme.
 */
public data class ContexteFermeture(public val raison: RaisonFermeture)

/**
 * Contrat d'une ressource qui participe à la fermeture propre d'une
 * session de projet (prompt 3 §5.1 et §5.5 ; ADR 0114).
 *
 * Une ressource s'abonne auprès de [RegistreFermeture.enregistrer] AU
 * MOMENT où elle démarre (l'ordre d'enregistrement est l'ordre de
 * démarrage, refermé dans l'ordre INVERSE). Le contrat attendu de
 * l'implémentation :
 *
 * - **idempotente** (I2) : la fermer deux fois ne fait rien la seconde
 *   fois — le registre n'appelle qu'une fois, mais l'adaptateur peut
 *   être refermé par ailleurs (fermeture d'onglet, écran détruit…) ;
 * - **retour dans son délai** : la libération est une fonction
 *   `suspend` ; un élément qui bloque SANS suspendre (I/O synchrones
 *   longues) ne peut PAS être interrompu par le délai — l'adaptateur
 *   doit suspendre (`delay`) ou s'exécuter via `runInterruptible` ;
 * - **jamais de réentrance** : ne pas rappeler `SessionProjet.fermer`
 *   depuis `fermer()` (refus typé `AppError.Session(Reentrance)`) ni
 *   appeler le gestionnaire de session ;
 * - **une exception est une panne, pas un arrêt** : le registre la
 *   journalise et libère les AUTRES éléments (I2, panne partielle).
 *
 * @property nom identifiant stable de la ressource pour le rapport de
 * fermeture et les journaux (règle 15 : un identifiant, JAMAIS un nom de
 * projet, un chemin ni un contenu de fichier).
 */
public interface Fermable {
    /**
     * Identifiant stable de la ressource, pour le rapport de fermeture et
     * les journaux (règle 15 : un identifiant, JAMAIS un nom de projet, un
     * chemin ni un contenu de fichier).
     */
    public val nom: String

    /**
     * Libère la ressource.
     *
     * @param contexte raison de la fermeture.
     */
    public suspend fun fermer(contexte: ContexteFermeture)
}

/**
 * Poignée rendue par [RegistreFermeture.enregistrer] : retire la
 * ressource du registre quand elle meurt de SA propre initiative (fermeture
 * d'onglet, écran détruit) — la fermeture de session ne doit alors PAS la
 * rappeler.
 *
 * [desenregistrer] est idempotente et *best-effort* : après une fermeture
 * de session complète, l'appel ne fait rien.
 */
public interface Desenregistrement {
    /** Retire la ressource du registre (idempotente). */
    public fun desenregistrer()
}
