package jo.codeide.core.testing

import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.ToolchainLocator
import java.io.File

/**
 * [ProcessEnvironmentProvider](jo.codeide.core.domain.ProcessEnvironmentProvider)
 * en mémoire : le test sème la carte complète servie aux sous-processus.
 */
public class FakeProcessEnvironmentProvider : ProcessEnvironmentProvider {
    /** Carte retournée par `baseEnvironment()` (modifiable par le test). */
    public val environnement: MutableMap<String, String> = mutableMapOf()

    /** Sème des variables par-dessus la carte courante. */
    public fun semer(variables: Map<String, String>) {
        environnement.putAll(variables)
    }

    public override fun baseEnvironment(): Map<String, String> = environnement.toMap()
}

/**
 * [ToolchainLocator](jo.codeide.core.domain.ToolchainLocator) en
 * mémoire : chaque outil est semé par le test ; les interrogations
 * booléennes dérivent des mêmes valeurs (une seule source par outil).
 *
 * Exemption ciblée (TooManyFunctions) : le contrat simulé compte 13
 * méthodes (prompt Terminal-1, section 2.2) — même exemption que
 * l'implémentation de référence (ToolchainBootstrap, étape T1).
 */
@Suppress("TooManyFunctions")
public class FakeToolchainLocator : ToolchainLocator {
    /** Racine du JDK (`javaHome()`), ou `null` si non installé. */
    public var jdk: File? = null

    /** Racine de la distribution Gradle (`gradleHome()`), ou `null`. */
    public var gradle: File? = null

    /** Racine du SDK Android (`androidHome()`), ou `null`. */
    public var sdk: File? = null

    /** `android.jar` de plateforme (`androidJar()`), ou `null`. */
    public var androidJar: File? = null

    /** Binaire `aapt2` déployé (`aapt2Binary()`), ou `null`. */
    public var aapt2: File? = null

    /** Racine du `GRADLE_USER_HOME` (toujours définie au port). */
    public var gradleUserHomeFichier: File = File(System.getProperty("java.io.tmpdir", "."), "gradle-fake")

    /** Distribution du cache wrapper retournée par `findCachedGradleDistribution`. */
    public var distributionCache: File? = null

    /** Chemin du shell par défaut retourné par `defaultShell()`. */
    public var shell: String = "/bin/sh"

    /** Valeur de `isBootstrapInstalled()`. */
    public var bootstrapInstalle: Boolean = false

    public override fun isBootstrapInstalled(): Boolean = bootstrapInstalle

    public override fun isJdkInstalled(): Boolean = jdk != null

    public override fun javaHome(): File? = jdk

    public override fun isGradleInstalled(): Boolean = gradle != null

    public override fun gradleHome(): File? = gradle

    public override fun isAndroidSdkInstalled(): Boolean = sdk != null

    public override fun androidHome(): File? = sdk

    public override fun androidJar(): File? = androidJar

    public override fun aapt2Binary(): File? = aapt2

    public override fun isAapt2Installed(): Boolean = aapt2?.canExecute() == true

    public override fun gradleUserHome(): File = gradleUserHomeFichier

    public override fun findCachedGradleDistribution(version: String?): File? = distributionCache

    public override fun defaultShell(): String = shell
}
