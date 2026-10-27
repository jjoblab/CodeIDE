package jo.codeide.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registre singleton du dossier projet RÉEL pour l'IA (mission IA,
 * ADR 0122 — miroir de [RegistrePortEditeurActif]) : l'activité
 * d'édition publie le chemin FUSE résolu (à la création, PUIS à chaque
 * ouverture du tiroir — C1, retour propriétaire) ; le fournisseur de
 * contexte du tiroir IA le lit pour lire les fichiers `@mentionnés`
 * et lister les chemins du projet.
 *
 * Dossier absent (processus recréé, aucun projet ouvert) : le
 * fournisseur traite l'absence comme « pas de projet » — refus typé,
 * jamais de lecture hors racine. Les chemins transmis au modèle restent
 * RELATIFS (ADR 0124 §4).
 *
 * Thread : publication (fil UI) et lecture (dispatchers IO) ne se
 * bloquent pas — [MutableStateFlow] (les écrans observent l'arrivée du
 * chemin : la puce « Projet » de l'agent passe au vert quand la
 * résolution atterrit, jamais rouge sur un état périmé).
 */
@Singleton
public class RegistreDossierProjetIa
@Inject
constructor() {
    private val dossierInterne = MutableStateFlow<String?>(null)

    /** Publie (ou efface avec `null`) le dossier réel du projet. */
    public fun publier(dossier: String?) {
        dossierInterne.value = dossier
    }

    /** Le dossier réel publié, ou `null` (aucun projet résolu). */
    public fun dossier(): String? = dossierInterne.value

    /**
     * Projection observable du dossier publié (C1, retour
     * propriétaire) : la résolution FUSE du démarrage peut aboutir
     * APRÈS l'arrivée sur l'écran Agent — les pré-vérifications
     * collectent ce flux et se recalculent à l'atterrissage.
     */
    public val etat: StateFlow<String?> = dossierInterne.asStateFlow()
}
