package org.openremote.manager.iotwatch;

import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.manager.security.ManagerIdentityProvider;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.security.Realm;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps CRA SSO login configured in the realms (OR_IOTWATCH_SSO_* env vars, see {@link SsoConfig}):
 * on startup every existing realm is reconciled and every realm created later is configured as soon
 * as it exists, through the manager's own Keycloak admin client. The configuration itself is
 * {@link SsoRealmConfigurer}: a detect-and-link first login flow and the OIDC identity provider.
 * Without the env vars the service does nothing and the realms keep local logins only; it never
 * removes a provider (use docs/runbooks/add-sso-to-realm.py --remove for that).
 */
public class IotWatchSsoService implements ContainerService {

    private static final Logger LOG = Logger.getLogger(IotWatchSsoService.class.getName());

    protected SsoConfig config;
    protected ManagerKeycloakIdentityProvider identityProvider;
    protected MessageBrokerService messageBrokerService;
    protected OidcDiscovery discovery;

    @Override
    public void init(Container container) throws Exception {
        config = SsoConfig.parse(container.getConfig()).orElse(null);
        if (config == null) {
            LOG.info("CRA SSO is not configured (" + SsoConfig.OR_IOTWATCH_SSO_ISSUER + ", "
                + SsoConfig.OR_IOTWATCH_SSO_CLIENT_ID + ", " + SsoConfig.OR_IOTWATCH_SSO_CLIENT_SECRET
                + "), realms keep local logins only");
            return;
        }
        ManagerIdentityProvider provider = container.getService(ManagerIdentityService.class).getIdentityProvider();
        if (!(provider instanceof ManagerKeycloakIdentityProvider keycloak)) {
            LOG.warning("CRA SSO needs the Keycloak identity provider, disabled");
            config = null;
            return;
        }
        identityProvider = keycloak;
        messageBrokerService = container.getService(MessageBrokerService.class);
    }

    @Override
    public void start(Container container) throws Exception {
        if (config == null) {
            return;
        }
        reconcile(Arrays.stream(identityProvider.getRealms()).map(Realm::getName).toList());

        messageBrokerService.getContext().addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(PersistenceService.PERSISTENCE_TOPIC)
                    .routeId("Persistence-IotWatchSso")
                    .filter(PersistenceService.isPersistenceEventForEntityType(Realm.class))
                    .process(exchange -> {
                        @SuppressWarnings("unchecked")
                        PersistenceEvent<Realm> event = exchange.getIn().getBody(PersistenceEvent.class);
                        onRealmChange(event);
                    });
            }
        });
    }

    @Override
    public void stop(Container container) throws Exception {
    }

    /** Configures every listed realm the configuration admits; one failing realm does not stop the others. */
    protected void reconcile(Collection<String> realmNames) {
        for (String realm : realmNames) {
            if (config.appliesTo(realm)) {
                configureQuietly(realm);
            } else {
                LOG.fine("CRA SSO: realm '" + realm + "' is not in " + SsoConfig.OR_IOTWATCH_SSO_REALMS + ", skipped");
            }
        }
    }

    protected void onRealmChange(PersistenceEvent<Realm> event) {
        if (config == null || event.getCause() != PersistenceEvent.Cause.CREATE) {
            return;
        }
        String realm = event.getEntity().getName();
        if (config.appliesTo(realm)) {
            configureQuietly(realm);
        }
    }

    protected void configureQuietly(String realm) {
        try {
            configureRealm(realm);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "CRA SSO: configuring realm '" + realm + "' failed", e);
        }
    }

    protected void configureRealm(String realm) throws Exception {
        SsoRealmConfigurer configurer = new SsoRealmConfigurer(config, discovery());
        SsoRealmConfigurer.Outcome outcome = identityProvider.getRealms(realms -> configurer.apply(realms.realm(realm)));
        LOG.info("CRA SSO: identity provider '" + config.alias() + "' " + outcome.name().toLowerCase() + " in realm '" + realm + "'");
    }

    protected synchronized OidcDiscovery discovery() throws IOException, InterruptedException {
        if (discovery == null) {
            discovery = OidcDiscovery.fetch(config.issuer());
            LOG.info("CRA SSO: endpoints of " + config.issuer() + " read from its discovery document");
        }
        return discovery;
    }
}
