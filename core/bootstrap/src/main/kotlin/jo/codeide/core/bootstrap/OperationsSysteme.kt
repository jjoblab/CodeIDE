package jo.codeide.core.bootstrap

import android.system.ErrnoException
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Opérations système de bas niveau nécessaires à l'installation du
 * bootstrap (prompt compagnon Terminal-1, section 3.4 : permissions
 * d'exécution via `android.system.Os.chmod`, liens symboliques via
 * `Os.symlink`).
 *
 * Port interne au module : l'implémentation Android [OperationsSystemeAndroid]
 * encapsule les appels natifs (invoquables uniquement sur appareil), les
 * tests de `core:bootstrap` doublent ce port par des appels `java.nio`
 * réels (création de liens symboliques effectifs sous Linux de test).
 */
internal interface OperationsSysteme {
    /**
     * Positionne les permissions d'un chemin.
     *
     * @param chemin fichier ou répertoire visé.
     * @param mode mode POSIX complet (ex. `0o700`).
     * @throws IOException si l'opération est refusée par le système y
     * compris après le repli ([java.io.File] ne connaît que le bit
     * d'exécution).
     */
    fun chmod(
        chemin: File,
        mode: Int,
    )

    /**
     * Crée un lien symbolique.
     *
     * @param cible contenu du lien (chemin relatif ou absolu, tel que
     * lu dans `SYMLINKS.txt`).
     * @param lien emplacement du lien à créer.
     * @throws IOException si la création échoue.
     */
    fun creerLienSymbolique(
        cible: String,
        lien: File,
    )
}

/**
 * Implémentation Android de [OperationsSysteme] : `android.system.Os`
 * en primaire, avec repli documenté (section 3.4 du prompt : « repli
 * sur `File.setExecutable` ») lorsque l'appel natif est refusé.
 */
internal class OperationsSystemeAndroid
    @Inject
    constructor() : OperationsSysteme {
        override fun chmod(
            chemin: File,
            mode: Int,
        ) {
            try {
                android.system.Os.chmod(chemin.absolutePath, mode)
            } catch (e: ErrnoException) {
                // Repli documenté (prompt Terminal-1, section 3.4) :
                // File ne porte que le bit d'exécution, suffisant pour
                // lancer les binaires du bootstrap.
                if (!chemin.setExecutable(true, true)) {
                    throw IOException("chmod refusé sur $chemin", e)
                }
            }
        }

        override fun creerLienSymbolique(
            cible: String,
            lien: File,
        ) {
            try {
                android.system.Os.symlink(cible, lien.absolutePath)
            } catch (e: ErrnoException) {
                throw IOException("symlink refusé sur $lien", e)
            }
        }
    }
