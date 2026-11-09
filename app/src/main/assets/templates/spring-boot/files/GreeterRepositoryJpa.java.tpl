package {{packageName}};

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {{t:depot.jpa.kdoc}}
 */
@Repository
public class GreeterRepository implements DepotSalutations {

    private final DepotEntitesSalutation entites;

    public GreeterRepository(DepotEntitesSalutation entites) {
        this.entites = entites;
    }

    @Override
    public Salutation sauver(String message) {
        EntiteSalutation entite = entites.save(new EntiteSalutation(message));
        return new Salutation(entite.getId(), entite.getMessage());
    }

    @Override
    public List<Salutation> lister() {
        return entites.findAllByOrderByIdAsc().stream()
            .map(entite -> new Salutation(entite.getId(), entite.getMessage()))
            .toList();
    }
}
