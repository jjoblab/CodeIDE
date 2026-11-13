package jo.codeide.core.domain

import jo.codeide.core.model.AppResult

/**
 * Port « versions disponibles d'une dépendance Maven » (mission Projet
 * P6, ADR 0097).
 *
 * Interroge les `maven-metadata.xml` des dépôts déclarés pour exposer
 * toutes les versions publiées d'une dépendance. L'UI P6 (onglet
 * « Mises à jour ») compare ces versions à celle déclarée dans le
 * script de build pour proposer des montées de version.
 *
 * Le port vit dans `core:domain` (consommable par des features sans
 * dépendre d'aucun module tooling). L'implémentation de référence
 * `ClientMavenHttp` vit dans `core:data` et utilise `HttpURLConnection`
 * (zéro dépendance externe, ADR 0097 §1).
 *
 * @property group groupe Maven (ex. `org.jetbrains.kotlin`).
 * @property name nom de l'artefact (ex. `kotlin-stdlib`).
 * @property depots URLs des dépôts à interroger, primaire d'abord.
 *         Par défaut : Maven Central + Google Maven. Le premier dépôt
 *         qui répond gagne ; les 404 sont ignorées.
 * @return liste des versions publiées (dernière en dernier), ou
 *         `AppResult.Failure` si AUCUN dépôt n'a répondu (réseau
 *         absent ET cache vide).
 */
public interface MavenVersionesDisponibles {
    public suspend fun versions(
        group: String,
        name: String,
        depots: List<String> = DEPOTS_PAR_DEFAUT,
    ): AppResult<List<String>>

    public companion object {
        /** Dépôts Maven interrogés par défaut (ADR 0097 §2). */
        public val DEPOTS_PAR_DEFAUT: List<String> =
            listOf(
                "https://repo1.maven.org/maven2/",
                "https://dl.google.com/dl/android/maven2/",
            )
    }
}
