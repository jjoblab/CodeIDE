package jo.codeide.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Port du moteur de recherche (mission Recherche S0, ADR 0094) —
 * abstraction de la recherche de texte dans le projet pour que l'UI et
 * les tests ne dépendent pas du moteur choisi (balayage Kotlin pur en
 * production, fake en test).
 *
 * Le moteur travaille sur des **chemins FUSE réels** (produits par
 * `ResoudreRepertoireProjet`), jamais sur des URI SAF. Les résultats
 * sont émis par **lots** via [Flow] pour une consommation réactive et
 * une mémoire bornée. L'annulation est immédiate quand la requête
 * change (collecte annulée → flux froid terminé).
 *
 * @see RechercheMoteurBalayage pour l'implémentation de production.
 */
public interface RechercheMoteur {
    /**
     * Recherche [requete] dans le projet à [cheminFuse]. Émet des lots de
     * résultats au fur et à mesure du balayage. Le flux est **froid** :
     * l'annulation de la collecte arrête immédiatement le balayage.
     *
     * @param cheminFuse chemin FUSE réel du projet.
     * @param requête texte à rechercher (literal ou regex selon [options]).
     * @param options options de recherche (casse, mot entier, regex, etc.).
     * @return flux de lots de résultats, terminé quand le balayage est
     * complet ou le plafond atteint.
     */
    public fun rechercher(
        cheminFuse: String,
        requete: String,
        options: OptionsRecherche = OptionsRecherche(),
    ): Flow<LotResultats>
}

/**
 * Options de recherche (modèle pur, ADR 0094).
 *
 * @property ignorerCasse insensible à la casse.
 * @property motEntier correspondance sur mots entiers (Unicode).
 * @property regex [requete] est une expression régulière.
 * @property conserverCasse le remplacement conserve la casse de l'original.
 * @property inclure masque de fichiers à inclure (globs séparés par virgules).
 * @property exclure masque de fichiers à exclure.
 * @property respecterGitignore exclure les fichiers ignorés par .gitignore.
 * @property masquerBuild exclure build/ et .gradle/.
 * @property masquerCaches exclure les fichiers cachés (commençant par un point).
 * @property plafondResultats nombre maximum de résultats (défaut 20 000).
 * @property tailleMaxFichier taille maximum par fichier en octets (défaut 2 Mo).
 */
public data class OptionsRecherche(
    public val ignorerCasse: Boolean = false,
    public val motEntier: Boolean = false,
    public val regex: Boolean = false,
    public val conserverCasse: Boolean = false,
    public val inclure: String = "",
    public val exclure: String = "",
    public val respecterGitignore: Boolean = true,
    public val masquerBuild: Boolean = true,
    public val masquerCaches: Boolean = false,
    public val plafondResultats: Int = PLAFOND_DEFAUT,
    public val tailleMaxFichier: Long = TAILLE_MAX_DEFAUT,
) {
    public companion object {
        /** Plafond de résultats par défaut (comme VS Code). */
        public const val PLAFOND_DEFAUT: Int = 20_000

        /** Taille maximum par fichier (2 Mo). */
        public const val TAILLE_MAX_DEFAUT: Long = 2L * 1024 * 1024
    }
}

/**
 * Un lot de résultats de recherche (émis par [RechercheMoteur.rechercher]).
 *
 * @property resultats liste des occurrences trouvées dans ce lot.
 * @property fichiersBalayes nombre de fichiers balayés jusqu'à présent.
 * @property plafondAtteint vrai si le plafond de résultats est atteint.
 * @property termine vrai si le balayage est complet.
 */
public data class LotResultats(
    public val resultats: List<ResultatRecherche>,
    public val fichiersBalayes: Int,
    public val plafondAtteint: Boolean = false,
    public val termine: Boolean = false,
)

/**
 * Une occurrence de recherche dans un fichier.
 *
 * @property chemin chemin relatif du fichier (ou absolu si hors projet).
 * @property numeroLigne numéro de ligne (1-indexé).
 * @property colonneDebut colonne de début (0-indexée).
 * @property colonneFin colonne de fin (0-indexée, exclusive).
 * @property extrait extrait de la ligne (espaces de tête coupés).
 * @property nomFichier nom du fichier (dernier segment).
 */
public data class ResultatRecherche(
    public val chemin: String,
    public val numeroLigne: Int,
    public val colonneDebut: Int,
    public val colonneFin: Int,
    public val extrait: String,
    public val nomFichier: String,
)
