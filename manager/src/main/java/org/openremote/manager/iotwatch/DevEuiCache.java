package org.openremote.manager.iotwatch;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory devEui to asset id index, one map per realm, kept fresh from asset
 * persistence events. EUIs are normalized to uppercase. One EUI may map to
 * multiple asset ids — a configuration error the endpoint reports as HTTP 409.
 */
public class DevEuiCache {

    protected record RealmEui(String realm, String eui) {
    }

    protected final Map<String, Map<String, Set<String>>> idsByEuiByRealm = new ConcurrentHashMap<>();
    protected final Map<String, RealmEui> entryByAssetId = new ConcurrentHashMap<>();

    public Set<String> resolve(String realm, String eui) {
        if (realm == null || eui == null) {
            return Set.of();
        }
        return Set.copyOf(idsByEuiByRealm
            .getOrDefault(realm, Map.of())
            .getOrDefault(normalize(eui), Set.of()));
    }

    // put/remove are synchronized because they maintain a cross-map invariant
    // (forward map + reverse map + shared mutable Set) that individual
    // concurrent-map operations cannot make atomic; resolve stays lock-free.
    public synchronized void put(String realm, String eui, String assetId) {
        remove(assetId);
        String normalized = normalize(eui);
        idsByEuiByRealm
            .computeIfAbsent(realm, r -> new ConcurrentHashMap<>())
            .computeIfAbsent(normalized, e -> ConcurrentHashMap.newKeySet())
            .add(assetId);
        entryByAssetId.put(assetId, new RealmEui(realm, normalized));
    }

    public synchronized void remove(String assetId) {
        RealmEui previous = entryByAssetId.remove(assetId);
        if (previous == null) {
            return;
        }
        Map<String, Set<String>> realmMap = idsByEuiByRealm.get(previous.realm());
        if (realmMap != null) {
            Set<String> ids = realmMap.get(previous.eui());
            if (ids != null) {
                ids.remove(assetId);
                if (ids.isEmpty()) {
                    realmMap.remove(previous.eui(), ids);
                }
            }
        }
    }

    public static String normalize(String eui) {
        return eui.trim().toUpperCase(Locale.ROOT);
    }
}
