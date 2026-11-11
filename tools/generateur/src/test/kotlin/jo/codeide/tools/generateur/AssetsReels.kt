package jo.codeide.tools.generateur

import java.io.File

/**
 * Localisation des **vrais** assets du dépôt (`app/src/main/assets`) pour
 * les tests qui éprouvent le catalogue embarqué réel (lot L2, C-3) : la
 * recherche remonte depuis le répertoire de travail — échec explicite avec
 * la conduite à tenir si les assets sont absents.
 */
internal object AssetsReels {
    /** Racine des assets embarqués du dépôt CodeIDE. */
    val racine: File by lazy {
        var dossier: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dossier != null) {
            val candidate = File(dossier, "app/src/main/assets/templates")
            if (candidate.isDirectory) {
                return@lazy File(dossier, "app/src/main/assets")
            }
            dossier = dossier.parentFile
        }
        throw IllegalStateException(
            "assets embarqués introuvables (app/src/main/assets) : lancez les tests " +
                "depuis le dépôt CodeIDE — ./gradlew :tools:generateur:test",
        )
    }
}
