package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La table des FAMILLES (PE12a — `docs/PREVIEW_PARENTS.md` § 2.2, ADR
 * 0135 D1) : chaînes de superclasses COMPLÈTES jusqu'à `Object` (celles
 * que produit le rendu réel), sous-classes connues, classes inconnues,
 * et le repli par balise simple ou qualifiée.
 *
 * [FamilleParent] est PUR (zéro Android) : test JVM direct, sans
 * Robolectric.
 */
class FamilleParentTest {
    @Test
    fun `deClasses - AppBarLayout rencontre avant le LinearLayout qu'il etend`() {
        assertEquals(
            FamilleParent.APPBAR,
            FamilleParent.deClasses(
                listOf(
                    "com.google.android.material.appbar.AppBarLayout",
                    "android.widget.LinearLayout",
                    "android.view.ViewGroup",
                    "android.view.View",
                    "java.lang.Object",
                ),
            ),
        )
    }

    @Test
    fun `deClasses - CoordinatorLayout rencontre avant le FrameLayout qu'il etend`() {
        assertEquals(
            FamilleParent.COORDINATOR,
            FamilleParent.deClasses(
                listOf(
                    "androidx.coordinatorlayout.widget.CoordinatorLayout",
                    "android.widget.FrameLayout",
                    "android.view.ViewGroup",
                    "android.view.View",
                    "java.lang.Object",
                ),
            ),
        )
    }

    @Test
    fun `deClasses - ConstraintLayout et MotionLayout vers CONSTRAINT`() {
        assertEquals(
            FamilleParent.CONSTRAINT,
            FamilleParent.deClasses(
                listOf(
                    "androidx.constraintlayout.widget.ConstraintLayout",
                    "android.view.ViewGroup",
                    "android.view.View",
                    "java.lang.Object",
                ),
            ),
        )
        // MotionLayout ÉTEND ConstraintLayout : la première classe connue
        // de la chaîne est MotionLayout lui-même — même famille.
        assertEquals(
            FamilleParent.CONSTRAINT,
            FamilleParent.deClasses(
                listOf(
                    "androidx.constraintlayout.motion.widget.MotionLayout",
                    "androidx.constraintlayout.widget.ConstraintLayout",
                    "android.view.ViewGroup",
                    "android.view.View",
                    "java.lang.Object",
                ),
            ),
        )
    }

    @Test
    fun `deClasses - RelativeLayout et LinearLayout`() {
        assertEquals(
            FamilleParent.RELATIVE,
            FamilleParent.deClasses(
                listOf(
                    "android.widget.RelativeLayout",
                    "android.view.ViewGroup",
                    "android.view.View",
                    "java.lang.Object",
                ),
            ),
        )
        assertEquals(
            FamilleParent.LINEAR,
            FamilleParent.deClasses(
                listOf(
                    "android.widget.LinearLayout",
                    "android.view.ViewGroup",
                    "android.view.View",
                    "java.lang.Object",
                ),
            ),
        )
    }

    @Test
    fun `deClasses - sous-classes connues tombent sur FrameLayout`() {
        // Quatre sous-classes dont le nom ne porte PAS l'ancêtre : la
        // chaîne les fait tomber sur android.widget.FrameLayout (§ 2.2).
        val sousClasses =
            listOf(
                "com.google.android.material.card.MaterialCardView",
                "androidx.core.widget.NestedScrollView",
                "android.widget.ScrollView",
                "com.google.android.material.appbar.CollapsingToolbarLayout",
            )
        for (nom in sousClasses) {
            assertEquals(
                "chaîne de $nom",
                FamilleParent.FRAME,
                FamilleParent.deClasses(
                    listOf(
                        nom,
                        "android.widget.FrameLayout",
                        "android.view.ViewGroup",
                        "android.view.View",
                        "java.lang.Object",
                    ),
                ),
            )
        }
    }

    @Test
    fun `deClasses - classe inconnue ou chaine vide vers AUTRE`() {
        assertEquals(
            FamilleParent.AUTRE,
            FamilleParent.deClasses(
                listOf(
                    "com.example.exotique.PanneauGrid",
                    "android.view.ViewGroup",
                    "android.view.View",
                    "java.lang.Object",
                ),
            ),
        )
        assertEquals(FamilleParent.AUTRE, FamilleParent.deClasses(emptyList()))
    }

    @Test
    fun `deBalise - noms simples de la plateforme`() {
        assertEquals(FamilleParent.APPBAR, FamilleParent.deBalise("AppBarLayout"))
        assertEquals(FamilleParent.COORDINATOR, FamilleParent.deBalise("CoordinatorLayout"))
        assertEquals(FamilleParent.CONSTRAINT, FamilleParent.deBalise("ConstraintLayout"))
        assertEquals(FamilleParent.CONSTRAINT, FamilleParent.deBalise("MotionLayout"))
        assertEquals(FamilleParent.RELATIVE, FamilleParent.deBalise("RelativeLayout"))
        assertEquals(FamilleParent.LINEAR, FamilleParent.deBalise("LinearLayout"))
        assertEquals(FamilleParent.FRAME, FamilleParent.deBalise("FrameLayout"))
    }

    @Test
    fun `deBalise - noms qualifiés`() {
        assertEquals(
            FamilleParent.COORDINATOR,
            FamilleParent.deBalise("androidx.coordinatorlayout.widget.CoordinatorLayout"),
        )
        assertEquals(
            FamilleParent.APPBAR,
            FamilleParent.deBalise("com.google.android.material.appbar.AppBarLayout"),
        )
        assertEquals(
            FamilleParent.FRAME,
            FamilleParent.deBalise("com.google.android.material.card.MaterialCardView"),
        )
    }

    @Test
    fun `deBalise - sous-classes connues vers FRAME`() {
        assertEquals(FamilleParent.FRAME, FamilleParent.deBalise("MaterialCardView"))
        assertEquals(FamilleParent.FRAME, FamilleParent.deBalise("NestedScrollView"))
        assertEquals(FamilleParent.FRAME, FamilleParent.deBalise("ScrollView"))
        assertEquals(FamilleParent.FRAME, FamilleParent.deBalise("CollapsingToolbarLayout"))
    }

    @Test
    fun `deBalise - balise inconnue ou vide vers AUTRE`() {
        assertEquals(FamilleParent.AUTRE, FamilleParent.deBalise("GridLayout"))
        assertEquals(FamilleParent.AUTRE, FamilleParent.deBalise("TextView"))
        assertEquals(FamilleParent.AUTRE, FamilleParent.deBalise(""))
    }

    @Test
    fun `deNom - nom d'entree connu, inconnu ou absent`() {
        assertEquals(FamilleParent.COORDINATOR, FamilleParent.deNom("COORDINATOR"))
        assertEquals(FamilleParent.AUTRE, FamilleParent.deNom("AUTRE"))
        assertNull(FamilleParent.deNom("chez_pas"))
        assertNull(FamilleParent.deNom(""))
        assertNull(FamilleParent.deNom(null))
    }
}
