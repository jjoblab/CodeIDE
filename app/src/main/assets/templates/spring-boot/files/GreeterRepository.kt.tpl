package {{packageName}}

import org.springframework.stereotype.Repository

/**
 * {{t:repository.kdoc}}
 */
data class Salutation(
    val id: Long,
    val message: String,
)

@Repository
class GreeterRepository {
    private val enregistrees = mutableListOf<Salutation>()
    private var prochainId = PREMIER_ID

    /** Enregistre une salutation et lui attribue un identifiant séquentiel. */
    fun sauver(message: String): Salutation {
        val salutation = Salutation(prochainId, message)
        prochainId += 1
        enregistrees += salutation
        return salutation
    }

    /** Liste les salutations enregistrées, dans l'ordre d'ajout. */
    fun lister(): List<Salutation> = enregistrees.toList()

    private companion object {
        const val PREMIER_ID = 1L
    }
}
