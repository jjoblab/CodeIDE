<?xml version="1.0" encoding="utf-8"?>
<resources>
{{#if estActiviteTiroir}}
    <style name="Theme.{{appName|resourceName}}" parent="Theme.Material3.DayNight.NoActionBar">
{{#else}}
    <style name="Theme.{{appName|resourceName}}" parent="Theme.Material3.DayNight">
{{/if}}
        <item name="colorPrimary">@color/violet_500</item>
        <item name="colorSecondary">@color/sarcelle_200</item>
    </style>
</resources>
