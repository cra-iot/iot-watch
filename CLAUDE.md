# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

IoT Watch — CRA's customization of OpenRemote, based on the [openremote/custom-project](https://github.com/openremote/custom-project) template. It runs next to the CRA IoT Platform and visualizes device data for CRA customers: the platform's HTTP egress posts device messages to a custom, API-key-authenticated HTTP ingest endpoint, which writes each message to the asset's `rawValue`; a decode service then turns `rawValue` into typed attributes. Data is modelled as custom asset types, and customers (one Keycloak realm each, local Keycloak users, no CRA SSO) view it in Manager UI dashboards and on the Fleet page of the IoT Watch manager app. The MQTT egress → built-in OpenRemote MQTT agent path is still supported but no longer used — both paths write the same `rawValue`, so decode serves both.

TEST/PROD run the **unmodified official OpenRemote images** on CRA Kubernetes (Helm charts in a separate repository). This repo is never built into a custom OpenRemote image — it produces extension JARs (custom asset types, services) and the manager app, which CI packs into one content image (`iot-watch/deployment`). On Kubernetes an init container copies the image content into an emptyDir, and the manager pod mounts only `/deployment/manager/extensions` and the app folder from it; the manager loads every JAR in that extensions folder. The setup and agent JARs and the Keycloak themes are not deployed. `manager_config.json` and the map settings are owned by the environment's deployment configuration, not by this repo's image. Groovy rules are authored in the Manager UI and only backed up here.

## License constraint — everything here becomes public

OpenRemote is AGPL-3.0, so all modifications are published on the public GitHub mirror https://github.com/cra-cz/iot-watch. Never commit secrets, tokens, customer data, or internal-only information; assume every commit on `main` will be public.

## Git workflow

- `origin` = CRA GitLab (`git@INTERNAL-GITLAB-HOST:iot-platform/other/iot-watch.git`), where daily work is pushed.
- `upstream` = the OpenRemote custom-project template on GitHub, fetch-only (push URL is `DISABLED` on purpose). Sync by `git fetch upstream && git merge upstream/main` — always merge, never rebase (see README, "Upstream repository synchronization").
- Keep README changes conflict-friendly: the template's section order is preserved deliberately so upstream merges stay small.

## Build and run commands

Java (Gradle multi-project; `settings.gradle` auto-includes every directory containing a `build.gradle`):

```bash
./gradlew clean installDist        # build all modules; extension JARs land in deployment/build/image/manager/extensions/ (CI packs the model and manager JARs into the deployment content image)
./gradlew test                     # run tests (test/ module)
./gradlew :ui:component:model:generateTypeScript   # regenerate ui/component/model/src/model.ts after changing model/ classes
```

UI (Yarn 4 workspaces `ui/app/*`, `ui/component/*`; run `yarn install` at repo root first):

```bash
docker compose -f profile/dev-ui.yml -p iot-watch up -d   # backend for UI dev (pulls pinned deploy.yml from GitHub)
cd ui/app/fleet && yarn run serveNoModelBuild --env managerUrl=http://localhost:8080 --env keycloakUrl=http://localhost:8081/auth
                                                          # dev server, http://localhost:9000/fleet/?realm=<realm>
yarn run build                                            # production build (in the app directory)
yarn workspace @openremote/fleet test                     # fleet app unit tests (vitest)
```

Plain `yarn run serve` also rebuilds the model and currently fails on existing tsc errors. Restart the dev server after any Gradle build, which regenerates `model.ts`.

Full local stack: `./gradlew clean installDist`, then `docker build -t openremote/deployment:develop ./deployment/build/` and `OR_HOSTNAME=localhost OR_ADMIN_PASSWORD=secret DEPLOYMENT_VERSION=develop docker compose -p iot-watch up -d` (requires `openremote/profile/deploy.yml`, see README).

## Architecture and where changes go

Java modules mirror OpenRemote's package layout (`org.openremote.*`) and plug into the manager via `META-INF/services` registrations — a new provider/service class does nothing until it is listed in the corresponding `META-INF/services` file:

- `model/` — **custom asset types, the main customization.** Asset classes + `AssetModelProvider` registration. Also holds the JAX-RS contracts in `org.openremote.model.iotwatch`: the HTTP ingest (`IngestResource` + envelope DTOs) and the fleet search (`FleetResource`, `FleetQuery`, `FleetQueryResult`, `FleetRow`, `FleetColumns`). After any change here, regenerate the TypeScript model so the UI sees the new types.
- `agent/` — custom protocol agents (template example only, not used). Data ingestion does not go through a custom agent: the active path is the HTTP ingest endpoint (see `manager/`). The built-in OpenRemote MQTT agent (configured in the Manager, no custom code) remains supported but is no longer used.
- `manager/` — custom manager services (`ContainerService` registration). Holds the IoT Watch runtime services in `org.openremote.manager.iotwatch`: `IotWatchIngestService` (the API-key-authenticated HTTP ingest endpoint, enabled by manager configuration; writes device messages to `rawValue`) and `IotWatchDecodeService` (reacts to committed `rawValue` events and writes typed attributes via a `DecoderRegistry`/`DecodePlan`; replaced the former Groovy decode rules), and `IotWatchFleetService` (`POST /api/{realm}/fleet/query` behind the Fleet page; reads only through `authorizeAssetQuery`, never filters on or returns `rawValue`, and drops matches on attributes a restricted user cannot read; primary registers overridable via `OR_IOTWATCH_FLEET_PRIMARY_REGISTER`).
- `setup/` — setup tasks (`SetupTasks` registration). Still the unmodified template: it creates realm `custom` and user `custom` (`CUSTOM_USER_PASSWORD`) and nothing IoT-Watch-specific. Customer realms, users and assets are created in the Manager UI; local demo data comes from `docs/runbooks/seed-demo-data.py` (local only).
- `ui/app/fleet` — the IoT Watch manager app: a copy of the OpenRemote manager app's entry point (`src/index.ts`, keep in step with upstream on upgrades) plus the Lit-based fleet search and overview page as a "Fleet" main menu item. It installs to `deployment/build/image/manager/app/manager`, where the manager serves it at `/manager` instead of the built-in app (custom app docroot first); `ui/app/custom-react` — React example from the template; `ui/component/model` — TypeScript model generated from `model/` (do not edit `src/model.ts` by hand); `ui/component/rest` — REST client.
- `deployment/` — deployment content for local docker compose, packed into the `deployment` image (on Kubernetes only the extension JARs and the manager app are delivered, through the content image built in CI; the Manager UI configuration, Keycloak themes and map settings below are not): Manager UI configuration (`manager/app/manager_config.json`, logos), Keycloak themes, map settings. All three are still template content: CRA branding and the CRA Keycloak theme are not merged yet. If a realm in `manager_config.json` restricts its menu with `headers`, it must list `"fleet"`.
- `profile/` — dev Docker Compose profiles (`dev-ui.yml` for UI work, `dev-testing.yml` for Keycloak theme work).

Removed template examples are preserved as how-to guides in `docs/examples/` — check there before reinventing a mechanism. Currently: `custom-rest-endpoint.md` (custom JAX-RS endpoint: `*Resource.java` interface in `model/`, implementation registered via `ManagerWebService.addApiSingleton` in a `ContainerService`, typed TS client from `./gradlew :ui:component:rest:generateTypeScript`; with zero `*Resource.java` files in `model/` the build writes a dummy `ApiClient` — this is expected).

## Asset model changes

OpenRemote copies descriptor meta into an attribute when the asset is created and never re-merges it; required attributes are validated on every save; optional attributes are not added to existing assets automatically, and rules cannot create attributes. Every `model/` change that affects existing assets therefore needs a migration script (pattern: `docs/runbooks/migrate-*.sh`, local only — dry run by default, `--apply` to write). When adding an attribute, migrate first and then let the decoder or rule write it; when removing one, change the rule first and then migrate.

## CI and testing

GitLab CI (`.gitlab-ci.yml`): the `auto-release` component computes `NEXT_VERSION`; `build:gradle` runs `./gradlew test`, `installDist` (UI apps excluded) and the TypeScript model/REST client generation; `build:fleet-app` builds the manager app with Yarn from the Nexus npm proxy; `build:deployment:image` pushes the `iot-watch/deployment` content image (the model and manager JARs under `/content/extensions`, the manager app under `/content/apps/manager`; tag `$NEXT_VERSION` on `main`, `<sha>-<branch>-<pipeline>` elsewhere), referenced by the Helm value `or.custom.image`. MR and tag pipelines run no jobs, and CI does not run the vitest UI tests. `.github/workflows/ci_cd.yml` is the upstream template workflow and is not used for CRA builds.

Container specs (`IngestEndpointTest`, `FleetEndpointTest`) run only with `OR_CONTAINER_TESTS=true` and wipe the local docker dev stack (realm `custom`, demo data) — never run them against a stack you care about. Use `docs/runbooks/check-fleet-search.py` (read-only) to check the fleet endpoint against seeded data.

## Version pinning

The OpenRemote version (currently 1.32.1) is pinned in three places that must stay in sync when upgrading: `gradle/libs.versions.toml` (`openremote = "..."`), `@openremote/*` dependency versions in `ui/app/*/package.json`, and the `x-base` URL anchors in `profile/dev-*.yml`. The project's own version comes from git tags via the axion-release plugin (`./gradlew currentVersion`).

The manager also checks the Keycloak version at startup: 1.32.x refuses to start against anything older than Keycloak 26.7.0, so the Keycloak image must be bumped alongside it (Helm charts for TEST/PROD, `openremote/keycloak:latest` locally).

## Communication

Always communicate in English. All content you create — code comments, commit
messages, documentation, identifiers — must be in English, even when existing
code or files contain Czech.
