package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la dérivation REGROUPÉE du Logcat (spec SORTIES § 5.4 M5, lot
 * L5) — répétitions ×N (identité message + étiquette + niveau), repli des
 * traces d'exception (tête suivie de cadres, causes relevées), ancres
 * défensives de [TracesLogcat], couverture stricte « chaque ligne une
 * fois » (critère § 10.1.1).
 */
class RegroupementLogcatTest {

    /** Une ligne LOGCAT (le seul flux qui porte des traces). */
    private fun ligne(message: String, etiquette: String? = "App", niveau: NiveauSortie = NiveauSortie.INFO) =
        LigneSortie(
            source = SourceSortie.LOGCAT,
            message = message,
            horodatageMs = 1L,
            niveau = niveau,
            etiquette = etiquette,
        )

    /** La couverture stricte : groupes + hors-groupes couvrent chaque
     *  position EXACTEMENT une fois (critère § 10.1.1). */
    private fun assertCouverture(etat: EtatRegroupementLogcat, total: Int) {
        val couverts = ArrayList<Int>()
        etat.groupes.forEach { groupe ->
            assertTrue("bornes incohérentes $groupe", groupe.debut <= groupe.fin)
            for (position in groupe.debut..groupe.fin) couverts.add(position)
        }
        couverts.addAll(etat.horsGroupes)
        assertEquals("couverture exacte", (0 until total).toList(), couverts.sorted())
    }

    // ----------------------------------------------------------- ancres

    @Test
    fun `une déclaration d'exception est une tête de trace`() {
        assertTrue(TracesLogcat.estTeteTrace("java.lang.RuntimeException: boom"))
        assertTrue(TracesLogcat.estTeteTrace("RuntimeException: boom"))
        assertTrue(TracesLogcat.estTeteTrace("FATAL EXCEPTION: main"))
        assertTrue(
            TracesLogcat.estTeteTrace(
                LignesPile.PREFIXE_EXCEPTION + ": java.lang.IllegalStateException",
            ),
        )
    }

    @Test
    fun `un message ordinaire n'est pas une tête de trace`() {
        assertFalse(TracesLogcat.estTeteTrace("Application démarrée"))
        assertFalse(TracesLogcat.estTeteTrace("    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"))
        assertFalse(TracesLogcat.estTeteTrace("Caused by: java.lang.Error: cause"))
    }

    @Test
    fun `cadres et causes sont reconnus défensivement`() {
        assertTrue(TracesLogcat.estCadre("    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"))
        assertFalse(TracesLogcat.estCadre("    at com.exemple.MainActivity.onCreate(Native Method)"))
        assertTrue(TracesLogcat.estCause("Caused by: java.lang.IllegalStateException: état"))
        assertTrue(TracesLogcat.estCause("Suppressed: java.lang.Exception: fermeture"))
        assertTrue(TracesLogcat.estCause("... 4 more"))
        assertFalse(TracesLogcat.estCause("at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"))
    }

    // ------------------------------------------------------ répétitions

    @Test
    fun `une run de lignes identiques forme un groupe xN`() {
        val lignes = listOf(
            ligne("GC a libéré 214 objets"),
            ligne("GC a libéré 214 objets"),
            ligne("GC a libéré 214 objets"),
            ligne("fin de purge"),
        )
        val etat = RegroupementLogcat.deriver(lignes)
        assertEquals(1, etat.groupes.size)
        val repetition = etat.groupes.single() as GroupeLogcat.Repetition
        assertEquals(0, repetition.debut)
        assertEquals(2, repetition.fin)
        assertEquals(3, repetition.repetitions)
        assertEquals(listOf(3), etat.horsGroupes)
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `l'identité de répétition porte message étiquette et niveau`() {
        val lignes = listOf(
            ligne("même texte", etiquette = "App"),
            ligne("même texte", etiquette = "Autre"),
            ligne("même texte", etiquette = "App", niveau = NiveauSortie.AVERTISSEMENT),
            ligne("même texte", etiquette = "App"),
        )
        val etat = RegroupementLogcat.deriver(lignes)
        // Seules les positions 0 et 3 (identiques TOUTES champs) forment
        // une run ; les deux autres restent isolées (non consécutives
        // avec leur jumelle — la run de 0 est coupée par l'étiquette).
        assertTrue(etat.groupes.isEmpty())
        assertEquals(listOf(0, 1, 2, 3), etat.horsGroupes)
    }

    @Test
    fun `deux occurrences suffisent a former un groupe`() {
        val etat = RegroupementLogcat.deriver(listOf(ligne("a"), ligne("a")))
        val repetition = etat.groupes.single() as GroupeLogcat.Repetition
        assertEquals(2, repetition.repetitions)
    }

    // ----------------------------------------------------------- traces

    private fun traceComplete(): List<LigneSortie> = listOf(
        ligne("Activity démarrée"),
        ligne("java.lang.RuntimeException: boom"),
        ligne("    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"),
        ligne("    at com.exemple.Autre.mesure(Autre.kt:8)"),
        ligne("Caused by: java.lang.IllegalStateException: état"),
        ligne("    at com.exemple.Source.ouvrir(Source.java:51)"),
        ligne("... 4 more"),
        ligne("reprise après incident"),
    )

    @Test
    fun `une trace complète se replie sous sa tête avec ses causes`() {
        val lignes = traceComplete()
        val etat = RegroupementLogcat.deriver(lignes)
        assertEquals(1, etat.groupes.size)
        val trace = etat.groupes.single() as GroupeLogcat.Trace
        assertEquals(1, trace.debut)
        assertEquals(6, trace.fin)
        assertEquals(listOf(4, 6), trace.positionsCauses)
        assertEquals(listOf(0, 7), etat.horsGroupes)
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `une tête sans cadre ni cause reste hors groupe`() {
        val lignes = listOf(ligne("java.lang.RuntimeException: boom"), ligne("rien après"))
        val etat = RegroupementLogcat.deriver(lignes)
        assertTrue(etat.groupes.isEmpty())
        assertEquals(listOf(0, 1), etat.horsGroupes)
    }

    @Test
    fun `des cadres sans tête ne forment pas une trace`() {
        val lignes = listOf(
            ligne("    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"),
            ligne("    at com.exemple.Autre.mesure(Autre.kt:8)"),
        )
        val etat = RegroupementLogcat.deriver(lignes)
        assertTrue(etat.groupes.isEmpty())
        assertEquals(listOf(0, 1), etat.horsGroupes)
    }

    @Test
    fun `une tête répétée trois fois donne trois traces distinctes`() {
        val tete = "java.lang.RuntimeException: boom"
        val cadre = "    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"
        val lignes = listOf(ligne(tete), ligne(cadre), ligne(tete), ligne(cadre), ligne(tete), ligne(cadre))
        val etat = RegroupementLogcat.deriver(lignes)
        assertEquals(3, etat.groupes.size)
        etat.groupes.forEach { groupe -> assertTrue(groupe is GroupeLogcat.Trace) }
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `la fenêtre vide ne dérive rien`() {
        val etat = RegroupementLogcat.deriver(emptyList())
        assertTrue(etat.groupes.isEmpty())
        assertTrue(etat.horsGroupes.isEmpty())
    }
}
