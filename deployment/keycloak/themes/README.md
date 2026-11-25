## Custom Keycloak themes
Add a directory for each custom theme (the directory name is the theme name) and add the required theme templates within; you can use the default [openremote theme](https://github.com/openremote/keycloak/tree/main/theme/src/main/resources/theme/openremote) as a template alternatively refer to the keycloak themes documentation.

This themes directory can then be volume mapped into the keycloak container at `/deployment/keycloak/themes`.

### Development
To be able to see the custom theme in development (i.e. when using `dev-testing.yml` compose profile) you need to create a copy of `openremote/profile/dev-testing.yml` in the custom project `profile` directory.

### CRA login theme (`cra`)
IoT Watch login theme based on the CRA Single Sign-On page: white header with the CRA logo, the IoT Watch product name and the language switcher, a large page title above a white card on a grey background with a decorative device network, the realm display name (falling back to the realm name) at the top of the card, the orange CRA button, and the grey footer band. Only `login/template.ftl` is overridden. Every page template (login, password reset, password update, OTP, ...) comes from the Keycloak `base` theme and is styled through the `kc*` classes in `login/theme.properties` and `login/resources/css/styles.css`. Keep `template.ftl` in sync with the base template's macro signature and nested sections when Keycloak is upgraded.

- Locales: `en`, `cs`. Theme-specific texts live in `login/messages/`.
- Font: Nunito (SIL Open Font License 1.1, `login/resources/fonts/OFL.txt`). Do not add commercially licensed fonts, because this repository is published.
- Activation: set the login theme of a realm to `cra` (Manager UI > Realms, or Keycloak admin console > Realm settings > Themes). Enable internationalization on the realm to show the language switcher.

#### CRA account sign-in
When a realm has an identity provider, the login pages show a "CRA account" block above the local form, separated from it by "or". It appears on the `login` and `login-username` pages (the `pageId` values in Keycloak 26.7) and only when the realm allows password login and has at least one visible identity provider. Realms without a provider look as before.

- The provider with alias `cra-sso` gets the button label "CRA account" (message `craAccount`), whatever its display name is. Any other alias shows its own display name on the button.
- With the block shown, the local "Sign in" button turns secondary (outlined), so the identity provider is the primary action.
- The block replaces the base `socialProviders` section on these two pages. On Keycloak upgrades, compare it with the `socialProviders` section of the base `login.ftl` and `login-username.ftl` and update `login/template.ftl` if the base markup or conditions changed.

### Delivery to Keycloak (Kubernetes)
`./gradlew clean installDist` also packs every theme in this directory into `deployment/build/keycloak/cra-keycloak-theme.jar` (task `:deployment:keycloakThemeJar`; `META-INF/keycloak-themes.json` is generated from the directory layout). Keycloak loads theme JARs from `/opt/keycloak/providers`, so the Kubernetes deployment mounts this single file there instead of a theme directory tree. The JAR is handed to the Keycloak Helm chart base64-encoded on one line (`base64 < cra-keycloak-theme.jar | tr -d '\n'`), the same way the extension JARs are handed to the manager chart. Keycloak picks up a new or changed JAR only on restart.

### Customer-specific login themes
A customer theme extends `cra` and overrides only what differs, so it keeps the layout and every page template:

```
theme/<customer>/login/theme.properties     parent=cra
                                            import=common/keycloak
                                            locales=en,cs
                                            styles=css/styles.css css/<customer>.css
theme/<customer>/login/resources/css/<customer>.css   e.g. :root { --cra-orange: #2BB3A3; }
META-INF/keycloak-themes.json               {"themes":[{"name":"<customer>","types":["login"]}]}
```

Customer branding (logos, names, colours agreed with the customer) is customer data and must not be committed here, because this repository is published. Build customer theme JARs outside this repository. The Keycloak Helm chart accepts any number of theme JARs, each mounted next to `cra-keycloak-theme.jar`, and the realm is then switched to the customer theme in the admin console.
