package jo.codeide.core.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registre singleton du port d'éditeur actif (mission IA, ADR 0122 —
 * miroir de [RegistreSourcesApercu]) : le `EditorViewModel` de
 * `:feature:editor` enregistre le [PortEditeurActif] de l'onglet actif à
 * l'ouverture/sélection d'un onglet et le retire à la fermeture ; le
 * domaine IA (`:core:ai`) le lit au moment d'assembler un contexte ou
 * d'appliquer un lot d'édition.
 *
 * UN SEUL éditeur est actif à un instant donné : l'enregistrement
 * REMPLACE le port précédent (pas une carte — l'IA ne cible que l'éditeur
 * visible). Port absent (processus recréé, aucun onglet) : le domaine IA
 * traite l'absence comme « pas de contexte éditeur » — jamais de blocage.
 *
 * Thread : enregistrement (fil UI, onglets) et lecture (dispatchers IA)
 * ne se bloquent pas — champ volatile.
 */
@Singleton
public class RegistrePortEditeurActif
@Inject
constructor() {
    @Volatile
    private var port: PortEditeurActif? = null

    /** Enregistre (ou remplace) le port de l'éditeur actif ; `null` le retire. */
    public fun enregistrer(port: PortEditeurActif?) {
        this.port = port
    }

    /** Le port de l'éditeur actif, ou `null` (aucun onglet, processus recréé). */
    public fun actif(): PortEditeurActif? = port
}
