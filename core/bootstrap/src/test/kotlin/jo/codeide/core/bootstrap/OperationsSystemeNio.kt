package jo.codeide.core.bootstrap

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Implémentation `java.nio` de [OperationsSysteme] pour les tests JVM
 * (Linux) : vrais chmod et vrais liens symboliques via l'API fichier
 * standard — la sémantique testée est celle de l'algorithme
 * d'extraction, pas des appels natifs Android (couverts par
 * [OperationsSystemeAndroid] sur appareil).
 */
internal class OperationsSystemeNio : OperationsSysteme {
    override fun chmod(
        chemin: File,
        mode: Int,
    ) {
        val permissions =
            when (mode) {
                448 -> "rwx------"
                493 -> "rwxr-xr-x"
                else -> throw IOException("mode non géré par la doublure de test : $mode")
            }
        Files.setPosixFilePermissions(Paths.get(chemin.absolutePath), PosixFilePermissions.fromString(permissions))
    }

    override fun creerLienSymbolique(
        cible: String,
        lien: File,
    ) {
        Files.createSymbolicLink(Paths.get(lien.absolutePath), Paths.get(cible))
    }
}
