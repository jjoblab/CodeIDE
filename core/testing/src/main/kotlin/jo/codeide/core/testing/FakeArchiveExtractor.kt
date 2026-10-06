package jo.codeide.core.testing

import jo.codeide.core.domain.ArchiveExtractor
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Doublure de [ArchiveExtractor] (refonte E2, ADR 0087) : les extractions
 * sont enregistrées ; le test sème soit un échec typé, soit laisse la
 * doublure réussir sans toucher au disque (la vraie extraction zip est
 * éprouvée par les tests de `core:bootstrap` eux-mêmes).
 */
public class FakeArchiveExtractor : ArchiveExtractor {
    /** Extractions demandées : paires (archive, cible). */
    public val extractions: MutableList<Pair<File, File>> = CopyOnWriteArrayList()

    /** Quand non `null`, l'extraction échoue par cette erreur typée. */
    public var echec: AppError? = null

    override suspend fun extract(
        archive: File,
        targetDir: File,
    ): AppResult<Unit> {
        extractions += archive to targetDir
        echec?.let { return AppResult.Failure(it) }
        return AppResult.Success(Unit)
    }
}
