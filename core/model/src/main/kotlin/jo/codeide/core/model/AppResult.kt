package jo.codeide.core.model

/**
 * Résultat d'une opération applicative, alternative typée aux exceptions
 * qui remonteraient jusqu'à l'UI (règle 6 du prompt maître, ADR 0004).
 *
 * Le domaine et les sources de données renvoient systématiquement des
 * `AppResult` pour les erreurs **attendues** (stockage inaccessible,
 * validation refusée, template invalide…). Les erreurs inattendues peuvent
 * traverser les couches basses en exception ; elles sont alors capturées au
 * plus près de l'UI ou converties en [AppError.Unknown].
 *
 * `CancellationException` n'est jamais capturée dans un `AppResult` : elle
 * est toujours relancée par convention.
 *
 * @param T type de la valeur en cas de succès (covariant).
 */
public sealed interface AppResult<out T> {
    /**
     * Opération réussie.
     *
     * @param value valeur produite par l'opération.
     */
    public data class Success<T>(
        public val value: T,
    ) : AppResult<T>

    /**
     * Opération échouée de manière **attendue et modélisée**.
     *
     * @param error erreur applicative typée, traduisible en message localisé.
     */
    public data class Failure(
        public val error: AppError,
    ) : AppResult<Nothing>
}

/**
 * Extrait la valeur de succès, ou `null` en cas d'échec.
 *
 * Pratique pour les appelants qui disposent déjà d'un traitement d'erreur
 * global ; pour une gestion explicite, préférer `when (result)`.
 *
 * @return la valeur en cas de succès, `null` sinon.
 */
public fun <T> AppResult<T>.getOrNull(): T? =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> null
    }

/**
 * Exécute [block] uniquement si le résultat est un succès, puis renvoie le
 * résultat inchangé pour permettre le chaînage.
 *
 * @param block action à exécuter avec la valeur de succès.
 * @return ce résultat, inchangé.
 */
public fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(value)
    return this
}

/**
 * Exécute [block] uniquement si le résultat est un échec, puis renvoie le
 * résultat inchangé pour permettre le chaînage.
 *
 * @param block action à exécuter avec l'erreur applicative.
 * @return ce résultat, inchangé.
 */
public fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}
