# CodeIDE — règles R8.
#
# Étape 0 : aucune règle personnalisée. La minification release est activée
# dans la convention (section 6 du prompt) et les keep-rules nécessaires
# seront ajoutées — une par une, chaque fois justifiée — à l'étape 13
# (audit release) lors de l'intégration de Room, DataStore, kotlinx.serialization
# et du moteur de templates.

# Bibliothèque d'édition code-editor (prompt compagnon, section 2.3) :
# conserver la surface d'API publique de cel-ui/cel-core — vues, sessions,
# documents et thèmes sont accédés par réflexion depuis les layouts et les
# styles ; l'audit release de l'étape 18 vérifiera ces règles en conditions
# réelles (assembleRelease + parcours de l'espace de travail).
-keep public class jo.codeeditor.view.EditorView { *; }
-keep public class jo.codeeditor.view.chrome.EditorTheme { *; }
-keep public class jo.codeeditor.view.EditorMetrics { *; }
-keep public class jo.codeeditor.view.input.EditorKeymap { *; }
-keep public class jo.codeeditor.session.EditorSession { *; }
-keep public class jo.codeeditor.document.EditorDocument { *; }
-keep public class jo.codeeditor.document.Selection { *; }
-keep public class jo.codeeditor.shift.DiagnosticShift$* { *; }
-keep public class jo.codeeditor.completion.CompletionSession$* { *; }
-keep public class jo.codeeditor.navigation.NavigationMenu$* { *; }
-keep public class jo.codeeditor.cache.LineRenderCache$* { *; }
