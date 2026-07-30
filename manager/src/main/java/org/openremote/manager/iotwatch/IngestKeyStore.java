package org.openremote.manager.iotwatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Per-realm API keys parsed from the OR_IOTWATCH_INGEST_KEYS value
 * ("realm1:key1,realm2:key2"). Key comparison is constant-time; key values are
 * never logged (this repository is publicly mirrored).
 */
public class IngestKeyStore {

    private static final Logger LOG = Logger.getLogger(IngestKeyStore.class.getName());

    protected final Map<String, byte[]> keysByRealm = new HashMap<>();

    protected IngestKeyStore() {
    }

    public static IngestKeyStore parse(String config) {
        IngestKeyStore store = new IngestKeyStore();
        if (config == null || config.isBlank()) {
            return store;
        }
        for (String entry : config.split(",")) {
            String[] parts = entry.split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                LOG.warning("Ignoring malformed OR_IOTWATCH_INGEST_KEYS entry (expected realm:key)");
                continue;
            }
            store.keysByRealm.put(parts[0].trim(), parts[1].trim().getBytes(StandardCharsets.UTF_8));
        }
        return store;
    }

    public boolean isEmpty() {
        return keysByRealm.isEmpty();
    }

    public boolean check(String realm, String apiKey) {
        if (realm == null || apiKey == null) {
            return false;
        }
        byte[] expected = keysByRealm.get(realm);
        return expected != null && MessageDigest.isEqual(expected, apiKey.getBytes(StandardCharsets.UTF_8));
    }

    public Set<String> realms() {
        return Collections.unmodifiableSet(keysByRealm.keySet());
    }
}
