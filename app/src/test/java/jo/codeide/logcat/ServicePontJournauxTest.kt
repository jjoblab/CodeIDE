package jo.codeide.logcat

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.applog.ILiaisonJournaux
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.RegistrePontJournaux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import javax.inject.Inject

/**
 * Tests du service Binder du pont de journaux (mission « Exécuter » R5 —
 * critère EXECUTER.md § 6.5 : « rejet d'un émetteur non autorisé (UID)
 * testé ») : la PREUVE d'authenticité est l'UID du noyau
 * (Binder.getCallingUid comparé à l'UID du paquet DÉCLARÉ).
 *
 * Robolectric pilote l'UID/PID appelants par [ShadowBinder] — un faux
 * émetteur (nom de paquet usurpé) est refusé SANS session ; un émetteur
 * légitime ouvre la session, alimente le registre et le débranchement
 * termine la session avec sa raison.
 *
 * Couture : les trames partent du format réel du pont (`L\tniveau\t…`).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class ServicePontJournauxTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    /** Registre du graphe de production — le MÊME que le service. */
    @Inject
    lateinit var registre: RegistrePontJournaux

    private lateinit var liaison: ILiaisonJournaux

    /** UID réel de l'application de test (paquet installé). */
    private var uidLegitime: Int = 0

    @Before
    fun setUp() {
        regleHilt.inject()
        val service = Robolectric.buildService(ServicePontJournaux::class.java).get()
        // Injection Hilt du @AndroidEntryPoint (onCreate du pilote).
        service.onCreate()
        liaison = service.onBind(null) as ILiaisonJournaux
        uidLegitime = service.packageManager.getPackageUid(PAQUET_TEST, 0)
    }

    @Test
    fun `un paquet inconnu est refuse sans session`() {
        ShadowBinder.setCallingUid(uidLegitime)

        liaison.connecter("com.fantome.app", 999, "com.fantome.app", null, VERSION_PROTOCOLE)

        assertTrue("aucune session pour un paquet inconnu", registre.sessions.value.isEmpty())
    }

    @Test
    fun `un uid usurpe est refuse sans session`() {
        // L'appelant PRÉTEND être le paquet de l'IDE — son UID noyau
        // (posé par ShadowBinder) dit autre chose : rejet.
        ShadowBinder.setCallingUid(uidLegitime + DECALAGE_USURPATEUR)

        liaison.connecter(PAQUET_TEST, 999, PAQUET_TEST, null, VERSION_PROTOCOLE)

        assertTrue("aucune session pour un UID usurpé", registre.sessions.value.isEmpty())
    }

    @Test
    fun `une version de protocole inconnue est refusee`() {
        ShadowBinder.setCallingUid(uidLegitime)

        liaison.connecter(PAQUET_TEST, 999, PAQUET_TEST, null, VERSION_PROTOCOLE + 7)

        assertTrue("aucune session pour un protocole inconnu", registre.sessions.value.isEmpty())
    }

    @Test
    fun `un lot sans connexion prealable est ignore`() {
        ShadowBinder.setCallingUid(uidLegitime)
        ShadowBinder.setCallingPid(777)

        // Aucune session fantôme : la donnée externe non fiable est
        // ignorée, jamais acceptée.
        liaison.envoyerLot(mutableListOf(TRAME_LIGNE), 0)

        assertTrue(registre.sessions.value.isEmpty())
        assertTrue(registre.instantane("$PAQUET_TEST/777").isEmpty())
    }

    @Test
    fun `une connexion legitime ouvre une session et les lots alimentent le registre`() {
        ShadowBinder.setCallingUid(uidLegitime)
        ShadowBinder.setCallingPid(4321)

        liaison.connecter(PAQUET_TEST, 4321, PAQUET_TEST, null, VERSION_PROTOCOLE)

        val sessions = registre.sessions.value
        assertEquals(1, sessions.size)
        assertEquals("$PAQUET_TEST/4321", sessions[0].id)
        assertEquals(EtatSessionJournal.VIVANTE, sessions[0].etat)

        liaison.envoyerLot(mutableListOf(TRAME_LIGNE), 0)

        assertEquals(1, registre.instantane("$PAQUET_TEST/4321").size)
        assertEquals("coucou", registre.instantane("$PAQUET_TEST/4321")[0].message)
    }

    @Test
    fun `un lot de pertes seules sans trames compte quand meme`() {
        ShadowBinder.setCallingUid(uidLegitime)
        ShadowBinder.setCallingPid(4321)
        liaison.connecter(PAQUET_TEST, 4321, PAQUET_TEST, null, VERSION_PROTOCOLE)

        liaison.envoyerLot(mutableListOf(), 42)

        assertEquals(42L, registre.sessions.value[0].lignesPerdues)
    }

    @Test
    fun `deconnecter termine la session avec la raison donnee`() {
        ShadowBinder.setCallingUid(uidLegitime)
        ShadowBinder.setCallingPid(4321)
        liaison.connecter(PAQUET_TEST, 4321, PAQUET_TEST, null, VERSION_PROTOCOLE)

        liaison.deconnecter("l'application s'arrête proprement")

        val session = registre.sessions.value.single()
        assertEquals(EtatSessionJournal.TERMINEE, session.etat)
        assertEquals("l'application s'arrête proprement", session.fin?.raison)
    }

    private companion object {
        /** Paquet de l'application de test (installée sous Robolectric). */
        const val PAQUET_TEST = "jo.codeide"

        /** Version du protocole du pont (miroir du service). */
        const val VERSION_PROTOCOLE = 1

        /** Décalage qui rend l'UID appelant étranger au paquet déclaré. */
        const val DECALAGE_USURPATEUR = 4000

        /** Une trame de ligne au format réel du pont (message = reste). */
        const val TRAME_LIGNE = "L\tI\t1000\t4321\t4400\tEtq\tcoucou"
    }
}
