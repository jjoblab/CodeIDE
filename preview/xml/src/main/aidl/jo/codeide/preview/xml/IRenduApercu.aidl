package jo.codeide.preview.xml;

import jo.codeide.preview.xml.RequeteRendu;
import jo.codeide.preview.xml.ResultatRendu;

/*
 * Frontière AIDL du processus :preview (PREVIEW.md §3, P3 — PE2, requête
 * paramétrée PE3). Un appel SYNCHRONE par rendu (le debounce vit côté app,
 * le rendu vit côté processus) : la REQUÊTE complète entre (texte + res/ +
 * genre + profil device + jour/nuit — Parcelable), le PNG compressé + les
 * métriques + les VUES (hit-test P6) sortent (Binder limite ~1 Mo par
 * transaction — le bitmap voyage COMPRESSÉ, décompressé côté app).
 */
interface IRenduApercu {
    ResultatRendu rendre(in RequeteRendu requete);
}
