package {{packageName}}

import org.springframework.data.jpa.repository.JpaRepository

/**
 * {{t:depot.entites.kdoc}}
 */
interface DepotEntitesSalutation : JpaRepository<EntiteSalutation, Long> {
    /** Liste les entités enregistrées, du premier au dernier. */
    fun findAllByOrderByIdAsc(): List<EntiteSalutation>
}
