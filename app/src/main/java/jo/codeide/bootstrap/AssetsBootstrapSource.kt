package jo.codeide.bootstrap

import android.content.Context
import android.content.res.AssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.BootstrapAssetsSource
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation applicative de [BootstrapAssetsSource] (prompt
 * Terminal-1, section 3.5) : lecture directe de l'`AssetManager`.
 *
 * Aucune traversée possible : le nom d'asset sert de clé exacte —
 * `AssetManager.open` refuse naturellement les chemins qui sortent du
 * répertoire `assets/`.
 *
 * Constat 2026-09-23 : aucun binaire `aapt2` n'est encore embarqué
 * (l'absence est traduite en `AppError.Bootstrap(AssetAbsent)` par le
 * déployeur, conformément à la consigne « signaler, ne pas contourner »).
 */
@Singleton
internal class AssetsBootstrapSource
    @Inject
    constructor(
        // Seul l'AssetManager est conservé — le contexte sert à
        // l'initialisation (même discipline que les adaptateurs T1).
        @ApplicationContext contexte: Context,
    ) : BootstrapAssetsSource {
        private val gestionnaire: AssetManager = contexte.assets

        // Exemption ciblée (SwallowedException) : l'absence d'asset est un
        // cas **attendu** (aucun binaire aapt2 embarqué à ce jour) — le
        // contrat du port traduit l'exception en `null`, que le déployeur
        // convertit en AppError.Bootstrap(AssetAbsent) : l'exception est le
        // signal de « absent », pas une erreur à propager ni à journaliser.
        @Suppress("SwallowedException")
        override fun ouvrir(nom: String): InputStream? =
            try {
                gestionnaire.open(nom)
            } catch (e: FileNotFoundException) {
                null
            }
    }
