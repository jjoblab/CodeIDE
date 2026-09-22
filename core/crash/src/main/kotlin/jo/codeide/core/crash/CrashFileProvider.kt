package jo.codeide.core.crash

import androidx.core.content.FileProvider

/**
 * FileProvider du processus `:crash` (ADR 0010) : partager l'archive d'un
 * rapport depuis l'écran dédié ne doit jamais ressusciter le processus
 * principal.
 *
 * Sous-classe triviale : le fusionneur de manifestes distingue les
 * fournisseurs par `android:name` — deux instances de la classe
 * `androidx.core.content.FileProvider` (journaux ici, plantages là) ne
 * peuvent coexister sans ce nom dédié.
 */
class CrashFileProvider : FileProvider()
