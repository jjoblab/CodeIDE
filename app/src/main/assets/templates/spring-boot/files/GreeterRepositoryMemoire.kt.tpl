package {{packageName}}

import org.springframework.stereotype.Repository

/**
 * {{t:depot.memoire.kdoc}}
 */
@Repository
class GreeterRepository : DepotSalutations {
    private val enregistrees = mutableListOf<Salutation>()
    private var prochainId = PREMIER_ID

    override fun sauver(message: String): Salutation {
        val salutation = Salutation(prochainId, message)
        prochainId += 1
        enregistrees += salutation
        return salutation
    }

    override fun lister(): List<Salutation> = enregistrees.toList()

    private companion object {
        const val PREMIER_ID = 1L
    }
}
