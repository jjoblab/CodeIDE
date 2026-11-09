{{#if estGradle}}
# {{t:gitignore.gradle}}
.gradle/
build/
{{#else}}
# {{t:gitignore.maven}}
target/
{{/if}}

# {{t:gitignore.commun}}
.idea/
*.iml
.DS_Store
Thumbs.db
