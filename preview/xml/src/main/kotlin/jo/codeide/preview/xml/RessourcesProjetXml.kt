package jo.codeide.preview.xml

/**
 * RESSOURCES DU PROJET PAR TYPE (PE11f — miroir `RES` de la maquette,
 * index consommé par [ValidationAttribut.ressource] et les suggestions
 * du contrôle `res`) — FONCTIONS PURES SANS ANDROID.
 *
 * Le document reste la SEULE source : [noms] lit les NOMS d'entrées
 * d'un fichier de valeurs par BALISE (`<string name="x">`,
 * `<style name="y">`…), premier vu gagne (miroir `Object.keys` —
 * `values/` d'abord, les qualifiés ne rabattent que les manquants).
 * Les drawables et layouts sont des NOMS DE FICHIERS (l'UI les liste
 * dans `res/`).
 */
public object RessourcesProjetXml {
    /** Les NOMS d'entrées `balise` des textes de fichiers de valeurs, premier vu gagne. */
    public fun noms(
        textes: List<String>,
        balise: String,
    ): Set<String> {
        val motif = Regex("""<${Regex.escape(balise)}\s+name\s*=\s*"([^"]+)"""")
        val vus = mutableSetOf<String>()
        for (texte in textes) {
            for (correspondance in motif.findAll(texte)) {
                vus.add(correspondance.groupValues[1].trim())
            }
        }
        return vus
    }
}
