package {{packageName}};

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {{t:depot.entites.kdoc}}
 */
public interface DepotEntitesSalutation extends JpaRepository<EntiteSalutation, Long> {

    /**
     * Liste les entités enregistrées, du premier au dernier.
     */
    List<EntiteSalutation> findAllByOrderByIdAsc();
}
