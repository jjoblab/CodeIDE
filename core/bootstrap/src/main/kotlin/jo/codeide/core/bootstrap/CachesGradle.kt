package jo.codeide.core.bootstrap

import java.io.File

/**
 * Retrouve les distributions Gradle mises en cache par le wrapper (prompt
 * compagnon Terminal-1, section 3.1 ; ADR 0032).
 *
 * Le Gradle réellement utilisé par un projet est en général dans
 * `~/.gradle/wrapper/dists/gradle-<version>-bin/<hash>/gradle-<version>/`,
 * pas dans `$PREFIX/opt/` : sans cette recherche, le wrapper retélécharge
 * une distribution déjà présente sur l'appareil.
 */
internal object CachesGradle {
    /**
     * Retrouve une distribution Gradle mise en cache par le wrapper.
     *
     * @param gradleUserHome répertoire de cache Gradle (`home/.gradle`).
     * @param version version exacte recherchée, ou `null` pour la plus
     * haute disponible.
     * @return la racine de la distribution **valide** (marqueur de
     * distribution complète), ou `null`.
     */
    internal fun trouverDistribution(
        gradleUserHome: File,
        version: String?,
    ): File? {
        val dists = File(gradleUserHome, "wrapper/dists")
        if (!dists.isDirectory) return null

        return MarqueursOutils
            .listerRepertoires(dists)
            .filter { nomDistribution -> version == null || nomDistribution.name.startsWith("gradle-$version-") }
            .flatMap { nomDistribution -> MarqueursOutils.listerRepertoires(nomDistribution) }
            .flatMap { empreinte -> MarqueursOutils.listerRepertoires(empreinte) }
            .filter { racine -> racine.name.startsWith("gradle-") }
            .filter(MarqueursOutils::estDistributionGradleValide)
            .maxWithOrNull(MarqueursOutils.comparateurVersions)
    }
}
