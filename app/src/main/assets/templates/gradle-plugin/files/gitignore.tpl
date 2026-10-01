{{#if gradle}}
# {{t:gitignore.gradle}}
.gradle/
build/
.kotlin/
local.properties
out/
{{/if}}
{{#if maven}}
# {{t:gitignore.maven}}
target/
{{/if}}
# {{t:gitignore.commun}}
.idea/
*.iml
.vscode/
.DS_Store
.codeide/local/
