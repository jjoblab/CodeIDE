package jo.codeide.feature.newproject

import jo.codeide.core.model.TemplatePlan

/**
 * Arborescence prévisible du récapitulatif (section 12.3) : transformation
 * pure des chemins du [TemplatePlan] en nœuds affichables — dossiers
 * repliables, fichiers en feuilles, ordre lexicographique stable avec les
 * dossiers d'abord.
 *
 * Chaque nœud porte son **chemin original** (pré-renommage, ADR 0077) :
 * c'est l'identité stable ciblée par un nouveau renommage — le nom affiché
 * peut changer, l'identité jamais. Les renommages d'un dossier et d'un
 * fichier se composent alors naturellement côté moteur.
 *
 * Le calcul est déterministe : deux plans égaux produisent des arbres égaux
 * (tests sans horloge réelle, section 12.5).
 *
 * @property racine dossier racine (nom vide, jamais affiché tel quel).
 */
data class Arborescence(
    val racine: Noeud.Dossier,
) {
    /** Nombre total de fichiers de l'arbre (feuilles). */
    val nombreFichiers: Int get() = racine.nombreFichiers()

    /** Cherche un nœud par son chemin original (identité de renommage). */
    fun noeud(cheminOriginal: String): Noeud? = racine.chercher(cheminOriginal)

    /** Parent du nœud identifié par son chemin original (`null` à la racine). */
    fun parentDe(cheminOriginal: String): Noeud.Dossier? = racine.parentDe(cheminOriginal)

    companion object {
        /** Construit l'arbre depuis le plan (chemins finaux + chemins originaux). */
        fun depuisPlan(plan: TemplatePlan): Arborescence {
            val racine = Noeud.Dossier(nom = "", cheminOriginal = "", enfants = mutableMapOf())
            plan.fichiers.forEach { fichier ->
                val identite = fichier.cheminOriginal ?: fichier.chemin
                val segmentsFinaux = fichier.chemin.split(SEPARATEUR)
                val segmentsOriginaux = identite.split(SEPARATEUR)
                var courant = racine
                segmentsFinaux.dropLast(1).forEachIndexed { index, segment ->
                    val cheminOriginal = segmentsOriginaux.subList(0, index + 1).joinToString(SEPARATEUR)
                    val existant = courant.enfants[segment]
                    courant =
                        if (existant is Noeud.Dossier) {
                            existant
                        } else {
                            val nouveau =
                                Noeud.Dossier(nom = segment, cheminOriginal = cheminOriginal, enfants = mutableMapOf())
                            courant.enfants[segment] = nouveau
                            nouveau
                        }
                }
                // Une feuille n'écrase jamais un dossier de même nom.
                if (courant.enfants[segmentsFinaux.last()] == null) {
                    courant.enfants[segmentsFinaux.last()] =
                        Noeud.Fichier(nom = segmentsFinaux.last(), cheminOriginal = identite)
                }
            }
            return Arborescence(racine)
        }

        /** Séparateur des chemins du plan (relatifs, style Unix). */
        private const val SEPARATEUR = "/"
    }
}

/** Nœud de l'arborescence : dossier repliable ou fichier feuille. */
sealed interface Noeud {
    /** Nom affiché (vide pour la racine). */
    val nom: String

    /** Chemin original (pré-renommage) — identité stable du nœud. */
    val cheminOriginal: String

    /** Dossier : enfants triés (dossiers puis fichiers, ordre stable). */
    data class Dossier(
        override val nom: String,
        override val cheminOriginal: String,
        val enfants: MutableMap<String, Noeud>,
    ) : Noeud {
        /** Enfants triés : dossiers d'abord, puis fichiers, chacun lexicographique. */
        val enfantsTries: List<Noeud>
            get() = enfants.values.sortedWith(compareBy({ it !is Dossier }, Noeud::nom))

        /** Nombre de fichiers (récursif). */
        fun nombreFichiers(): Int =
            enfants.values.sumOf { noeud ->
                when (noeud) {
                    is Fichier -> 1
                    is Dossier -> noeud.nombreFichiers()
                }
            }

        /** Cherche récursivement le nœud d'identité [cheminOriginal]. */
        @Suppress("ReturnCount") // Récursion : trouvé, sinon descendu (règle 16).
        fun chercher(cheminOriginal: String): Noeud? {
            enfants.values.forEach { noeud ->
                if (noeud.cheminOriginal == cheminOriginal) return noeud
                if (noeud is Dossier) {
                    noeud.chercher(cheminOriginal)?.let { return it }
                }
            }
            return null
        }

        /** Parent récursif du nœud d'identité [cheminOriginal]. */
        @Suppress("ReturnCount") // Récursion : trouvé, sinon descendu (règle 16).
        fun parentDe(cheminOriginal: String): Dossier? {
            enfants.values.forEach { noeud ->
                if (noeud.cheminOriginal == cheminOriginal) return this
                if (noeud is Dossier) {
                    noeud.parentDe(cheminOriginal)?.let { return it }
                }
            }
            return null
        }
    }

    /** Fichier feuille. */
    data class Fichier(
        override val nom: String,
        override val cheminOriginal: String,
    ) : Noeud
}

/** Erreur de saisie d'un renommage au dialogue du récapitulatif (ADR 0077). */
enum class ErreurRenommage {
    /** Nom vide ou entièrement blanc. */
    VIDE,

    /** Séparateur de chemin dans le nom : un renommage est un simple segment. */
    SEPARATEUR,

    /** « . » ou « .. » : référence au répertoire courant ou parent. */
    PARENT,

    /** Un frère porte déjà ce nom final. */
    EXISTE,
}

/** Bornes de la validation locale d'un renommage (le domaine revalide tout). */
private const val NOM_MAX = 96

/**
 * Valide un renommage **localement** (dialogue du récapitulatif) : nom
 * simple, non vide, sans collision de frère dans l'arbre affiché. Les cas
 * exotiques (noms réservés Windows, encodage) restent au domaine — le plan
 * échoue explicitement et le bouton « Réessayer » réaffiche l'arbre.
 */
@Suppress("ReturnCount") // Validation : une clause de garde par famille d'erreur (règle 16).
fun Arborescence.validerRenommage(
    cheminOriginal: String,
    nom: String,
): ErreurRenommage? {
    val nettoye = nom.trim()
    if (nettoye.isEmpty()) return ErreurRenommage.VIDE
    if ('/' in nettoye || '\\' in nettoye) return ErreurRenommage.SEPARATEUR
    if (nettoye == "." || nettoye == "..") return ErreurRenommage.PARENT
    if (nettoye.length > NOM_MAX) return ErreurRenommage.VIDE
    val cible = noeud(cheminOriginal) ?: return null
    val parent = parentDe(cheminOriginal) ?: return null
    val frereExistant = parent.enfants.containsKey(nettoye) && cible.nom != nettoye
    return if (frereExistant) ErreurRenommage.EXISTE else null
}
