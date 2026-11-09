package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du parseur ANSI SGR (ADR 0160 § Décision 4, lot L1) — gras,
 * italique, palette 8+8 ; tout contrôleur hors périmètre (couleurs 256,
 * RGB, fonds, CSI non-SGR, OSC, jeux de caractères) est CONSOMMÉ sans
 * rendu ; un ESC isolé en fin de flux ne lève rien.
 *
 * Les séquences se composent par CONCATÉNATION ([ESC] + littéral) et pas
 * par template : une simplification de template par l'outil de format
 * altérerait le littéral `[…m` qui suit le `$` (leçon du lot L1).
 */
class ParseurAnsiSgrTest {
    private companion object {
        /** Caractère d'échappement sous sa forme lisible. */
        private const val ESC = "\u001B"

        /** Cloche de terminaison OSC (BEL). */
        private const val BEL = "\u0007"

        /** Contre-oblique du terminateur ST (`ESC \`). */
        private const val CONTRE_OBLIQUE = "\\"
    }

    @Test
    fun `une ligne sans echappement reste un segment unique`() {
        val segments = ParseurAnsiSgr.analyser("BUILD SUCCESSFUL in 42s")

        assertEquals(1, segments.size)
        assertEquals("BUILD SUCCESSFUL in 42s", segments[0].texte)
        assertFalse(segments[0].gras)
        assertNullStyle(segments[0])

        assertEquals(0, ParseurAnsiSgr.analyser("").size)
        assertFalse(ParseurAnsiSgr.contientEscape("texte nu"))
        assertTrue(ParseurAnsiSgr.contientEscape(ESC + "[31mrouge"))
    }

    @Test
    fun `gras et italique se posent et se retirent`() {
        val segments = ParseurAnsiSgr.analyser(
            ESC + "[1mgras" + ESC + "[22m" + ESC + "[3mitalique" + ESC + "[23mnu",
        )

        assertEquals(3, segments.size)
        assertEquals("gras", segments[0].texte)
        assertTrue(segments[0].gras)
        assertFalse(segments[0].italique)
        assertEquals("italique", segments[1].texte)
        assertFalse(segments[1].gras)
        assertTrue(segments[1].italique)
        assertEquals("nu", segments[2].texte)
        assertFalse(segments[2].gras)
        assertFalse(segments[2].italique)
    }

    @Test
    fun `les couleurs de texte se projettent sur la palette huit plus huit`() {
        val segments = ParseurAnsiSgr.analyser(
            ESC + "[31mrouge" + ESC + "[91mrouge vif" + ESC + "[39mdefaut",
        )

        assertEquals(3, segments.size)
        assertEquals(CouleurAnsi.ROUGE, segments[0].couleur)
        assertFalse(segments[0].couleurVive)
        assertEquals(CouleurAnsi.ROUGE, segments[1].couleur)
        assertTrue(segments[1].couleurVive)
        assertNullStyle(segments[2])
        // Le vert (32), le blanc vif (97) et le cyan (96) tombent juste.
        assertEquals(CouleurAnsi.VERT, ParseurAnsiSgr.analyser(ESC + "[32mv")[0].couleur)
        assertEquals(CouleurAnsi.BLANC, ParseurAnsiSgr.analyser(ESC + "[97mw")[0].couleur)
        assertTrue(ParseurAnsiSgr.analyser(ESC + "[96mc")[0].couleurVive)
    }

    @Test
    fun `la reinitialisation SGR zero efface tout le style`() {
        val segments = ParseurAnsiSgr.analyser(
            ESC + "[1;3;32membelli" + ESC + "[0mnu",
        )

        assertEquals(2, segments.size)
        assertTrue(segments[0].gras)
        assertTrue(segments[0].italique)
        assertEquals(CouleurAnsi.VERT, segments[0].couleur)
        assertEquals("nu", segments[1].texte)
        assertFalse(segments[1].gras)
        assertNullStyle(segments[1])
    }

    @Test
    fun `les couleurs 256 et RGB et les fonds sont consommes sans rendu`() {
        // 38;5;n (256 couleurs) : le n est consommé AVEC le 38;5 — aucun
        // paramètre ne fuit dans le texte.
        val palette = ParseurAnsiSgr.analyser(ESC + "[38;5;196mrose")
        assertEquals(1, palette.size)
        assertEquals("rose", palette[0].texte)
        assertNullStyle(palette[0])
        // 38;2;r;g;b (RGB direct) : quatre paramètres consommés.
        val rgb = ParseurAnsiSgr.analyser(ESC + "[38;2;255;0;0mx")
        assertEquals(1, rgb.size)
        assertEquals("x", rgb[0].texte)
        assertNullStyle(rgb[0])
        // Fonds 48;5;n et 48;2;r;g;b : consommés sans influer sur le texte
        // ni sur le style (le dernier octet RGB n'est PAS relu comme un
        // code SGR autonome — 48;2;1;2;3 ne pose pas d'italique). Deux SGR
        // de fond sans effet de style ne coupent pas le segment.
        val fond = ParseurAnsiSgr.analyser(ESC + "[48;5;21mfond" + ESC + "[48;2;1;2;3mrgb")
        assertEquals(1, fond.size)
        assertEquals("fondrgb", fond[0].texte)
        assertNullStyle(fond[0])
    }

    @Test
    fun `les CSI non SGR et les OSC sont purges sans rendu`() {
        // Effacement de ligne (2K), mouvement de curseur (1;2H).
        val csi = ParseurAnsiSgr.purger("avant" + ESC + "[2Kmilieu" + ESC + "[1;2Hapres")
        assertEquals("avantmilieuapres", csi)
        // OSC terminé par BEL (titres de fenêtre) : consommé jusqu'à la cloche.
        assertEquals("suite", ParseurAnsiSgr.purger(ESC + "]0;titre" + BEL + "suite"))
        // OSC terminé par ST (liens hypertexte ESC]…ESC\) : consommé jusqu'au ST.
        assertEquals("fin", ParseurAnsiSgr.purger(ESC + "]8;;https://x" + ESC + CONTRE_OBLIQUE + "fin"))
        // L'analyse s'aligne sur la purge : aucune séquence résiduelle.
        val segments = ParseurAnsiSgr.analyser("avant" + ESC + "[2Kapres")
        assertEquals(1, segments.size)
        assertEquals("avantapres", segments[0].texte)
    }

    @Test
    fun `les jeux de caracteres et commandes a deux caracteres sont purges`() {
        assertEquals("texte", ParseurAnsiSgr.purger("te" + ESC + "(Bxt" + ESC + ")0e"))
        assertEquals("texte", ParseurAnsiSgr.purger("te" + ESC + "#8xt" + ESC + "ce"))
        assertEquals("x", ParseurAnsiSgr.purger(ESC + "7x"))
    }

    @Test
    fun `un ESC isole en fin de flux est purge sans exception`() {
        assertEquals("flot", ParseurAnsiSgr.purger("flot" + ESC))
        assertEquals(1, ParseurAnsiSgr.analyser("flot" + ESC).size)
        assertEquals("flot", ParseurAnsiSgr.analyser("flot" + ESC)[0].texte)
    }

    @Test
    fun `purger rend le texte nu y compris sur un texte deja nu`() {
        val forge =
            ESC + "[1m" + ESC + "[32m" + ESC + "[2KTask :app:compile" + ESC + "[0m" + ESC + "[39m ok"
        assertEquals("Task :app:compile ok", ParseurAnsiSgr.purger(forge))
        assertEquals("texte nu", ParseurAnsiSgr.purger("texte nu"))
        assertEquals("", ParseurAnsiSgr.purger(""))
    }

    @Test
    fun `les segments se sequencent dans l ordre d ecriture`() {
        val segments = ParseurAnsiSgr.analyser(
            ESC + "[1m>" + ESC + "[0m Task :app:" + ESC + "[91mFAILED" + ESC + "[0m",
        )

        assertEquals(3, segments.size)
        assertEquals(">", segments[0].texte)
        assertTrue(segments[0].gras)
        assertEquals(" Task :app:", segments[1].texte)
        assertFalse(segments[1].gras)
        assertEquals("FAILED", segments[2].texte)
        assertEquals(CouleurAnsi.ROUGE, segments[2].couleur)
        assertTrue(segments[2].couleurVive)
    }

    /** Le segment porte-t-il un style neutre (couleur par défaut, sans
     *  gras ni italique) ? */
    private fun assertNullStyle(segment: SegmentAnsi) {
        assertFalse(segment.gras)
        assertFalse(segment.italique)
        assertEquals(null, segment.couleur)
        assertFalse(segment.couleurVive)
    }
}
