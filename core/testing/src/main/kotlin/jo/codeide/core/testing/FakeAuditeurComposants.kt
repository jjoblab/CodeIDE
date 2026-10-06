package jo.codeide.core.testing

import jo.codeide.core.domain.AuditeurComposants
import jo.codeide.core.domain.InstalledComponent

/**
 * Doublure de [AuditeurComposants] (refonte E5, ADR 0090) : tailles
 * semables par identifiant de composant, `null` par défaut (absent du
 * disque) ; le JDK est semable séparément.
 */
public class FakeAuditeurComposants : AuditeurComposants {
    /** Tailles semées par identifiant de composant (absent → `null`). */
    public val tailles: MutableMap<String, Long> = mutableMapOf()

    /** Taille semée du JDK — `null` par défaut (absent). */
    public var tailleJdk: Long? = null

    override suspend fun tailleOctets(composant: InstalledComponent): Long? = tailles[composant.id]

    override suspend fun tailleJdkOctets(): Long? = tailleJdk
}
