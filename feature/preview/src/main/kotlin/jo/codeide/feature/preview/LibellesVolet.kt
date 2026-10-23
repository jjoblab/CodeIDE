package jo.codeide.feature.preview

import android.content.Context
import jo.codeide.preview.xml.InfoVueRendu

/**
 * Les LIBELLÉS partagés des volets de la feuille (PE9a) : le nom
 * lisible d'une vue rendue, consommé par le fil d'Ariane de la poignée
 * ([FeuilleApercuFragment]) ET par le détail de l'onglet Problèmes
 * ([VoletProblemesFragment]) — une seule formulation pour tout
 * l'écran (miroir `lbl` de la maquette).
 */
internal object LibellesVolet {
    /** Le libellé d'une vue (« Classe · @+id/x », sans id : la classe seule). */
    internal fun libelleVue(contexte: Context, vue: InfoVueRendu): String {
        val idNom = vue.idRes.substringAfterLast('/')
        return if (idNom.isEmpty()) {
            vue.classe
        } else {
            contexte.getString(R.string.preview_feuille_libelle, vue.classe, idNom)
        }
    }
}
