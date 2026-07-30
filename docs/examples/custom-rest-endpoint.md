# Custom REST endpoint

How to add a custom JAX-RS endpoint to the OpenRemote manager API. The
custom-project template shipped an example of this (`CustomEndpointResource` +
`CustomData` in `model/`); it was removed from this repository because we do
not use it. This document preserves the example and the full recipe.

Upstream originals (openremote/custom-project, AGPL-3.0):

- [CustomEndpointResource.java](https://github.com/openremote/custom-project/blob/main/model/src/main/java/org/openremote/model/custom/CustomEndpointResource.java)
- [CustomData.java](https://github.com/openremote/custom-project/blob/main/model/src/main/java/org/openremote/model/custom/CustomData.java)

## How it works

A custom endpoint has three parts:

1. **JAX-RS interface in `model/`** — the shared contract. The file name must
   end with `Resource.java`: `ui/component/rest/build.gradle` scans `model/`
   for `**/*Resource.java` to decide whether to generate a TypeScript client.
2. **Implementation in `manager/`** — registered with the manager's web
   service from a `ContainerService` (which itself must be listed in
   `manager/src/main/resources/META-INF/services/org.openremote.model.ContainerService`).
3. **Generated TypeScript client** — `./gradlew :ui:component:rest:generateTypeScript`
   produces a typed `restclient.ts` for the custom web app. With zero
   `*Resource.java` files in `model/`, the build writes a dummy
   `export class ApiClient {}` instead, so removing all endpoints is safe.

The endpoint is served under `https://<hostname>/api/<realm>/<path>`.

## 1. Interface + DTO in `model/`

```java
package org.openremote.model.iotwatch;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("custom")
public interface CustomEndpointResource {

    @POST
    void submitData(CustomData customData);
}
```

```java
package org.openremote.model.iotwatch;

public class CustomData {

    protected String name;
    protected Integer age;

    protected CustomData() {
    }

    public String getName() {
        return name;
    }

    public Integer getAge() {
        return age;
    }
}
```

## 2. Implementation and registration in `manager/`

The implementation typically extends `org.openremote.manager.web.ManagerWebResource`
to get realm and authentication context helpers (see the built-in
`AssetResourceImpl` for a complete reference). Secure methods with
`@RolesAllowed` the same way the built-in resources do.

Register the singleton in a `ContainerService` (verified against
OpenRemote 1.27.0 — `ManagerWebService.addApiSingleton`):

```java
package org.openremote.manager.iotwatch;

import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;

public class IotWatchService implements ContainerService {

    @Override
    public void init(Container container) throws Exception {
        container.getService(ManagerWebService.class)
                .addApiSingleton(new CustomEndpointResourceImpl(/* ... */));
    }

    @Override
    public void start(Container container) throws Exception {
    }

    @Override
    public void stop(Container container) throws Exception {
    }
}
```

## 3. Regenerate the clients

```bash
./gradlew :ui:component:model:generateTypeScript   # model.ts (DTO types)
./gradlew :ui:component:rest:generateTypeScript    # restclient.ts (API client)
```

## Further reading

- OpenRemote docs: [Working on the UI and APIs](https://docs.openremote.io/docs/developer-guide/working-on-ui-and-apis)
- Git history of this repository (the removed template files).
