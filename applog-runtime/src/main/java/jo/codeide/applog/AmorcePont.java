package jo.codeide.applog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Amorce du pont : le provider fusionné dans le manifeste de l'app cible
 * démarre le pont dès que le processus existe — AVANT l'application
 * elle-même (les providers s'installent entre {@code attachBaseContext}
 * et {@code onCreate} de l'Application), donc avant la première ligne de
 * journal utile.
 *
 * <p>Le provider n'expose AUCUNE donnée : {@code query}, {@code insert},
 * {@code update} et {@code delete} ne sont jamais appelés (l'autorité
 * n'appartient qu'à CodeIDE, et personne d'autre ne la connaît) —
 * ils répondent les valeurs neutres par contrat.
 */
public final class AmorcePont extends ContentProvider {

    @Override
    public boolean onCreate() {
        // Hors build debug, retourne false : le provider est « mort »,
        // aucune trace, aucune activité — la bibliothèque est invisible.
        return Pont.demarrer(getContext());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
