package jo.codeide.core.domain

import javax.inject.Qualifier

/**
 * Qualifier Hilt du système de fichiers du **stockage privé de
 * l'application** (étape 31, explorateur v2) : `filesDir`, `cacheDir`,
 * `codeCacheDir`, `databases` et `shared_prefs` exposés à travers le port
 * [FileSystem] par l'adaptateur `FileSystemPrive` de `core:storage`.
 *
 * Sans ce qualifier, l'injection par défaut désigne l'implémentation SAF
 * des **projets** (ADR 0003) : les deux arbres de l'explorateur (Projet /
 * Privé) vivent derrière le même port, distingués par ce seul marqueur.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
public annotation class FileSystemPrive
