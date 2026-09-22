Project: {{projectName}}
Package: {{packageName}}
Group: {{groupId}}
Version: {{version}}
Year: {{year}}
Author: {{author}}
Slug: {{slug}}
Lang: {{contentLanguage}}
License: {{license}}
Type: {{projectType}}
Extras:{{#if withExtras}} ON{{#else}} OFF{{/if}}
App:{{#if estApplication}} yes{{#else}} no{{/if}}
Literal: \{{notATag}}
i18n: {{t:msg.hello}}
kotlin: {{description|kotlinString}}
java: {{description|javaString}}
xml: {{description|xml}}
json: {{description|json}}
toml: {{description|tomlString}}
md: {{description|md}}
lower: {{projectName|lower}}
upper: {{projectName|upper}}
