package org.openremote.manager.iotwatch;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.resource.AuthenticationManagementResource;
import org.keycloak.admin.client.resource.IdentityProvidersResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configures CRA SSO login in one Keycloak realm: a first login flow that only links an SSO identity
 * to a user that already exists in the realm (same e-mail or username) and never creates one, plus
 * the OpenID Connect identity provider bound to that flow. No mappers: the existing realm user keeps
 * its username, name, e-mail and roles. Re-applying updates the provider in place; a flow that someone
 * extended by hand is refused, because an extra step such as "Create user if unique" would defeat
 * the allow-list.
 */
public class SsoRealmConfigurer {

    public static final String DETECT_EXISTING = "idp-detect-existing-broker-user";
    public static final String AUTO_LINK = "idp-auto-link";
    public static final List<String> EXECUTIONS = List.of(DETECT_EXISTING, AUTO_LINK);
    public static final String REQUIRED = "REQUIRED";

    public enum Outcome { CREATED, UPDATED }

    private final SsoConfig config;
    private final OidcDiscovery discovery;

    public SsoRealmConfigurer(SsoConfig config, OidcDiscovery discovery) {
        this.config = config;
        this.discovery = discovery;
    }

    public Outcome apply(RealmResource realm) {
        ensureFlow(realm.flows());
        return ensureProvider(realm.identityProviders());
    }

    protected void ensureFlow(AuthenticationManagementResource flows) {
        String alias = config.flowAlias();
        boolean exists = flows.getFlows().stream().anyMatch(flow -> alias.equals(flow.getAlias()));
        if (!exists) {
            AuthenticationFlowRepresentation flow = new AuthenticationFlowRepresentation();
            flow.setAlias(alias);
            flow.setProviderId("basic-flow");
            flow.setTopLevel(true);
            flow.setBuiltIn(false);
            flow.setDescription("CRA SSO: only users that already exist in the realm may log in; link them automatically");
            try (Response response = flows.createFlow(flow)) {
                check(response, "create flow '" + alias + "'");
            }
        }

        List<AuthenticationExecutionInfoRepresentation> executions = flows.getExecutions(alias);
        List<String> present = executions.stream().map(SsoRealmConfigurer::providerOf).toList();
        List<String> unexpected = present.stream().filter(provider -> !EXECUTIONS.contains(provider)).toList();
        if (!unexpected.isEmpty()) {
            throw new IllegalStateException("Flow '" + alias + "' contains unexpected executions " + unexpected
                + "; an extra step would defeat the pre-provisioned-users rule, fix the flow by hand first");
        }
        boolean added = false;
        for (String provider : EXECUTIONS) {
            if (!present.contains(provider)) {
                flows.addExecution(alias, Map.of("provider", provider));
                added = true;
            }
        }
        if (added) {
            executions = flows.getExecutions(alias);
        }
        List<String> order = executions.stream().map(SsoRealmConfigurer::providerOf).toList();
        if (!order.equals(EXECUTIONS)) {
            throw new IllegalStateException("Flow '" + alias + "' executions are in the order " + order
                + ", expected " + EXECUTIONS + "; reorder them by hand");
        }
        for (AuthenticationExecutionInfoRepresentation execution : executions) {
            if (!REQUIRED.equals(execution.getRequirement())) {
                execution.setRequirement(REQUIRED);
                flows.updateExecutions(alias, execution);
            }
        }
    }

    /** A nested sub-flow execution has no provider id; name it so the refusal message stays readable. */
    private static String providerOf(AuthenticationExecutionInfoRepresentation execution) {
        return execution.getProviderId() == null ? "<sub-flow " + execution.getDisplayName() + ">" : execution.getProviderId();
    }

    protected Outcome ensureProvider(IdentityProvidersResource providers) {
        Optional<IdentityProviderRepresentation> existing = providers.findAll().stream()
            .filter(provider -> config.alias().equals(provider.getAlias()))
            .findFirst();
        IdentityProviderRepresentation representation = representation(existing.map(IdentityProviderRepresentation::getConfig).orElse(null));
        if (existing.isPresent()) {
            representation.setInternalId(existing.get().getInternalId());
            // An operator who switched the provider off in the console keeps that decision across restarts.
            representation.setEnabled(existing.get().isEnabled());
            providers.get(config.alias()).update(representation);
            return Outcome.UPDATED;
        }
        try (Response response = providers.create(representation)) {
            check(response, "create identity provider '" + config.alias() + "'");
        }
        return Outcome.CREATED;
    }

    protected IdentityProviderRepresentation representation(Map<String, String> existingConfig) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (existingConfig != null) {
            settings.putAll(existingConfig);
        }
        settings.put("issuer", discovery.issuer());
        settings.put("authorizationUrl", discovery.authorizationUrl());
        settings.put("tokenUrl", discovery.tokenUrl());
        settings.put("jwksUrl", discovery.jwksUrl());
        if (discovery.userInfoUrl() != null) {
            settings.put("userInfoUrl", discovery.userInfoUrl());
        } else {
            settings.remove("userInfoUrl");
        }
        settings.put("useJwksUrl", "true");
        settings.put("validateSignature", "true");
        settings.put("clientId", config.clientId());
        settings.put("clientSecret", config.clientSecret());
        settings.put("clientAuthMethod", "client_secret_post");
        settings.put("defaultScope", "openid email profile");
        settings.put("syncMode", "IMPORT");
        settings.put("backchannelSupported", "false");
        settings.put("hideOnLoginPage", "false");
        settings.put("logoutUrl", config.logout() && discovery.endSessionUrl() != null ? discovery.endSessionUrl() : "");

        IdentityProviderRepresentation representation = new IdentityProviderRepresentation();
        representation.setAlias(config.alias());
        representation.setDisplayName(config.displayName());
        representation.setProviderId("oidc");
        representation.setEnabled(true);
        representation.setTrustEmail(true);
        representation.setStoreToken(false);
        representation.setAddReadTokenRoleOnCreate(false);
        representation.setLinkOnly(false);
        representation.setHideOnLogin(false);
        representation.setFirstBrokerLoginFlowAlias(config.flowAlias());
        representation.setPostBrokerLoginFlowAlias(null);
        representation.setConfig(settings);
        return representation;
    }

    protected static void check(Response response, String action) {
        if (response.getStatus() < 300) {
            return;
        }
        String detail;
        try {
            detail = response.hasEntity() ? response.readEntity(String.class) : "";
        } catch (RuntimeException e) {
            Object entity = response.getEntity();
            detail = entity == null ? "" : String.valueOf(entity);
        }
        throw new IllegalStateException(action + " failed: HTTP " + response.getStatus() + " " + detail);
    }
}
