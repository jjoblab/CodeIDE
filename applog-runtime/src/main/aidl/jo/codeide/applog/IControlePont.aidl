// IControlePont — sens IDE → app cible (ADR 0103 §1 : Binder
// bidirectionnel ; §10 : réduction à la source).
//
// L'IDE garde ce binder (passé par connecter) : il fait son linkToDeath
// dessus (mort immédiate de l'app détectée) et peut commander la veille
// du pont quand personne n'écoute — la collecte s'arrête, l'anneau
// tamponne, la batterie est économisée.
package jo.codeide.applog;

oneway interface IControlePont {
    /** Suspend la collecte (personne n'écoute côté IDE). */
    void suspendre();

    /** Reprend la collecte (un auditeur est revenu). */
    void reprendre();
}
