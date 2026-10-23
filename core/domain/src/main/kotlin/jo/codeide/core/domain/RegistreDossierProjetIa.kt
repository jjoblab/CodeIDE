package jo.codeide.core.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registre singleton du dossier projet RÉEL pour l'IA (mission IA,
 * ADR 0122 — miroir de [RegistrePortEditeurActif]) : l'activité
 * d'édition publie le chemin FUSE résolu (une fois, en arrière-plan) ;
 * le fournisseur de contexte du tiroir IA le lit pour lire les fichiers
 * `@mentionnés` et lister les chemins du projet.
 *
 * Dossier absent (processus recréé, aucun projet ouvert) : le
 * fournisseur traite l'absence comme « pas de projet » — refus typé,
 * jamais de lecture hors racine. Les chemins transmis au modèle restent
 * RELATIFS (ADR 0124 §4).
 *
 * Thread : publication (fil UI) et lecture (dispatchers IO) ne se
 * bloquent pas — champ volatile.
 */
@Singleton
public class RegistreDossierProjetIa
@Inject
constructor() {
    @Volatile
    private var dossier: String? = null

    /** Publie (ou efface avec `null`) le dossier réel du projet. */
    public fun publier(dossier: String?) {
        this.dossier = dossier
    }

    /** Le dossier réel publié, ou `null` (aucun projet résolu). */
    public fun dossier(): String? = dossier
}
