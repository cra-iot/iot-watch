# IoT Watch

IoT Watch is the CRA (České Radiokomunikace a.s.) customization of [OpenRemote](https://github.com/openremote/openremote), deployed alongside the CRA IoT Platform. It provides data visualization for CRA customers: device messages collected by the IoT Platform are delivered over its HTTP egress into this OpenRemote instance, modelled as assets, and presented in Manager UI dashboards and a custom web application. Each customer is served by a dedicated realm.

> This repository is set up using the [Custom Project template](https://github.com/openremote/custom-project/). This repository uses the same standards and folder structure. More information about how to use this repository as a template to develop your own agents, services, model classes, setup tasks, tests, and new UI apps can be found in the [OpenRemote documentation](https://docs.openremote.io/docs/developer-guide/creating-a-custom-project).

## License and source publication

OpenRemote is licensed under [AGPL-3.0](https://github.com/openremote/openremote/blob/master/LICENSE.txt). Because IoT Watch is offered to customers as a network service, all modifications made in this repository must be publicly available.

- Public mirror: **https://github.com/cra-cz/iot-watch**
- Everything merged to `main` on the CRA GitLab (`origin`) is published to the GitHub mirror automatically via GitLab push mirroring.
- Consequently, never commit anything that cannot be public: secrets, tokens, customer data, or internal-only information. Assume every commit becomes public.
- CRA-specific code in this repository is licensed under AGPL-3.0-or-later as well, see [LICENSE.txt](LICENSE.txt).

## Project context

### Features

- **Custom asset types** (`model/`) representing CRA IoT device data — the main customization of this project.
- **Custom web app** for CRA customers (`ui/app/`) built on OpenRemote UI components.
- **CRA branding** (`deployment/`): logos, Manager UI configuration, map settings, and Keycloak theme.
- **Data ingestion** from the CRA IoT Platform via its HTTP egress, received by a custom, API-key-authenticated HTTP ingest endpoint implemented in `manager/`. The MQTT egress → built-in OpenRemote MQTT agent path (configured in the Manager; no custom protocol code) remains supported but is no longer used.
- **Device decoding** (`manager/`): a decode service turns each message's `rawValue` into typed attributes, independent of how `rawValue` arrived — so it serves both ingest paths. Replaces the earlier Manager-authored Groovy decode rules.
- **Groovy rules** authored in the Manager UI; this repository versions their backups.

OpenRemote itself runs unmodified — the customizations above are delivered to the instances as an extensions JAR and mounted files, not as a custom OpenRemote build (see [Deployment](#deployment-kubernetes)).

### Vocabulary / common terms

- **CRA IoT Platform** — CRA's integration/messaging platform (PaaS) for IoT devices (LoRaWAN, MQTT, UDP ingest). The source of all device data shown in IoT Watch.
- **Egress** — delivery of device messages from the IoT Platform to customer endpoints (HTTP or MQTT). IoT Watch consumes the HTTP egress; the MQTT egress is still supported but no longer used.
- **Manager (UI)** — the OpenRemote administration and dashboard UI, deployed at `https://<hostname>/manager/`.
- **Realm** — a Keycloak/OpenRemote tenant. IoT Watch uses one realm per CRA customer.
- **Asset / attribute** — OpenRemote's data model: devices and their measurements are represented as assets with attributes.
- **Agent** — an OpenRemote component connecting external protocols to assets via agent links; the built-in MQTT agent is supported but no longer used, as ingestion now goes through the HTTP endpoint.
- **Extension (JAR)** — a JAR the manager loads at startup from `/deployment/manager/extensions`; the mechanism by which the custom asset types, services, and setup tasks from this repository reach the unmodified OpenRemote image.

### Company background

CRA (České Radiokomunikace a.s.) operates the CRA IoT Platform, a PaaS that ingests, persists, transforms, and routes IoT device messages. The platform itself does not host customer-facing applications; IoT Watch fills that gap by giving CRA customers visualization of their device data on top of OpenRemote. The project is developed in-house by the CRA IoT team. Write access is limited to CRA employees and contractors; the sources are public through the GitHub mirror (see above).

## Architecture

```
Devices (LoRaWAN / MQTT / UDP)
        │
        ▼
CRA IoT Platform
   ├── HTTP egress → custom HTTP ingest endpoint (API-key) → ingest service  [active]
   └── MQTT egress → built-in OpenRemote MQTT agent                          [supported, unused]
                          │  both write ▼
        rawValue attribute on the asset (custom asset types from model/)
                          │
                          ▼
     decode service → typed attributes (location, battery, currentReading, …)
                          │
                          ▼
      Manager UI dashboards + custom app (ui/app/)
                          ▲
                          │
        CRA customers (one Keycloak realm per customer)
```

IoT Watch does not communicate with devices directly. Device connectivity, message persistence, transformation, and routing are the responsibility of the CRA IoT Platform; IoT Watch only consumes the resulting egress stream — currently over HTTP, with the MQTT egress path still supported but no longer used — and visualizes it.

### Keycloak setup

The identity provider in place is [Keycloak](https://github.com/openremote/keycloak), running in its own container. The default configuration from the repository ([link](https://github.com/openremote/keycloak)) is used. Users are managed locally in Keycloak — there is no federation to the CRA corporate SSO. Each customer gets a dedicated realm.

### Proxy setup

All requests from and towards running services go through the [HAProxy](https://github.com/openremote/proxy) container. The default configuration from the repository ([haproxy.cfg](https://github.com/openremote/proxy/blob/main/haproxy.cfg)) is used.

### Deployment (Kubernetes)

The TEST and PROD instances run the **unmodified official OpenRemote images** on CRA Kubernetes; Helm charts and deployment configuration are maintained in a separate repository on the CRA GitLab. No custom OpenRemote image is built from this repository. Instead, `./gradlew clean installDist` assembles the deployment content under `deployment/build/image/`, and the relevant parts are mounted into the pods:

- `manager/extensions/*.jar` — the extension JARs (custom asset types, custom services, setup tasks). The manager loads every JAR found in `/deployment/manager/extensions`.
- Branding files (`manager/app/`), Keycloak themes, and map settings — mounted the same way.

The `docker-compose.yml` in the root of this repository is the upstream template's deployment profile and serves local development and manual runs only; there the same content is delivered through the `deployment` image and a shared volume instead of a mount.

#### Deployment considerations

- **Version coupling.** The extension JARs are compiled against the OpenRemote version pinned in `gradle/libs.versions.toml`. When the manager image tag is bumped in the Helm charts, the JARs must be rebuilt against the same version — a mismatch can fail at startup or misbehave silently.
- **Startup-only loading.** The manager scans `/deployment/manager/extensions` only at boot. Replacing a JAR on the mounted volume has no effect until the manager pod is restarted; every JAR update must be paired with a rollout restart.
- **Artifact delivery.** Copying JARs to a volume by hand is unversioned and hard to roll back. The recommended variant of the same architecture: build the deployment image this repository already produces (`deployment/Dockerfile`) and run it as an init container that copies `/deployment` into an `emptyDir` shared with the manager container. A deployment then becomes an image tag in Helm — versioned, reproducible, with rollback and pod restart for free.
- **Whole content, not just extensions.** The custom UI app and branding are served by the manager from the same `/deployment` volume, so the mount must carry the complete `deployment/build/image/` content, not only the `extensions/` folder.

## Developer Guide

### Quickstart

Before starting, make sure you have cloned the Git repository locally, as this is required.
Follow the initial guides on the OpenRemote documentation on [preparing the environment](https://docs.openremote.io/docs/developer-guide/preparing-the-environment), [installing and using Docker](https://docs.openremote.io/docs/developer-guide/installing-and-using-docker), and on [setting up an IDE](https://docs.openremote.io/docs/developer-guide/setting-up-an-ide).

Build the Java modules and assemble the deployment image content:

```bash
./gradlew clean installDist
```

The extension JARs end up in `deployment/build/image/manager/extensions/` — this is the artifact mounted into the Kubernetes manager pod.

Run the full local stack (fetch the base compose profile once, see [Docker Compose files](#docker-compose-files)):

```bash
docker build -t openremote/deployment:develop ./deployment/build/
OR_HOSTNAME=localhost OR_ADMIN_PASSWORD=secret DEPLOYMENT_VERSION=develop docker compose -p iot-watch up -d
```

For UI development, start the backend from a dev profile (it pulls `deploy.yml` for the pinned OpenRemote version directly from GitHub) and serve the app with hot reload:

```bash
docker compose -f profile/dev-ui.yml -p iot-watch up -d
yarn install
cd ui/app/custom && yarn run serve   # http://localhost:9000/custom/
```

### Examples

How-to guides for template mechanisms we removed from the sources live in [`docs/examples/`](docs/examples/):

- [Custom REST endpoint](docs/examples/custom-rest-endpoint.md) — adding a custom JAX-RS endpoint to the manager API (the template's `CustomEndpointResource` example).

### Upstream repository synchronization

This repository lives on the CRA GitLab (`origin`, `git@INTERNAL-GITLAB-HOST:iot-platform/other/iot-watch.git`) and is based on the [openremote/custom-project](https://github.com/openremote/custom-project) template (`upstream`). Day-to-day work is committed and pushed to `origin` as usual; changes from the upstream template are pulled in manually when needed.

#### One-time setup (per clone)

Add the upstream remote as fetch-only, so an accidental `git push upstream` fails with a clear error:

```bash
git remote add upstream https://github.com/openremote/custom-project.git
git remote set-url --push upstream DISABLED
```

If the upstream history has not been merged into this repository yet, the very first merge must connect the two unrelated histories:

```bash
git fetch upstream
git merge upstream/main --allow-unrelated-histories
# resolve conflicts (e.g. README.md), then: git add <files> && git commit
git push origin main
```

#### Manual upstream sync (recurring)

Whenever newer upstream template changes are wanted:

```bash
git fetch upstream
git merge upstream/main
# resolve conflicts if any, commit
git push origin main
```

Notes:
- After the first merge the histories are connected, so `--allow-unrelated-histories` is no longer needed.
- Always merge (do not rebase) when syncing from upstream — `main` is published on `origin` and rebasing would require a force-push.
- Do not add the GitHub repository as a second push URL on `origin`; keeping it as a separate fetch-only `upstream` remote is what provides "push to CRA GitLab, pull from GitHub manually".

### Docker Compose files

In the `profile` directory you can find different Docker Compose files, each serving a different purpose. To be able to use them, you'll need to download a copy of the `deploy.yml` file from the main OpenRemote repository and place it in the `openremote/profile` directory, to ensure you always have the latest version of the file:

```bash
mkdir -p openremote/profile && curl -L https://github.com/openremote/openremote/raw/refs/heads/master/profile/deploy.yml -o openremote/profile/deploy.yml
```

### Environment variables

| Key                  | Containers            | Description                                                                                                                       | Default  |
|----------------------|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------|----------|
| `OR_HOSTNAME`        | All services          | **(REQUIRED)** FQDN hostname of where this instance will be exposed (localhost, IP address or public domain)                      | -        |
| `OR_ADMIN_PASSWORD`  | `keycloak`, `manager` | **(REQUIRED)** Initial admin user password                                                                                        | -        |
| `DEPLOYMENT_VERSION` | `deployment`          | **(REQUIRED)** The custom project version in use. This tag is used for building and deploying the artifacts from this repository. | -        |
| `MANAGER_VERSION`    | `manager`             | The OpenRemote version in use.                                                                                                    | 'latest' |
| `KEYCLOAK_VERSION`   | `keycloak`            | The Keycloak version in use.                                                                                                      | 'latest' |
| `PROXY_VERSION`      | `proxy`               | The HAProxy version in use.                                                                                                       | 'latest' |

A list of all environment variables from OpenRemote can be found [here](https://github.com/openremote/openremote/blob/master/profile/deploy.yml).

## Deployments / environments

Both environments run the unmodified official OpenRemote images on CRA Kubernetes, deployed via the Helm charts in the separate deployment repository. This repository only supplies the mounted artifacts (extension JARs, branding files, UI app bundle); updates are rolled out through the CRA deployment process.

### `test`

Used for development and testing before releasing to production.
- This environment is only used for development purposes, so it can be offline at any time.
- There is no guarantee that data will be persisted in the long term.
- **OpenRemote Manager:** internal URL - see the deployment (Helm) repository

### `production`

The live, customer-facing instance with a guarantee of stability and data persistence.
- **OpenRemote Manager:** internal URL - see the deployment (Helm) repository

## Project setup TODO

Remaining setup steps for this repository:

- [ ] Add a `.gitlab-ci.yml` (Gradle build/test + UI build) on the CRA GitLab; the template's GitHub Actions workflow (`.github/workflows/ci_cd.yml`) stays dormant on the mirror.
- [ ] Implement the first CRA asset types in `model/` (replacing the `CustomAsset` example) and regenerate the TypeScript model (`./gradlew :ui:component:model:generateTypeScript`).
- [ ] Define where Manager-authored Groovy rules are backed up in this repository (e.g. a `rules/` directory) and how the backup is refreshed.
