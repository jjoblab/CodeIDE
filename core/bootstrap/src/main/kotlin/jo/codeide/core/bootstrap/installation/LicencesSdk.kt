package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Licences du SDK Android (§ 12.5, E4 — ADR 0089) : l'application écrit
 * **elle-même** les fichiers du dossier `licenses/` de la racine du SDK,
 * uniquement après l'acceptation explicite de l'utilisateur (la phase
 * `ANDROID_SDK` refuse de démarrer sans elle, ADR 0087).
 *
 * Contenu : un fichier `<nom>-license` par licence, dont la première ligne
 * est le **hachage** attendu par `sdkmanager` (les lignes suivantes, la
 * licence elle-même, sont optionnelles — seul le hachage est lu côté
 * outils). Les hachages sont les valeurs historiques stables des licences
 * du SDK Android (inchangées par les révisions de `sdkmanager` depuis
 * leur introduction) — **non vérifiés sur un appareil** dans cet
 * environnement (règle 2 du cahier) : documenté dans l'ADR 0089, avec le
 * scénario de contre-vérification (`sdkmanager --licenses` sur appareil)
 * consigné pour `docs/TESTS_MANUELS.md`. Une divergence constatée sur
 * appareil sera signalée au propriétaire (protocole § 12) : aucun
 * contournement silencieux.
 */
internal object LicencesSdk {
    /**
     * Licences écrites : l'identifiant du fichier → hachage attendu.
     * `android-sdk-license` est la licence exigée par les composants du
     * plan (build-tools, plateformes, platform-tools) ;
     * `android-sdk-preview-license` couvre les canaux preview (inutilisés
     * par l'app : canal `stable` seul, § 12.2 — posée par prudence pour
     * les outils qui la réclament à l'exploration).
     */
    internal val LICENCES: Map<String, String> =
        mapOf(
            "android-sdk-license" to "24333f8a63b6825ea9c5514f83c2829b004d1fee",
            "android-sdk-preview-license" to "84831b9409646a918e30573bab4c9c91346d8abd",
        )

    /** Dossier `licenses/` sous la racine du SDK. */
    internal fun dossier(racineSdk: File): File = File(racineSdk, "licenses")

    /**
     * Écrit les fichiers de licence (atomique par fichier : temporaire
     * puis renommage) — idempotent : un fichier déjà conforme n'est pas
     * réécrit.
     *
     * @param racineSdk racine du SDK Android (`home/android-sdk`).
     * @param dispatchers répartiteur pour l'écriture hors thread principal.
     * @return `true` si toutes les licences sont présentes et conformes
     * après l'appel.
     */
    internal suspend fun ecrire(
        racineSdk: File,
        dispatchers: DispatcherProvider,
    ): Boolean =
        withContext(dispatchers.io) {
            val dossier = dossier(racineSdk)
            if (!dossier.isDirectory) dossier.mkdirs()
            LICENCES.forEach { (nom, hachage) ->
                val fichier = File(dossier, nom)
                if (fichier.isFile && fichier.readText().trim() == hachage) return@forEach
                val temporaire = File(dossier, "$nom.tmp")
                temporaire.bufferedWriter().use { it.write(hachage + "\n") }
                if (fichier.isFile) fichier.delete()
                if (!temporaire.renameTo(fichier)) temporaire.delete()
            }
            verifiees(racineSdk)
        }

    /**
     * Les licences sont-elles présentes et conformes (hachage exact) ?
     * Contrôle léger : lecture seule, sans exécution.
     */
    internal fun verifiees(racineSdk: File): Boolean =
        LICENCES.all { (nom, hachage) ->
            val fichier = File(dossier(racineSdk), nom)
            fichier.isFile && fichier.readText().trim() == hachage
        }
}
