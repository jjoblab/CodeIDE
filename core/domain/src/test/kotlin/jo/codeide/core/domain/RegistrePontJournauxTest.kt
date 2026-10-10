package jo.codeide.core.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du registre des sessions du pont de journaux (mission « Exécuter »
 * R2) — JVM pur : le registre est la pièce que le service Binder alimente
 * et que l'onglet Logcat (R3) lira.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistrePontJournauxTest {
    private val registre = RegistrePontJournaux(capacite = 8)

    private val identite =
        IdentiteSessionJournal(paquet = "com.exemple.app", pid = 4321, nomProcessus = "com.exemple.app")

    private fun trameLigne(
        message: String,
        horodatage: Long = 1_000L,
    ): String = "L\tI\t$horodatage\t4321\t4400\tEtq\t$message"

    @Test
    fun `l ouverture d une session la rend vivante et consultable`() {
        val id = registre.ouvrirSession(identite)

        val sessions = registre.sessions.value
        assertEquals(1, sessions.size)
        assertEquals(id, sessions[0].id)
        assertEquals(EtatSessionJournal.VIVANTE, sessions[0].etat)
        assertEquals(0, sessions[0].nombreLignes)
        assertEquals("com.exemple.app/4321", id)
    }

    @Test
    fun `les lots de trames alimentent tampon compteur et flux`() =
        runTest {
            val id = registre.ouvrirSession(identite)
            val lots = ArrayList<List<LigneJournal>>()
            // L'abonné s'installe AVANT les émissions : un flux partagé sans
            // rejeu ne redonne pas le passé (l'instantané est là pour ça).
            val abonnement = registre.lignes(id).onEach { lots.add(it) }.launchIn(this)
            advanceUntilIdle()

            val produites =
                registre.ajouterTrames(
                    id,
                    listOf(trameLigne("un"), trameLigne("deux"), "X\t3\tmanque de mémoire"),
                    lignesPerduesAnnoncees = 0,
                )

            advanceUntilIdle()
            assertEquals(2, produites)
            assertEquals(2, registre.instantane(id).size)
            assertEquals("un", registre.instantane(id)[0].message)
            assertEquals(2, registre.sessions.value[0].nombreLignes)
            assertEquals("un lot émis", 1L, lots.size.toLong())
            // La trame X documente la sortie précédente de la session.
            assertEquals("manque de mémoire", registre.sessions.value[0].sortiePrecedente)
            abonnement.cancel()
        }

    @Test
    fun `les pertes annoncees par le pont s accumulent sans faux silence`() {
        val id = registre.ouvrirSession(identite)

        registre.ajouterTrames(id, emptyList(), lignesPerduesAnnoncees = 42)

        assertEquals(42, registre.sessions.value[0].lignesPerdues)
        registre.ajouterTrames(id, emptyList(), lignesPerduesAnnoncees = 7)
        assertEquals(49, registre.sessions.value[0].lignesPerdues)
    }

    @Test
    fun `la fin de session garde les lignes et marque la raison`() {
        val id = registre.ouvrirSession(identite)
        registre.ajouterTrames(id, listOf(trameLigne("dernière ligne")), 0)

        registre.terminerSession(id, "le processus s'est arrêté")

        val session = registre.sessions.value[0]
        assertEquals(EtatSessionJournal.TERMINEE, session.etat)
        assertNotNull(session.fin)
        assertEquals("le processus s'est arrêté", session.fin?.raison)
        // Le tampon SURVIT : la session passée reste consultable.
        assertEquals(1, registre.instantane(id).size)
    }

    @Test
    fun `les trames non conformes sont ignorees sans compter de lignes`() {
        val id = registre.ouvrirSession(identite)

        val produites = registre.ajouterTrames(id, listOf("n'importe quoi", "", "L\tZ\t0\t1\t2\tE\tm"), 0)

        assertEquals(0, produites)
        assertEquals(0, registre.sessions.value[0].nombreLignes)
    }

    @Test
    fun `un lot d un process non connecte est ignore`() {
        // JAMAIS de session fantôme : sans connexion préalable validée
        // (UID côté service), rien n'entre dans le registre.
        val produites = registre.ajouterTrames("inconnu/999", listOf(trameLigne("fantôme")), 0)

        assertEquals(0, produites)
        assertTrue(registre.sessions.value.isEmpty())
        assertEquals(0, registre.instantane("inconnu/999").size)
    }

    @Test
    fun `le tampon borne garde les plus recentes et compte les pertes d affichage`() =
        runTest {
            val id = registre.ouvrirSession(identite)
            // Un abonné EXISTE mais est lent (le fil de test est occupé par
            // la salve) : le tampon d'émissions (512 lots) déborde, les
            // lignes non émises sont COMPTÉES, jamais perdues en silence.
            // L'abonné s'installe avant la salve, mais le fil de test
            // reste OCCUPÉ par la salve : il ne draine rien — le tampon
            // d'émissions déborde, exactement le scénario éprouvé.
            val abonnement = registre.lignes(id).launchIn(this)
            advanceUntilIdle() // l'abonné s'installe avant la salve

            for (numero in 0 until 600) {
                registre.ajouterTrames(id, listOf(trameLigne("ligne-$numero", horodatage = numero.toLong())), 0)
            }

            // Le TAMPON garde les 8 plus récentes (capacité du test).
            assertEquals(8, registre.instantane(id).size)
            assertEquals("ligne-599", registre.instantane(id).last().message)
            // Les 600 lignes sont comptées ; 512 lots partis, 88 comptés
            // en pertes d'affichage (600 − 512 = 88, déterministe).
            val session = registre.sessions.value[0]
            assertEquals(600, session.nombreLignes)
            assertEquals(88L, session.lignesPerdues)
            abonnement.cancel()
        }

    @Test
    fun `plusieurs processus coexistent sessions distinctes`() {
        val principal = registre.ouvrirSession(identite)
        val secondaire =
            registre.ouvrirSession(identite.copy(pid = 5000, nomProcessus = "com.exemple.app:service"))

        registre.ajouterTrames(principal, listOf(trameLigne("du principal")), 0)
        registre.ajouterTrames(secondaire, listOf(trameLigne("du service")), 0)

        assertEquals(2, registre.sessions.value.size)
        assertEquals(1, registre.instantane(principal).size)
        assertEquals(1, registre.instantane(secondaire).size)
    }

    @Test
    fun `la reouverture du meme identite rouvre sans rien jeter`() {
        val id = registre.ouvrirSession(identite)
        registre.ajouterTrames(id, listOf(trameLigne("avant veille")), 0)
        registre.terminerSession(id, "l'IDE a disparu")

        // Le pont se REconnecte (IDE de retour) : même pid, même paquet.
        val idRouvert = registre.ouvrirSession(identite)

        assertEquals(id, idRouvert)
        val session = registre.sessions.value[0]
        assertEquals(EtatSessionJournal.VIVANTE, session.etat)
        assertNull(session.fin)
        // Les lignes d'avant la déconnexion ont survécu (promesse ADR 0103
        // §5 : vidées à la reconnexion, pas perdues).
        assertEquals(1, registre.instantane(id).size)
    }
}
