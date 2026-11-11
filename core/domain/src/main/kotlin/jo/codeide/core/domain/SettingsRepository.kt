package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow

/**
 * Source des paramètres applicatifs (étape 4 — couche données).
 *
 * Expose [AppSettings] comme projection immutable de Preferences DataStore
 * (l'implémentation réelle vit dans `core:data` au-dessus de
 * `core:datastore`). La mutation passe par une transformation de l'état
 * **complet** : la source de données applique la transformation de façon
 * atomique (lecture, transformation, réécriture), ce qui évite les
 * mises à jour perdues entre deux lectures concurrentes.
 *
 * Le dossier de travail ([setWorkspace]) bénéficie d'un accès dédié : le
 * prompt en fait un réglage à part entière (section 5.6 — permissions
 * persistantes limitées, héritage par les projets créés dedans).
 *
 * Politique de permissions : ce dépôt **persiste uniquement** le
 * réglage ; la prise/libération de la permission SAF associée reste à la
 * charge du flux appelant (onboarding étape 5, Paramètres étape 6) via
 * [FileSystem] — remplacer un dossier de travail ne libère donc pas
 * encore automatiquement l'ancienne permission (décision reportée aux
 * étapes d'UI, où l'on saura si des projets dépendent encore de l'ancien
 * accès).
 */
public interface SettingsRepository {
    /**
     * Observe les paramètres applicatifs.
     *
     * Contexte d'exécution attendu : flot chaud conservé tant que
     * collecté ; la première émission est la valeur actuelle.
     *
     * @return le flot des paramètres courants.
     */
    public fun observeSettings(): Flow<AppSettings>

    /**
     * Lit les paramètres de manière ponctuelle.
     *
     * @return les paramètres courants, ou l'échec de lecture typé.
     */
    public suspend fun getSettings(): AppResult<AppSettings>

    /**
     * Met à jour les paramètres par transformation atomique de l'état
     * complet.
     *
     * @param update transformation à appliquer à l'état courant.
     * @return le succès, ou l'échec d'écriture typé.
     */
    public suspend fun updateSettings(update: (AppSettings) -> AppSettings): AppResult<Unit>

    /**
     * Définit (ou efface) le dossier de travail.
     *
     * @param location emplacement SAF choisi, ou `null` pour effacer le
     * réglage (l'accueil affichera alors le bandeau de configuration).
     * @return le succès, ou l'échec d'écriture typé.
     */
    public suspend fun setWorkspace(location: StorageLocation?): AppResult<Unit>
}
