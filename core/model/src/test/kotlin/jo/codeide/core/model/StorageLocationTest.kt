package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests de [StorageLocation] : contrat SAF (deux URI + libellé), validation
 * à la construction et sécurité du `toString` pour les journaux.
 */
class StorageLocationTest {
    private val exemple =
        StorageLocation(
            grantUri = "content://com.android.externalstorage.documents/tree/ABC",
            documentUri = "content://com.android.externalstorage.documents/tree/ABC/document/XYZ",
            displayPath = "Téléchargements",
        )

    @Test
    fun `l'emplacement porte les deux URI et le libellé`() {
        assertEquals(
            "content://com.android.externalstorage.documents/tree/ABC",
            exemple.grantUri,
        )
        assertEquals(
            "content://com.android.externalstorage.documents/tree/ABC/document/XYZ",
            exemple.documentUri,
        )
        assertEquals("Téléchargements", exemple.displayPath)
    }

    @Test
    fun `une URI vide est refusée pour chaque champ`() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageLocation(grantUri = "", documentUri = "uri", displayPath = "libellé")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageLocation(grantUri = "uri", documentUri = "", displayPath = "libellé")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageLocation(grantUri = "uri", documentUri = "uri", displayPath = " ")
        }
    }

    @Test
    fun `l'égalité structurelle couvre les trois champs`() {
        val meme =
            StorageLocation(
                grantUri = "content://com.android.externalstorage.documents/tree/ABC",
                documentUri = "content://com.android.externalstorage.documents/tree/ABC/document/XYZ",
                displayPath = "Téléchargements",
            )
        val autreLibelle =
            StorageLocation(
                grantUri = "content://com.android.externalstorage.documents/tree/ABC",
                documentUri = "content://com.android.externalstorage.documents/tree/ABC/document/XYZ",
                displayPath = "Documents",
            )

        assertEquals(exemple, meme)
        assertNotEquals(exemple, autreLibelle)
    }

    @Test
    fun `toString n'expose ni les URI ni plus que le libellé`() {
        val representation = exemple.toString()

        assertEquals("Téléchargements", representation)
        assertNotEquals(exemple.grantUri, representation)
    }
}
