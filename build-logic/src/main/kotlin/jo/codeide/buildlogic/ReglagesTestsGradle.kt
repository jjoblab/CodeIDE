package jo.codeide.buildlogic

import java.time.Duration
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

/**
 * Réglages communs anti-hang et d'observabilité des tâches de tests
 * (incident CI du 2026-10-18 : `:ai:providers:test` figé des heures sur
 * GitHub Actions).
 *
 * Deux ceintures :
 * 1. **Timeout de tâche** (10 min) : une tâche de tests qui dépasse est
 *    tuée par Gradle — échec explicite au lieu d'un job bloqué des heures.
 *    Le plafond est très au-dessus des durées réelles (≤ 2 min par module
 *    en CI) ; il n'agit qu'en cas pathologique.
 * 2. **testLogging** : échecs et sauts tracés par défaut (format complet),
 *    départs des tests sur demande via `-Dtest.events.started=true`
 *    (diagnostic de hang : le dernier test « STARTED » dans les logs CI
 *    désigne le coupable).
 */
internal fun Test.reglagesAntiHang() {
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        if (System.getProperty("test.events.started") == "true") {
            events(TestLogEvent.STARTED)
        }
        exceptionFormat = TestExceptionFormat.FULL
    }
}
