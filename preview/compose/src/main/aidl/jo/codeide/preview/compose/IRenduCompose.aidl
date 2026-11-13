package jo.codeide.preview.compose;

import jo.codeide.preview.compose.EvenementTactileCompose;
import jo.codeide.preview.compose.RequeteRenduCompose;
import jo.codeide.preview.compose.ResultatRenduCompose;

/*
 * Frontière AIDL du processus :preview pour la preview COMPOSE
 * (PREVIEW.md §4 PC4, C1) — le MÊME processus que IRenduApercu (une
 * seule machinerie, deux dialectes). Un appel SYNCHRONE par rendu : le
 * PROGRAMME interprété (arbre d'appels clos issu du LSP Kotlin — le
 * processus :preview ne voit JAMAIS de source Kotlin) et le profil
 * entrent (Parcelable), le BITMAP (ashmem) + les métriques + les
 * ERREURS TYPIQUES sortent. L'échec de rendu voyage en DONNÉE, jamais
 * en exception à travers Binder.
 *
 * C3 (PREVIEW.md §4.4 « forwarding des entrées », écho triggerTouchEvent
 * de la référence) : transmettre pousse UNE touche du premier doigt
 * dans la composition VIVANTE de la session (identité `uri#nom#index`) ;
 * le point voyage en px bitmap (le repère du canevas), la composition
 * reçoit un vrai MotionEvent reconstruit sur son fil. faux = session
 * inconnue (fermée, jamais rendue) — une touche perdue est une DONNÉE
 * honnête, jamais une exception.
 */
interface IRenduCompose {
    ResultatRenduCompose rendre(in RequeteRenduCompose requete);

    boolean transmettre(in String identite, in EvenementTactileCompose evenement);
}
