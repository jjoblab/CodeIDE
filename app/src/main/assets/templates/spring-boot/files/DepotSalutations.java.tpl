package {{packageName}};

import java.util.List;

/**
 * {{t:depot.kdoc}}
 */
public interface DepotSalutations {

    /**
     * Enregistre une salutation et lui attribue un identifiant séquentiel.
     */
    Salutation sauver(String message);

    /**
     * Liste les salutations enregistrées, dans l'ordre d'ajout.
     */
    List<Salutation> lister();
}
