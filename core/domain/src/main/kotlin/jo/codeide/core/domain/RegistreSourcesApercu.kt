package jo.codeide.core.domain

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registre singleton des sources d'aperçu (PE6-0, maquette
 * `docs/mockups/apercu-xml.html` § « Partage de la session ») : la
 * session d'édition vit dans le `EditorViewModel` de `:feature:editor`
 * (privée, inaccessible par `by viewModels()` depuis une autre
 * activité) — ce registre est le point de partage EXPLICITE.
 *
 * L'éditeur enregistre chaque source à l'ouverture d'un onglet et la
 * libère à sa fermeture/disposition ; l'activité d'aperçu lit.
 * Les deux côtés dépendent de `:core:domain` — jamais l'un de l'autre.
 *
 * Piège du propriétaire (maquette § « Mort du processus ») : Android ne
 * recrée que l'activité du DESSUS — le registre est alors VIDE (le
 * ViewModel de l'éditeur n'existe plus). L'aperçu doit se terminer et
 * relancer l'éditeur, jamais afficher un panneau vide : voir
 * `PreviewViewModel` (`:feature:preview`).
 *
 * Thread : ConcurrentHashMap — enregistrement (fil UI, création
 * d'onglet) et lecture (création de l'aperçu, sélecteur de fichier) ne
 * se bloquent pas.
 */
@Singleton
public class RegistreSourcesApercu
@Inject
constructor() {
    private val sources = ConcurrentHashMap<String, SourceApercu>()

    /** Enregistre (ou remplace) la source d'un document ouvert. */
    public fun enregistrer(source: SourceApercu) {
        sources[source.uri] = source
    }

    /** Libère la source d'un document fermé (onglet fermé, session disposée). */
    public fun liberer(uri: String) {
        sources.remove(uri)
    }

    /** La source d'un document ouvert, ou `null` (jamais ouvert ou processus mort). */
    public fun sourceDe(uri: String): SourceApercu? = sources[uri]

    /** Les sources enregistrées, TRIÉES par chemin relatif (sélecteur de fichier déterministe). */
    public fun sources(): List<SourceApercu> = sources.values.sortedBy { it.cheminRelatif }
}
