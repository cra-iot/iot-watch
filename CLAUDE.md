# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

IoT Watch — CRA's customization of OpenRemote, based on the [openremote/custom-project](https://github.com/openremote/custom-project) template. It runs next to the CRA IoT Platform and visualizes device data for CRA customers: the platform's HTTP egress posts device messages to a custom, API-key-authenticated HTTP ingest endpoint, which writes each message to the asset's `rawValue`; a decode service then turns `rawValue` into typed attributes. Data is modelled as custom asset types, and customers (one Keycloak realm each, local Keycloak users, no CRA SSO) view it in Manager UI dashboards and a custom web app. The MQTT egress → built-in OpenRemote MQTT agent path is still supported but no longer used — both paths write the same `rawValue`, so decode serves both.

TEST/PROD run the **unmodified official OpenRemote images** on CRA Kubernetes (Helm charts in a separate repository). This repo is never built into a custom OpenRemote image — it produces extension JARs (custom asset types, services, setup tasks) plus deployment files (branding, UI app) that are mounted into the pods; the manager loads every JAR in `/deployment/manager/extensions`. Groovy rules are authored in the Manager UI and only backed up here.

## License constraint — everything here becomes public

OpenRemote is AGPL-3.0, so all modifications are published on the public GitHub mirror https://github.com/cra-cz/iot-watch. Never commit secrets, tokens, customer data, or internal-only information; assume every commit on `main` will be public.

## Git workflow

- `origin` = CRA GitLab (`git@INTERNAL-GITLAB-HOST:iot-platform/other/iot-watch.git`), where daily work is pushed.
- `upstream` = the OpenRemote custom-project template on GitHub, fetch-only (push URL is `DISABLED` on purpose). Sync by `git fetch upstream && git merge upstream/main` — always merge, never rebase (see README, "Upstream repository synchronization").
- Keep README changes conflict-friendly: the template's section order is preserved deliberately so upstream merges stay small.

## Build and run commands

Java (Gradle multi-project; `settings.gradle` auto-includes every directory containing a `build.gradle`):

```bash
./gradlew clean installDist        # build all modules; extension JARs land in deployment/build/image/manager/extensions/ (the artifact mounted into the K8s manager pod)
./gradlew test                     # run tests (test/ module)
./gradlew :ui:component:model:generateTypeScript   # regenerate ui/component/model/src/model.ts after changing model/ classes
```

UI (Yarn 4 workspaces `ui/app/*`, `ui/component/*`; run `yarn install` at repo root first):

```bash
docker compose -f profile/dev-ui.yml -p iot-watch up -d   # backend for UI dev (pulls pinned deploy.yml from GitHub)
cd ui/app/custom && yarn run serve                        # dev server, http://localhost:9000/custom/
yarn run build                                            # production build (in the app directory)
```

Full local stack: `./gradlew clean installDist`, then `docker build -t openremote/deployment:develop ./deployment/build/` and `OR_HOSTNAME=localhost OR_ADMIN_PASSWORD=secret DEPLOYMENT_VERSION=develop docker compose -p iot-watch up -d` (requires `openremote/profile/deploy.yml`, see README).

## Architecture and where changes go

Java modules mirror OpenRemote's package layout (`org.openremote.*`) and plug into the manager via `META-INF/services` registrations — a new provider/service class does nothing until it is listed in the corresponding `META-INF/services` file:

- `model/` — **custom asset types, the main customization.** Asset classes + `AssetModelProvider` registration. Also holds the HTTP ingest JAX-RS contract (`IngestResource`) and its envelope DTOs. After any change here, regenerate the TypeScript model so the UI sees the new types.
- `agent/` — custom protocol agents (template example only, not used). Data ingestion does not go through a custom agent: the active path is the HTTP ingest endpoint (see `manager/`). The built-in OpenRemote MQTT agent (configured in the Manager, no custom code) remains supported but is no longer used.
- `manager/` — custom manager services (`ContainerService` registration). Holds the two core runtime services: `IotWatchIngestService` (the API-key-authenticated HTTP ingest endpoint, enabled by manager configuration; writes device messages to `rawValue`) and `IotWatchDecodeService` (reacts to committed `rawValue` events and writes typed attributes via a `DecoderRegistry`/`DecodePlan`; replaced the former Groovy decode rules).
- `setup/` — setup tasks (`SetupTasks` registration) that provision realms, users, and assets on a clean install.
- `ui/app/custom` — Lit-based custom app (this is the customer-facing app); `ui/app/custom-react` — React example; `ui/component/model` — TypeScript model generated from `model/` (do not edit `src/model.ts` by hand); `ui/component/rest` — REST client.
- `deployment/` — deployment content mounted into the manager/keycloak pods on Kubernetes (for local docker compose it is packed into the `deployment` image instead): CRA branding (`manager/app/manager_config.json`, logos), Keycloak themes, map settings.
- `profile/` — dev Docker Compose profiles (`dev-ui.yml` for UI work, `dev-testing.yml` for Keycloak theme work).

Removed template examples are preserved as how-to guides in `docs/examples/` — check there before reinventing a mechanism. Currently: `custom-rest-endpoint.md` (custom JAX-RS endpoint: `*Resource.java` interface in `model/`, implementation registered via `ManagerWebService.addApiSingleton` in a `ContainerService`, typed TS client from `./gradlew :ui:component:rest:generateTypeScript`; with zero `*Resource.java` files in `model/` the build writes a dummy `ApiClient` — this is expected).

## Version pinning

The OpenRemote version (currently 1.27.0) is pinned in three places that must stay in sync when upgrading: `gradle/libs.versions.toml` (`openremote = "..."`), `@openremote/*` dependency versions in `ui/app/*/package.json`, and the `x-base` URL anchors in `profile/dev-*.yml`. The project's own version comes from git tags via the axion-release plugin (`./gradlew currentVersion`).

## Communication

Always communicate in English. All content you create — code comments, commit
messages, documentation, identifiers — must be in English, even when existing
code or files contain Czech.
