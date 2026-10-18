package jo.codeide.feature.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.DiagnosticEditeur
import jo.codeide.core.domain.GraviteDiagnostic
import jo.codeide.core.domain.RegistrePortEditeurActif
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests du pont éditeur → IA (mission IA, lot IA-5, ADR 0126) : la
 * consigne de la barre part avec envoi immédiat ; le pré-remplissage
 * Problèmes/Console n'envoie JAMAIS seul ; la sortie est bornée ; le
 * diagnostic est retrouvé par identifiant (repli sans chemin) ; aucun
 * signal sans port, sans diagnostic, sans texte.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PontIaEditeurImplTest {
    private lateinit var contexte: Context
    private lateinit var registre: RegistrePortEditeurActif
    private lateinit var canal: CanalSignauxIa
    private lateinit var pont: PontIaEditeurImpl

    @Before
    fun preparer() {
        contexte = ApplicationProvider.getApplicationContext<Application>()
        registre = RegistrePortEditeurActif()
        canal = CanalSignauxIa()
        pont = PontIaEditeurImpl(contexte, registre, canal)
    }

    @Test
    fun `la consigne de la barre part avec envoi immédiat`() =
        runTest {
            pont.envoyerConsigne("Explique ce code")
            val signal = canal.signal.value
            assertTrue(signal is SignalIaExterne.Consigne)
            val consigne = signal as SignalIaExterne.Consigne
            assertEquals("Explique ce code", consigne.texte)
            assertTrue(consigne.envoiImmediat)
        }

    @Test
    fun `une consigne vide ne publie rien`() =
        runTest {
            pont.envoyerConsigne("   ")
            assertNull(canal.signal.value)
        }

    @Test
    fun `expliquer publie un pré-remplissage borné, jamais auto-envoyé`() =
        runTest {
            val sortie = (1..400).joinToString("\n") { "ligne $it" }
            pont.expliquer(sortie)
            val signal = canal.signal.value as SignalIaExterne.Consigne
            assertTrue(!signal.envoiImmediat)
            assertTrue(signal.texte.contains("ligne 400"))
            assertTrue(!signal.texte.contains("ligne 150\n"))
        }

    @Test
    fun `une sortie vide ne publie rien`() =
        runTest {
            pont.expliquer("  \n  ")
            assertNull(canal.signal.value)
        }

    @Test
    fun `sans port d'éditeur, corrigerDiagnostic ne publie rien`() =
        runTest {
            pont.corrigerDiagnostic("src/Main.kt", "javac:12:5")
            assertNull(canal.signal.value)
        }

    @Test
    fun `le diagnostic est retrouvé par identifiant et pré-remplit sans envoyer`() =
        runTest {
            registre.enregistrer(
                object : jo.codeide.core.domain.PortEditeurActif {
                    override val cheminRelatifFichier: String = "src/Main.kt"

                    override val selection: jo.codeide.core.domain.SelectionEditeur? = null

                    override val diagnostics: List<DiagnosticEditeur> =
                        listOf(
                            DiagnosticEditeur(
                                identifiant = "javac:12:5",
                                gravite = GraviteDiagnostic.ERREUR,
                                cheminRelatif = "src/Main.kt",
                                ligne = 12,
                                colonne = 5,
                                message = "';' attendu",
                            ),
                        )

                    override fun revision(): Long = 1L

                    override fun appliquerRemplacement(
                        debut: Int,
                        fin: Int,
                        texte: String,
                    ) = jo.codeide.core.model.AppResult
                        .Success(Unit)
                },
            )

            pont.corrigerDiagnostic("src/Main.kt", "javac:12:5")

            val signal = canal.signal.value as SignalIaExterne.Consigne
            assertTrue(!signal.envoiImmediat)
            assertTrue(signal.texte.contains("src/Main.kt"))
            assertTrue(signal.texte.contains("';' attendu"))
        }

    @Test
    fun `un diagnostic inconnu ne publie rien`() =
        runTest {
            registre.enregistrer(
                object : jo.codeide.core.domain.PortEditeurActif {
                    override val cheminRelatifFichier: String = "src/Main.kt"

                    override val selection: jo.codeide.core.domain.SelectionEditeur? = null

                    override val diagnostics: List<DiagnosticEditeur> = emptyList()

                    override fun revision(): Long = 1L

                    override fun appliquerRemplacement(
                        debut: Int,
                        fin: Int,
                        texte: String,
                    ) = jo.codeide.core.model.AppResult
                        .Success(Unit)
                },
            )

            pont.corrigerDiagnostic("src/Main.kt", "javac:99:1")

            assertNull(canal.signal.value)
        }

    @Test
    fun `réessayer publie le signal de régénération`() =
        runTest {
            pont.reessayer()
            assertTrue(canal.signal.value is SignalIaExterne.Regenerer)
        }
}
