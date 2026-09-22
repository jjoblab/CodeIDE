package jo.codeide.core.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Port des permissions persistantes SAF (section 5.6).
 *
 * Isolé en interface pour deux raisons :
 * - **testabilité** : Robolectric ne simule pas l'état des permissions
 *   persistantes du système ; les tests de `SafFileSystem` injectent un
 *   faux cohérent sans toucher au vrai service système ;
 * - **lisibilité** : la politique (lecture + écriture, plafond de 512
 *   permissions sur Android 11+ — n'en prendre que le nécessaire) vit
 *   en un seul endroit, à côté des appels système qui la portent.
 */
internal interface PersistableUriPermissions {
    /**
     * Prend la permission persistante en lecture **et** écriture.
     *
     * @param grantUri URI d'arborescence proposée par le sélecteur SAF
     * (avec `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`).
     * @throws SecurityException si l'URI n'est pas persistable telle
     * quelle (drapeau absent, arborescence déjà révoquée).
     */
    fun prendre(grantUri: Uri)

    /**
     * Libère la permission persistante (remplacement du dossier de
     * travail, nettoyage) — sans effet si elle n'est pas détenue.
     *
     * @param grantUri URI d'arborescence à libérer.
     */
    fun liberer(grantUri: Uri)

    /**
     * La permission persistante en écriture est-elle détenue ?
     *
     * @param grantUri URI d'arborescence à interroger.
     * @return `true` si le système rapporte une permission de lecture
     * écrite active sur cette arborescence.
     */
    fun detient(grantUri: Uri): Boolean
}

/**
 * Implémentation réelle sur [ContentResolver].
 *
 * La permission demandée est toujours **lecture + écriture** : le dossier
 * de travail doit être créable, renommable et effaçable par CodeIDE ; une
 * permission en lecture seule n'a aucun usage ici.
 */
internal class ContentResolverPersistableUriPermissions(
    private val resolver: ContentResolver,
) : PersistableUriPermissions {
    override fun prendre(grantUri: Uri) {
        resolver.takePersistableUriPermission(
            grantUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    override fun liberer(grantUri: Uri) {
        resolver.releasePersistableUriPermission(
            grantUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    override fun detient(grantUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == grantUri && permission.isWritePermission
        }
}
