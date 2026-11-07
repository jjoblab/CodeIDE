package jo.codeide.tooling.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de l'analyse des lignes de stderr de l'orchestrateur (INC-40/41,
 * volet C du prompt « retard d'affichage des sorties de build » §6.5) :
 * le niveau du préfixe « [gradle-server] [NIVEAU] » devient la sévérité
 * APPLICATIVE (avant le correctif, TOUT partait en WARN — l'export de
 * logs du propriétaire montrait « [gradle-server] [INFO] handshake
 * accepté… » au niveau WARN), le préfixe redondant est retiré, les
 * traces JVM partent en erreur et les avertissements SLF4J du shadowJar
 * sans fournisseur sont filtrés.
 */
class AnalyseLigneServeurTest {
    @Test
    fun `le niveau du serveur devient la severite applicative - prefixe retire`() {
        assertEquals(
            LigneServeurAnalysee(NiveauLigneServeur.INFO, "handshake accepté (protocole 10)"),
            analyserLigneServeur("[gradle-server] [INFO] handshake accepté (protocole 10)"),
        )
        assertEquals(
            LigneServeurAnalysee(NiveauLigneServeur.AVERTISSEMENT, "file d'écriture à 32 frames"),
            analyserLigneServeur("[gradle-server] [WARN] file d'écriture à 32 frames"),
        )
        assertEquals(
            LigneServeurAnalysee(NiveauLigneServeur.ERREUR, "orchestrateur mort"),
            analyserLigneServeur("[gradle-server] [ERROR] orchestrateur mort"),
        )
        assertEquals(
            LigneServeurAnalysee(NiveauLigneServeur.DEBUG, "trame détaillée"),
            analyserLigneServeur("[gradle-server] [DEBUG] trame détaillée"),
        )
    }

    @Test
    fun `une ligne sans prefixe reste info - sauf trace jvm en erreur`() {
        assertEquals(
            LigneServeurAnalysee(NiveauLigneServeur.INFO, "Starting Gradle Daemon"),
            analyserLigneServeur("Starting Gradle Daemon"),
        )
        assertEquals(
            LigneServeurAnalysee(
                NiveauLigneServeur.ERREUR,
                "Exception in thread \"main\" java.lang.NullPointerException",
            ),
            analyserLigneServeur("Exception in thread \"main\" java.lang.NullPointerException"),
        )
        assertEquals(
            LigneServeurAnalysee(NiveauLigneServeur.ERREUR, "\tat jo.codeide.tooling.server.ServerMain.kt(42)"),
            analyserLigneServeur("\tat jo.codeide.tooling.server.ServerMain.kt(42)"),
        )
        assertEquals(
            LigneServeurAnalysee(NiveauLigneServeur.ERREUR, "Caused by: java.io.IOException"),
            analyserLigneServeur("Caused by: java.io.IOException"),
        )
    }

    @Test
    fun `les avertissements slf4j du shadowjar sont filtres - INC-41`() {
        assertNull(analyserLigneServeur("SLF4J(W): No SLF4J providers were found."))
        assertNull(analyserLigneServeur("SLF4J(W): Defaulting to no-operation (NOP) logger implementation"))
        assertNull(
            analyserLigneServeur("SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details."),
        )
    }
}
