package jo.codeide.core.bootstrap

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.BootstrapReason

/**
 * Échec interne du pipeline d'installation, transportant la raison
 * typée qui deviendra [AppError.Bootstrap] à la frontière de l'état
 * partagé (les composants échouent en exception, l'installateur
 * traduit — jamais d'exception jusqu'à l'UI).
 *
 * [java.util.concurrent.CancellationException] ne transite jamais ici :
 * l'annulation suit la voie coroutine standard et devient l'état
 * `Annulee`.
 */
internal class EchecBootstrap(
    val raison: BootstrapReason,
    val details: String,
    cause: Throwable? = null,
) : Exception(details, cause)
