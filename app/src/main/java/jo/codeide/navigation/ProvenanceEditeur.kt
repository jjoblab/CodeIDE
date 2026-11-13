package jo.codeide.navigation

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provenance éditeur process-wide (v0.183.0, retour propriétaire) :
 * l'espace de travail qui ouvre les paramètres note ici son projet —
 * l'hôte du graphe (MainActivity) intercepte le RETOUR depuis l'écran
 * maître des paramètres pour remonter CET espace vivant au lieu de
 * retomber sur l'accueil.
 *
 * Singleton : le navigateur est scopé à l'activité (l'espace et l'hôte
 * du graphe en tiennent chacun un) — SEUL ce titulaire partage l'état
 * entre les deux.
 *
 * Classe PUBLIQUE (même patron que `RegistreDossierProjetIa`) car
 * injectée dans des classes publiques (MainActivity) — Dagger refuse
 * les champs privés et Kotlin interdit d'exposer un type interne ; les
 * MEMBRES restent internes au module `app`.
 *
 * Consommation en un seul geste : le retour vers l'espace EFFACE la
 * provenance — le retour suivant se comporte à nouveau normalement.
 */
@Singleton
public class ProvenanceEditeur
@Inject
constructor() {
    /** Identifiant du projet dont l'espace a ouvert le graphe, ou `null`. */
    @Volatile
    internal var projet: String? = null

    /** Retire-et-retourne la provenance (une seule remontée par note). */
    internal fun consommer(): String? {
        val id = projet
        projet = null
        return id
    }
}
