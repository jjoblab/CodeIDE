package jo.codeide.core.bootstrap

import android.os.StatFs
import java.io.File
import javax.inject.Inject

/**
 * Sonde d'espace disque libre (prompt compagnon Terminal-1, section
 * 3.4 : échec explicite « espace disque insuffisant » **avant** tout
 * téléchargement).
 *
 * Port interne au module pour rester doublable en test (l'appareil
 * embarque réellement un système de fichiers occupé — le test n'a pas
 * à simuler des dizaines de Gio de manque).
 */
internal interface EspaceDisqueSonde {
    /**
     * Octets réellement disponibles en écriture sous [racine].
     *
     * @param racine répertoire de référence (stockage privé de l'app).
     */
    fun octetsLibres(racine: File): Long
}

/** Implémentation Android : `StatFs` sur le stockage privé. */
internal class EspaceDisqueStatFs
    @Inject
    constructor() : EspaceDisqueSonde {
        override fun octetsLibres(racine: File): Long = StatFs(racine.absolutePath).availableBytes
    }
