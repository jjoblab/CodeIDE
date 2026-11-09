<?xml version="1.0" encoding="utf-8"?>
<androidx.drawerlayout.widget.DrawerLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/tiroir_racine"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/barre_outils"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="?attr/colorSurface"
            android:elevation="4dp"
            app:title="@string/app_name" />

{{#if avecNavigation}}
        <!-- Le NavHostFragment héberge les destinations du graphe
             res/navigation/graphe_principal.xml. -->
        <androidx.fragment.app.FragmentContainerView
            android:id="@+id/conteneur_fragments"
            android:name="androidx.navigation.fragment.NavHostFragment"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            app:defaultNavHost="true"
            app:navGraph="@navigation/graphe_principal" />
{{#else}}
        <androidx.fragment.app.FragmentContainerView
            android:id="@+id/conteneur_fragments"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />
{{/if}}
    </LinearLayout>

    <com.google.android.material.navigation.NavigationView
        android:id="@+id/vue_navigation"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        app:menu="@menu/tiroir" />
</androidx.drawerlayout.widget.DrawerLayout>
