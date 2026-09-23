package jo.codeide.core.bootstrap

import android.os.Build
import javax.inject.Inject

/**
 * Capacités d'architecture de l'appareil (prompt compagnon Terminal-1,
 * section 3.4 : « vérifie si d'autres architectures sont publiées, ne
 * suppose pas que seul aarch64 existe »).
 *
 * Constat du 2026-09-23 : seule l'architecture `aarch64` est publiée
 * en release. L'installateur refuse donc proprement (erreur typée
 * [jo.codeide.core.model.AppError.BootstrapReason.ArchitectureNonSupportee])
 * sur un appareil qui ne l'exécute pas, plutôt que de télécharger une
 * archive inutilisable.
 *
 * Port interne au module : doublable en test (Robolectric n'expose pas
 * les ABI réelles d'un appareil ARM).
 */
internal interface CapaciteArchitecture {
    /** L'appareil peut-il exécuter des binaires `arm64-v8a` ? */
    fun supporteAarch64(): Boolean
}

/** Implémentation Android : lecture de `Build.SUPPORTED_ABIS`. */
internal class CapaciteArchitectureBuild
    @Inject
    constructor() : CapaciteArchitecture {
        override fun supporteAarch64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }
