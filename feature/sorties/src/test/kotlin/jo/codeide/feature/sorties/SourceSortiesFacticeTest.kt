package jo.codeide.feature.sorties

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la source factice (lot L2) : les séquences du banc livrent
 * leurs VOLUMES exacts (S1 = 100 000 lignes dont la phase lente ; S2 =
 * tampon plein d'un bloc avec ses 12 480 lignes chaudes ; S6 = cadence
 * 10 000 lignes/s), le contenu est DÉTERMINISTE (deux émissions se
 * rejouent à l'identique) et l'armement honnête (rien d'armé → rien
 * d'émis, capacité de fenêtre fidèle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSortiesFacticeTest {
    private val source = SourceSortiesFactice()

    @Test
    fun `rien d'armé - rien d'émis et capacité de fenêtre par défaut`() = runTest {
        assertNull(source.sequenceArmee.value)
        assertEquals(FENETRE_DEFAUT, source.capaciteFenetre())
    }

    @Test
    fun `S1 débit ingestion - 100 000 lignes en deux phases lente puis rapide`() = runTest {
        source.armer(SequenceBanc.S1_DEBIT_INGESTION)
        assertEquals(FENETRE_BANC, source.capaciteFenetre())
        val lots = ArrayList<List<jo.codeide.core.domain.LigneSortie>>()
        val travail = launch { source.lots().collect { lot -> lots.add(lot) } }
        advanceUntilIdle()
        travail.cancel()
        val total = lots.sumOf { it.size }
        assertEquals(100_000, total)
        // Phase lente : 20 lots de 500 lignes.
        assertEquals(20, lots.take(20).size)
        assertTrue(lots.take(20).all { it.size == 500 })
    }

    @Test
    fun `S2 tampon plein - un bloc avec exactement 12 480 lignes chaudes`() = runTest {
        source.armer(SequenceBanc.S2_TAMPON_PLEIN)
        val lignes = ArrayList<jo.codeide.core.domain.LigneSortie>()
        val travail = launch { source.lots().collect { lot -> lignes.addAll(lot) } }
        advanceUntilIdle()
        travail.cancel()
        assertEquals(100_000, lignes.size)
        // Une ligne sur huit est chaude, hors les 20 dernières.
        val chaudes = lignes.count {
            it.niveau == jo.codeide.core.domain.NiveauSortie.ERREUR ||
                it.niveau == jo.codeide.core.domain.NiveauSortie.AVERTISSEMENT
        }
        assertEquals(12_480, chaudes)
    }

    @Test
    fun `le contenu est déterministe - deux émissions identiques`() = runTest {
        val empreintes = ArrayList<List<String>>()
        repeat(2) {
            source.armer(SequenceBanc.S6_PAUSE_REPRISE)
            val messages = ArrayList<String>()
            val travail = launch {
                source.lots().take(20).collect { lot -> messages.addAll(lot.map { it.message }) }
            }
            advanceUntilIdle()
            travail.cancel()
            empreintes.add(messages)
            source.desarmer()
        }
        assertEquals(empreintes[0], empreintes[1])
        assertTrue(empreintes[0].isNotEmpty())
    }

    @Test
    fun `S6 cadence - 50 ms entre lots de 500 (10 000 lignes s)`() = runTest {
        source.armer(SequenceBanc.S6_PAUSE_REPRISE)
        val cadences = ArrayList<Long>()
        var instantPrecedent = 0L
        val travail = launch {
            source.lots().take(4).collect { lot ->
                val instant = testScheduler.currentTime
                cadences.add(instant - instantPrecedent)
                instantPrecedent = instant
            }
        }
        advanceUntilIdle()
        travail.cancel()
        // Premier lot sans délai, puis 50 ms entre lots de 500 lignes.
        assertEquals(0L, cadences[0])
        assertTrue(cadences.drop(1).all { it >= 50L })
    }
}
