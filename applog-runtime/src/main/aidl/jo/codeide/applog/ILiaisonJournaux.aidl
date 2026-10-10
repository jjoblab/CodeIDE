// ILiaisonJournaux — sens app cible → IDE (ADR 0103).
//
// Chaque méthode est oneway : l'application n'attend JAMAIS l'IDE — un
// envoi par lot, le débit est borné côté pont (anneau + limite de taille
// par lot). L'IDE identifie l'appelant par Binder.getCallingUid() :
// l'identité déclarée ici est un INDEX de recherche (paquet → UID
// attendu), jamais une preuve.
package jo.codeide.applog;

oneway interface ILiaisonJournaux {
    /**
     * Première transaction d'une connexion : annonce l'identité du pont
     * (paquet, pid, nom de processus) et confie le binder de CONTRÔLE
     * (IControlePont) que l'IDE garde pour linkToDeath et les commandes
     * de veille.
     */
    void connecter(String nomPaquet, int pid, String nomProcessus, in IBinder controle, int versionProtocole);

    /**
     * Lot de trames tabulaires (format documenté dans Pont.java) +
     * nombre de lignes PERDUES par débordement de l'anneau depuis le
     * dernier lot (jamais silencieux, ADR 0103 §5).
     */
    void envoyerLot(in List<String> trames, long lignesPerdues);

    /** Fermeture volontaire du pont, avec la raison lisible. */
    void deconnecter(String raison);
}
