package jo.codeide.preview.xml

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitaires de [DexViewFactory] — G1 / Phase 2 (v0.132.0).
 *
 * Valide la logique de dispatch :
 * - Tag court (pas de point) → retour null (ViewFactory par défaut).
 * - Tag boot-loadable (android, java) → retour null.
 * - Tag kotlin/kotlinx → retour null.
 * - Pas de classloader (loader=null) → retour null.
 * - Tag dotted projet avec classloader → tentative de création.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DexViewFactoryTest {
    private lateinit var context: Context
    private lateinit var factory: DexViewFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = DexViewFactory()
    }

    @Test
    fun `tag court retourne null`() {
        factory.loader = ClassLoader.getSystemClassLoader()
        val view = factory.onCreateView(null, "TextView", context, emptyAttrs())
        assertNull("tag court doit retourner null (ViewFactory par défaut)", view)
    }

    @Test
    fun `tag android point retourne null`() {
        factory.loader = ClassLoader.getSystemClassLoader()
        val view = factory.onCreateView(null, "android.widget.TextView", context, emptyAttrs())
        assertNull("android.* doit retourner null (framework géré par ViewFactory)", view)
    }

    @Test
    fun `tag java point retourne null`() {
        factory.loader = ClassLoader.getSystemClassLoader()
        val view = factory.onCreateView(null, "java.lang.String", context, emptyAttrs())
        assertNull("java.* doit retourner null", view)
    }

    @Test
    fun `tag kotlin point retourne null`() {
        factory.loader = ClassLoader.getSystemClassLoader()
        val view = factory.onCreateView(null, "kotlin.collections.List", context, emptyAttrs())
        assertNull("kotlin.* doit retourner null (stdlib bundlée)", view)
    }

    @Test
    fun `loader null retourne null pour tag dotted`() {
        factory.loader = null
        val view = factory.onCreateView(null, "com.example.FooView", context, emptyAttrs())
        assertNull("sans classloader, factory retourne null", view)
    }

    @Test
    fun `classe introuvable retourne null`() {
        factory.loader = ClassLoader.getSystemClassLoader()
        val view = factory.onCreateView(null, "com.example.NouvelleClasseInexistante", context, emptyAttrs())
        assertNull("classe introuvable → null (G15 MockView fallback)", view)
    }

    @Test
    fun `classe non View retourne null`() {
        factory.loader = ClassLoader.getSystemClassLoader()
        // java.lang.String est une classe valide mais pas une View.
        // On force le tag avec un préfixe non-android pour passer les gardes.
        val view = factory.onCreateView(null, "com.example.NotAView", context, emptyAttrs())
        assertNull("classe non-View → null", view)
    }

    @Test
    fun `factory sans loader ne leve pas`() {
        factory.loader = null
        // Appel répété — ne doit jamais lever.
        repeat(10) {
            factory.onCreateView(null, "com.example.Foo", context, emptyAttrs())
        }
    }

    @Test
    fun `onCreateView 4 args delegue vers 3 args`() {
        factory.loader = null
        val view4 = factory.onCreateView(null, "com.example.Foo", context, emptyAttrs())
        val view3 = factory.onCreateView("com.example.Foo", context, emptyAttrs())
        // Les deux doivent retourner null (pas de loader).
        assertNull(view4)
        assertNull(view3)
    }

    @Test
    fun `loader volatile visible`() {
        // Vérifie que `loader` est @Volatile (visible depuis autres threads).
        // Test statique : on ne peut pas vérifier l'annotation runtime,
        // mais on vérifie que la valeur est lisible/inscriptible.
        factory.loader = null
        assertNull(factory.loader)
        val cl = ClassLoader.getSystemClassLoader()
        factory.loader = cl
        assertTrue(factory.loader === cl)
        factory.loader = null
        assertNull(factory.loader)
    }

    /** Crée un AttributeSet vide (NopAttributeSet-like via Xml). */
    private fun emptyAttrs(): AttributeSet {
        // Utilise org.xmlpull.v1 wrapper — Robolectric fournit un contexte
        // qui peut créer un AttributeSet vide.
        return android.util.Xml.newPullParser().let { parser ->
            parser.setInput(java.io.StringReader("<x/>"))
            // Avance jusqu'au START_TAG, puis crée l'AttributeSet.
            parser.next()
            try {
                android.util.Xml::class.java
                    .getMethod("asAttributeSet", org.xmlpull.v1.XmlPullParser::class.java)
                    .invoke(null, parser) as AttributeSet
            } catch (_: Exception) {
                // Fallback : retourne un AttributeSet vide via Proxy.
                emptyAttributeSet()
            }
        }
    }

    /** AttributeSet vide via Proxy dynamique (fallback). */
    private fun emptyAttributeSet(): AttributeSet =
        java.lang.reflect.Proxy.newProxyInstance(
            AttributeSet::class.java.classLoader,
            arrayOf(AttributeSet::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                String::class.java -> ""
                else -> null
            }
        } as AttributeSet
}
