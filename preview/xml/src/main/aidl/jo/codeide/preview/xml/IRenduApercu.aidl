package jo.codeide.preview.xml;

import jo.codeide.preview.xml.ResultatRendu;

/*
 * Frontière AIDL du processus :preview (PREVIEW.md §3, P3 — PE2).
 * Un appel SYNCHRONE par rendu (le debounce vit côté app, le rendu vit
 * côté processus) : le XML + le dossier res/ entrent, le PNG compressé
 * + les métriques sortent (Binder limite ~1 Mo par transaction — le
 * bitmap voyage COMPRESSÉ, décompressé côté app).
 */
interface IRenduApercu {
    ResultatRendu rendre(String xml, String dossierRes);
}
