package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Cas d'usage « observer les paramètres » (étape 4) — alimente l'apparence
 * immédiate de l'onboarding (étape 5), l'écran Paramètres (étape 6) et
 * le branchement du niveau de journalisation persistant.
 *
 * Contexte d'exécution attendu : flot chaud délégué au dépôt ; collecte
 * depuis le cycle de vie de l'UI, ou depuis une portée applicative pour
 * les consommateurs hors UI (journalisation).
 */
public class ObserveSettingsUseCase
    @Inject
    constructor(
        private val repository: SettingsRepository,
    ) {
        /** @return le flot des paramètres applicatifs courants. */
        public operator fun invoke(): Flow<AppSettings> = repository.observeSettings()
    }

/**
 * Cas d'usage « mettre à jour les paramètres » (étape 4).
 *
 * La transformation reçoit l'état complet et retourne le nouvel état :
 * chaque appelant ne touche qu'aux champs qui le concernent, et la
 * source de données garantit l'atomicité lecture-réécriture.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal ;
 * la transformation elle-même doit être pure et rapide.
 */
public class UpdateSettingsUseCase
    @Inject
    constructor(
        private val repository: SettingsRepository,
    ) {
        /**
         * @param update transformation atomique de l'état complet.
         * @return le succès, ou l'échec d'écriture typé.
         */
        public suspend operator fun invoke(update: (AppSettings) -> AppSettings): AppResult<Unit> =
            repository.updateSettings(update)
    }

/**
 * Cas d'usage « définir le dossier de travail » (étape 4).
 *
 * Appelé par l'onboarding (étape 5) et l'écran Paramètres (étape 6)
 * **après** validation du dossier par SAF (test d'écriture) et prise de
 * la permission persistante — ce cas d'usage ne fait que persister le
 * réglage.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
public class SetWorkspaceUseCase
    @Inject
    constructor(
        private val repository: SettingsRepository,
    ) {
        /**
         * @param location emplacement validé, ou `null` pour effacer le
         * réglage.
         * @return le succès, ou l'échec d'écriture typé.
         */
        public suspend operator fun invoke(location: StorageLocation?): AppResult<Unit> =
            repository.setWorkspace(location)
    }
