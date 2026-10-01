package {{packageName}}

import kotlinx.datetime.Instant

/**
 * {{t:horodatage.kdoc}}
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun analyserInstant(texte: String): Instant = Instant.parse(texte)
