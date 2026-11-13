package jo.codeide.core.storage

import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests de [UrisDocuments] : construction et décomposition des URI SAF
 * (section 5.6 — logique d'URI, sans I/O).
 *
 * Les formes attendues sont les formes **modernes** du framework (API 26+) :
 * - document : `content://<autorite>/tree/<arbre>/document/<id>` ;
 * - enfants : `content://<autorite>/tree/<arbre>/document/<id>/children`.
 */
@RunWith(RobolectricTestRunner::class)
class UrisDocumentsTest {
    private val arbre = DocumentsContract.buildTreeDocumentUri("autorite", "racine")
    private val document = DocumentsContract.buildDocumentUriUsingTree(arbre, "racine/projet")

    @Test
    fun `l'URI de document se construit depuis l'arborescence`() {
        val uri = UrisDocuments.uriDocument(arbre, "racine/projet")

        assertEquals("content://autorite/tree/racine/document/racine%2Fprojet", uri.toString())
    }

    @Test
    fun `l'URI de document se construit aussi depuis une URI de document`() {
        // Les segments au-delà de `tree/<arbre>` sont ignorés : seul
        // compte le segment arborescence (comportement du framework).
        val uri = UrisDocuments.uriDocument(document, "racine/autre")

        assertEquals(
            UrisDocuments.uriDocument(arbre, "racine/autre").toString(),
            uri.toString(),
        )
    }

    @Test
    fun `l'URI des enfants dérive du parent document`() {
        val enfants = UrisDocuments.uriEnfants(document)

        assertEquals(
            "content://autorite/tree/racine/document/racine%2Fprojet/children",
            enfants.toString(),
        )
    }

    @Test
    fun `l'URI d'un document et celle de ses enfants partagent l'arborescence`() {
        val enfants = UrisDocuments.uriEnfants(document)

        assertEquals(document.authority, enfants.authority)
        assertEquals(
            Uri.parse("content://autorite/tree/racine"),
            DocumentsContract.buildTreeDocumentUri(enfants.authority, DocumentsContract.getTreeDocumentId(enfants)),
        )
    }
}
