package jo.codeide.ai.providers

import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.model.AppResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** Statut HTTP d'une réponse réussie. */
internal const val HTTP_OK = 200

/** Dernier statut de la plage 2xx. */
internal const val DERNIER_STATUT_SUCCES = 299

/**
 * Exécute un `GET` de liste de modèles et traduit la réponse JSON
 * `{"data": [ … ]}` commune à Anthropic et aux serveurs compatibles
 * OpenAI.
 */
@Suppress("ReturnCount") // erreurs typées successives (ouverture, statut, I/O, parse)
internal suspend fun lireListeModeles(
    transport: AiHttpTransport,
    spec: AiHttpRequestSpec,
    fabrique: (id: String, affiche: String?) -> AiModelInfo,
): AppResult<List<AiModelInfo>> {
    val reponse =
        when (val ouverture = transport.open(spec)) {
            is AppResult.Success -> ouverture.value
            is AppResult.Failure -> return AppResult.Failure(ouverture.error)
        }

    reponse.use { reponseOuverte ->
        if (reponseOuverte.statusCode != HTTP_OK) {
            return AppResult.Failure(
                HttpErrorMapper.fromStatus(
                    reponseOuverte.statusCode,
                    HttpErrorMapper.lireRetryAfter(reponseOuverte.headers["retry-after"]),
                ),
            )
        }
        val texte =
            try {
                reponseOuverte.body.readBytes().decodeToString()
            } catch (reseau: IOException) {
                return AppResult.Failure(HttpErrorMapper.fromIo(reseau))
            }
        return parserListeModeles(texte, fabrique)
    }
}

/**
 * Parse le corps JSON d'une liste de modèles (`{"data": […]}`).
 *
 * @param texte corps de la réponse.
 * @param fabrique construction d'un modèle depuis un élément de `data`.
 * @return liste des modèles, ou `MalformedResponse` si le JSON ou le
 * champ `data` est absent/illisible.
 */
@Suppress("ReturnCount") // corps JSON, champ data, succès — sorties distinctes
internal fun parserListeModeles(
    texte: String,
    fabrique: (id: String, affiche: String?) -> AiModelInfo,
): AppResult<List<AiModelInfo>> {
    val json =
        runCatching { JsonIa.parseur.parseToJsonElement(texte).jsonObject }.getOrNull()
            ?: return AppResult.Failure(HttpErrorMapper.malformed("liste de modèles non JSON"))
    val data =
        json["data"] as? JsonArray
            ?: return AppResult.Failure(HttpErrorMapper.malformed("champ data absent"))

    val modeles =
        data.mapNotNull { element ->
            val objet = element as? JsonObject ?: return@mapNotNull null
            val id = JsonIa.chaine(objet, "id") ?: return@mapNotNull null
            fabrique(id, JsonIa.chaine(objet, "display_name"))
        }
    return AppResult.Success(modeles)
}

/**
 * Test de connexion minimal : un `GET` léger ; tout statut 2xx vaut
 * succès d'authentification.
 */
@Suppress("ReturnCount") // ouverture échouée, succès, échec HTTP — sorties distinctes
internal suspend fun executerPing(
    transport: AiHttpTransport,
    spec: AiHttpRequestSpec,
): AppResult<Unit> {
    val reponse =
        when (val ouverture = transport.open(spec)) {
            is AppResult.Success -> ouverture.value
            is AppResult.Failure -> return AppResult.Failure(ouverture.error)
        }

    reponse.use { reponseOuverte ->
        return if (reponseOuverte.statusCode in HTTP_OK..DERNIER_STATUT_SUCCES) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(
                HttpErrorMapper.fromStatus(
                    reponseOuverte.statusCode,
                    HttpErrorMapper.lireRetryAfter(reponseOuverte.headers["retry-after"]),
                ),
            )
        }
    }
}
