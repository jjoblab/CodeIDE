package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Classification previewable (PE1) — chemin d'abord, racine XML en repli,
 * conservateur (null sur l'inconnu).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ClassificateurPreviewTest {
    @Test
    fun `par chemin - layout drawable valeurs`() {
        assertEquals(
            GenrePreview.LAYOUT,
            ClassificateurPreview.genreDe("app/src/main/res/layout/activity_main.xml", "<LinearLayout/>"),
        )
        assertEquals(GenrePreview.DRAWABLE, ClassificateurPreview.genreDe("res/drawable/fond.xml", "<selector/>"))
        assertEquals(GenrePreview.VALEURS, ClassificateurPreview.genreDe("res/values/colors.xml", "<resources/>"))
        assertEquals(GenrePreview.LAYOUT, ClassificateurPreview.genreDe("res\\layout\\a.xml", "<Foo/>"))
    }

    @Test
    fun `par racine - repli sans chemin`() {
        assertEquals(GenrePreview.LAYOUT, ClassificateurPreview.genreDe("notes.txt", "<TextView/>"))
        assertEquals(
            GenrePreview.LAYOUT,
            ClassificateurPreview.genreDe("", "<androidx.constraintlayout.widget.ConstraintLayout/>"),
        )
        assertEquals(GenrePreview.DRAWABLE, ClassificateurPreview.genreDe("", "<shape/>"))
        assertEquals(GenrePreview.VALEURS, ClassificateurPreview.genreDe("", "<resources/>"))
    }

    @Test
    fun `chemin prioritaire sur la racine`() {
        // Un layout dans res/values/ reste VALEURS (la vérité est le chemin).
        assertEquals(
            GenrePreview.VALEURS,
            ClassificateurPreview.genreDe("res/values/truc.xml", "<LinearLayout/>"),
        )
    }

    @Test
    fun `inconnu - null conservateur`() {
        assertNull(ClassificateurPreview.genreDe("src/Main.kt", "fun main() {}"))
        assertNull(ClassificateurPreview.genreDe("truc.xml", "<MachinChose/>"))
    }

    @Test
    fun `xml malforme ou vide - null sans lever`() {
        assertNull(ClassificateurPreview.racineXml(""))
        assertNull(ClassificateurPreview.racineXml("<LinearLayout"))
        assertNull(ClassificateurPreview.racineXml("du texte pas du tout xml"))
        // genreDe sur texte malformé : null (chemin absent).
        assertNull(ClassificateurPreview.genreDe("", "<LinearLayout"))
    }
}
