package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogVerbosity
import javax.inject.Inject

/**
 * Port d'application de la verbosité de journalisation **à l'exécution**
 * (branchement de l'étape 4, consommé par l'étape 12).
 *
 * L'implémentation vit dans `core:logging` (bascule du niveau minimal du
 * moteur à chaud, sans interruption du pipeline) ; ce port existe pour que
 * le cas d'usage [SetLogVerbosityUseCase] — et donc l'écran Diagnostic —
 * n'ait jamais connaissance des rouages du moteur.
 */
public fun interface LogVerbosityApplier {
    /**
     * Bascule le niveau minimal actif du pipeline.
     *
     * @param verbosity verbosité demandée (`NORMAL` = `INFO`,
     * `DETAILED` = `DEBUG`).
     */
    public fun apply(verbosity: LogVerbosity)
}

/**
 * Cas d'usage « régler la verbosité de journalisation » (section 5.7) —
 * réglage de la visionneuse de diagnostics (étape 12).
 *
 * Deux effets inséparables, dans cet ordre : la verbosité est **persistée**
 * dans les paramètres applicatifs (source de vérité au prochain démarrage,
 * ADR 0011), puis appliquée **immédiatement** au moteur via
 * [LogVerbosityApplier] — sans elle, le réglage ne prendrait effet qu'au
 * prochain lancement.
 *
 * Si la persistance échoue, le moteur n'est pas touché : un niveau actif
 * sans sauvegarde serait un mensonge au redémarrage.
 *
 * Contexte d'exécution attendu : suspendu, hors thread principal (la
 * persistance délègue au DataStore) ; l'application au moteur est
 * instantanée et non bloquante.
 */
public class SetLogVerbosityUseCase
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val applier: LogVerbosityApplier,
    ) {
        /**
         * Persiste puis applique la verbosité demandée.
         *
         * @param verbosity `NORMAL` ou `DETAILED`.
         * @return le succès, ou l'échec d'écriture typé (dans ce cas le moteur
         * garde son niveau courant).
         */
        public suspend operator fun invoke(verbosity: LogVerbosity): AppResult<Unit> {
            val resultat = settings.updateSettings { it.copy(logLevel = verbosity) }
            if (resultat is AppResult.Success) {
                applier.apply(verbosity)
            }
            return resultat
        }
    }
