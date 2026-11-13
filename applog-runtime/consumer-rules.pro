// Règles de consommation R8 pour les builds debug MINIFIÉS de l'app cible
// (ADR 0103 : inclus dans la bibliothèque, pas dans les sources de
// l'utilisateur). Le point d'entrée du pont est le provider fusionné dans
// le manifeste — le réducteur ne peut pas le deviner seul.
-keep class jo.codeide.applog.AmorcePont { *; }
-keep class jo.codeide.applog.Pont { *; }

// Interfaces AIDL : le binder les instancie par réflexion côté IDE.
-keep class jo.codeide.applog.ILiaisonJournaux { *; }
-keep class jo.codeide.applog.ILiaisonJournaux$* { *; }
-keep class jo.codeide.applog.IControlePont { *; }
-keep class jo.codeide.applog.IControlePont$* { *; }
