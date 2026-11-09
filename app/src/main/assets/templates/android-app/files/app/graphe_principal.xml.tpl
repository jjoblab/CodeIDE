<?xml version="1.0" encoding="utf-8"?>
<!-- Graphe de navigation principal : les identifiants de destination
     reprennent ceux du menu du tiroir (si activé) pour que NavigationUI
     câble automatiquement la NavigationView. -->
<navigation
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/graphe_principal"
    app:startDestination="@+id/{{#if estActiviteTiroir}}tiroir_accueil{{#else}}fragment_bienvenue{{/if}}">
{{#if estActiviteTiroir}}
    <fragment
        android:id="@+id/tiroir_accueil"
        android:name="{{packageName}}.FragmentAccueil"
        android:label="@string/tiroir_accueil"
        tools:layout="@layout/fragment_accueil">
        <argument
            android:name="espace"
            app:argType="string"
            android:defaultValue="accueil" />
    </fragment>
    <fragment
        android:id="@+id/tiroir_apropos"
        android:name="{{packageName}}.FragmentAccueil"
        android:label="@string/tiroir_apropos"
        tools:layout="@layout/fragment_accueil">
        <argument
            android:name="espace"
            app:argType="string"
            android:defaultValue="apropos" />
    </fragment>
{{#else}}
    <fragment
        android:id="@+id/fragment_bienvenue"
        android:name="{{packageName}}.BienvenueFragment"
        android:label="@string/app_name"
        tools:layout="@layout/fragment_bienvenue" />
{{/if}}
</navigation>
