package jo.codeide.core.storage

import androidx.core.net.toUri
import dagger.Reusable
import jo.codeide.core.domain.ArborescencesSaf
import javax.inject.Inject

/**
 * Implémentation SAF du port [ArborescencesSaf] : délègue aux
 * décompositions officielles de `DocumentsContract` (concentrées dans
 * [UrisDocuments] pour la partie requêtes), en avalissant une URI
 * illisible par `null` au lieu de lever.
 */
@Reusable
internal class SafArborescences
    @Inject
    constructor() : ArborescencesSaf {
        override fun idDocument(grantUri: String): String? =
            runCatching {
                UrisDocuments.idArbre(grantUri.toUri())
            }.getOrNull()

        override fun uriDocument(grantUri: String): String? =
            runCatching {
                val arbre = grantUri.toUri()
                UrisDocuments.uriDocument(arbre, UrisDocuments.idArbre(arbre)).toString()
            }.getOrNull()
    }
