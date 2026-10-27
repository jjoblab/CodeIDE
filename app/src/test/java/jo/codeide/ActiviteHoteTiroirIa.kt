package jo.codeide

import androidx.fragment.app.FragmentActivity

/**
 * Activité hôte DE TEST pour les fragments `@AndroidEntryPoint` du
 * tiroir IA (rapport IA, chantier IA-CODEIDE, lot 1, B2) : Hilt exige
 * que tout fragment annoté vive dans une activité annotée — les tests
 * d'intégration du tiroir (TiroirIaOuvertureConversationTest) y
 * montent le tiroir SANS l'éditeur complet (le cast
 * `as? ControleurIaTiroir` échoue silencieusement : aucune commande
 * hôte n'est sollicitée).
 *
 * Hilt compile ce sourceSet de test (déjà utilisé par
 * FermetureProjetEspaceTest et ses injections `@Inject`).
 */
@dagger.hilt.android.AndroidEntryPoint
class ActiviteHoteTiroirIa : FragmentActivity()
