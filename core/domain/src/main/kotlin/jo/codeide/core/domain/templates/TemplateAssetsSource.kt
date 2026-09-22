package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppResult

/**
 * Port d'accès aux assets de templates embarqués (étape 8 — section 11).
 *
 * Le moteur est du Kotlin JVM pur (testable avec un faux) mais les manifestes,
 * fichiers `.tpl` et licences vivent dans les assets Android : ce port isole
 * l'accès. L'implémentation de référence (`AssetTemplateAssetsSource`, module
 * `app`) lit `assets/templates/` et `assets/licenses/` via `AssetManager`.
 *
 * Sémantique : les chemins sont **relatifs au répertoire du modèle** pour
 * [readTemplateFile] et à `assets/licenses/` pour [readLicenseFile] ; toute
 * tentative de sortir du répertoire (`..`, absolu) est refusée par
 * l'implémentation. Les erreurs sont typées comme celles du [jo.codeide.core.domain.FileSystem].
 */
public interface TemplateAssetsSource {
    /**
     * Liste les répertoires candidats sous `assets/templates/`.
     *
     * @return les noms de répertoires (sans chemin), ou l'échec typé.
     */
    public suspend fun listTemplateDirectories(): AppResult<List<String>>

    /**
     * Lit un fichier du répertoire d'un modèle.
     *
     * @param templateId identifiant (nom de répertoire) du modèle.
     * @param cheminRelatif chemin relatif **dans** le répertoire du modèle.
     * @return les octets bruts, ou l'échec typé (`NotFound` si absent).
     */
    public suspend fun readTemplateFile(
        templateId: String,
        cheminRelatif: String,
    ): AppResult<ByteArray>

    /**
     * Lit un fichier de licence de référence (`assets/licenses/`).
     *
     * @param nomFichier nom du fichier (ex. `mit.txt`), sans chemin.
     * @return les octets bruts, ou l'échec typé.
     */
    public suspend fun readLicenseFile(nomFichier: String): AppResult<ByteArray>
}

/**
 * Version du générateur inscrite dans `.codeide/project.json`
 * (forme `CodeIDE <version>`, section 11) — injectée par `app` depuis la
 * source unique de version, testable côté domaine.
 */
public interface GeneratorVersion {
    /** Version lisible, ex. « CodeIDE 0.9.0 ». */
    public val value: String
}
