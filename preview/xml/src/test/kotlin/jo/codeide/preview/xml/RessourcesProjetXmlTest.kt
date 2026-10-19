package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contrat de l'INDEX des ressources du projet (PE11f — les NOMS par
 * type, miroir `RES` de la maquette) : lecture des entrées de fichiers
 * de valeurs par balise, premier vu gagne. Module PUR.
 */
public class RessourcesProjetXmlTest {
    /** Un fichier de valeurs de test. */
    private val strings =
        """
        <resources>
            <string name="app_nom">CodeIDE</string>
            <string name="login_titre">Connexion</string>
        </resources>
        """.trimIndent()

    /** Les styles du même projet (autodéclarés dans styles.xml). */
    private val styles =
        """
        <resources>
            <style name="Theme.CodeIDE" parent="Theme.Material3.DayNight.NoActionBar" />
            <style name="Apparence.Bouton">
                <item name="android:textColor">#000000</item>
            </style>
        </resources>
        """.trimIndent()

    @Test
    public fun `les noms de strings se lisent dans l ordre du document`() {
        assertEquals(
            setOf("app_nom", "login_titre"),
            RessourcesProjetXml.noms(listOf(strings), "string"),
        )
    }

    @Test
    public fun `les noms de styles se lisent y compris les items internes`() {
        // Le motif s'ancre sur `<style name=` — les `<item>` ne fuient pas.
        assertEquals(
            setOf("Theme.CodeIDE", "Apparence.Bouton"),
            RessourcesProjetXml.noms(listOf(styles), "style"),
        )
    }

    @Test
    public fun `la balise demandee ne lit que la sienne`() {
        assertEquals(emptySet<String>(), RessourcesProjetXml.noms(listOf(strings), "style"))
        assertEquals(emptySet<String>(), RessourcesProjetXml.noms(listOf(styles), "string"))
    }
}
