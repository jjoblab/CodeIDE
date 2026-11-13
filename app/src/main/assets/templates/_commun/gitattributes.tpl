{{#if gradle}}
# {{t:gitattributes.entete}}
* text=auto eol=lf
gradlew text eol=lf
*.bat text eol=crlf
*.jar binary
{{#else}}
# {{t:gitattributes.entete}}
* text=auto eol=lf
*.bat text eol=crlf
{{/if}}
