package {{packageName}}

import org.springframework.stereotype.Repository

/**
 * {{t:depot.jpa.kdoc}}
 */
@Repository
class GreeterRepository(
    private val entites: DepotEntitesSalutation,
) : DepotSalutations {
    override fun sauver(message: String): Salutation {
        val entite = entites.save(EntiteSalutation(message = message))
        return Salutation(entite.id, entite.message)
    }

    override fun lister(): List<Salutation> =
        entites.findAllByOrderByIdAsc().map { entite ->
            Salutation(entite.id, entite.message)
        }
}
