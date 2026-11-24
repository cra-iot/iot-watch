package org.openremote.manager.iotwatch;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration of the CRA SSO identity brokering, read from the manager environment.
 * The service is enabled only when the issuer, client id and client secret are all set.
 *
 * @param issuer       issuer URL of the CRA SSO realm, e.g. {@code https://sso.cra.cz/auth/realms/CRA}
 * @param clientId     client id of IoT Watch in that realm
 * @param clientSecret the client's secret (never logged)
 * @param alias        alias of the identity provider in every realm; it is part of the broker redirect URI
 * @param displayName  label of the button on the login page
 * @param logout       whether logging out of IoT Watch also ends the CRA SSO session
 * @param realms       realms to configure; empty means every realm, master included
 */
public record SsoConfig(String issuer, String clientId, String clientSecret, String alias, String displayName,
                        boolean logout, Set<String> realms) {

    public static final String OR_IOTWATCH_SSO_ISSUER = "OR_IOTWATCH_SSO_ISSUER";
    public static final String OR_IOTWATCH_SSO_CLIENT_ID = "OR_IOTWATCH_SSO_CLIENT_ID";
    public static final String OR_IOTWATCH_SSO_CLIENT_SECRET = "OR_IOTWATCH_SSO_CLIENT_SECRET";
    public static final String OR_IOTWATCH_SSO_ALIAS = "OR_IOTWATCH_SSO_ALIAS";
    public static final String OR_IOTWATCH_SSO_DISPLAY_NAME = "OR_IOTWATCH_SSO_DISPLAY_NAME";
    public static final String OR_IOTWATCH_SSO_REALMS = "OR_IOTWATCH_SSO_REALMS";
    public static final String OR_IOTWATCH_SSO_LOGOUT = "OR_IOTWATCH_SSO_LOGOUT";

    public static final String DEFAULT_ALIAS = "cra-sso";
    public static final String DEFAULT_DISPLAY_NAME = "CRA SSO";

    /** Returns the configuration, or empty when the issuer, client id or secret is missing. */
    public static Optional<SsoConfig> parse(Map<String, ?> config) {
        String issuer = text(config, OR_IOTWATCH_SSO_ISSUER);
        String clientId = text(config, OR_IOTWATCH_SSO_CLIENT_ID);
        String clientSecret = text(config, OR_IOTWATCH_SSO_CLIENT_SECRET);
        if (issuer == null || clientId == null || clientSecret == null) {
            return Optional.empty();
        }
        while (issuer.endsWith("/")) {
            issuer = issuer.substring(0, issuer.length() - 1);
        }
        String alias = Optional.ofNullable(text(config, OR_IOTWATCH_SSO_ALIAS)).orElse(DEFAULT_ALIAS);
        String displayName = Optional.ofNullable(text(config, OR_IOTWATCH_SSO_DISPLAY_NAME)).orElse(DEFAULT_DISPLAY_NAME);
        boolean logout = "true".equalsIgnoreCase(text(config, OR_IOTWATCH_SSO_LOGOUT));
        Set<String> realms = Optional.ofNullable(text(config, OR_IOTWATCH_SSO_REALMS))
            .map(list -> Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet()))
            .orElse(Set.of());
        return Optional.of(new SsoConfig(issuer, clientId, clientSecret, alias, displayName, logout, realms));
    }

    /** Alias of the first login flow bound to the identity provider. */
    public String flowAlias() {
        return alias + "-first-login";
    }

    public boolean appliesTo(String realm) {
        return realms.isEmpty() || realms.contains(realm);
    }

    private static String text(Map<String, ?> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
