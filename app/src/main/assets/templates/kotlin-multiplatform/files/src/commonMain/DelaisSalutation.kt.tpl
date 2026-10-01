package {{packageName}}

import kotlinx.coroutines.delay

/**
 * {{t:delai.kdoc}}
 */
suspend fun saluerApresDelai(
    salutation: String,
    delaiMs: Long,
): String {
    delay(delaiMs)
    return salutation
}
