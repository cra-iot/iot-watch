package org.openremote.manager.iotwatch;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.openremote.model.manager.ManagerAppConfig;
import org.openremote.model.util.ValueUtil;

/**
 * Merges the git-owned keys (pages, languages, loadLocales) of a ManagerAppConfig onto a persisted
 * copy while preserving realms and manager. The realms field is owned by the Appearance page and
 * manager is never taken from git, because OpenRemote's typed serialisation (every read and save
 * of the file) writes ManagerConfig by field and would materialise its primitive fields
 * (loadIcons, loadDescriptors, applyConfigToAdmin) as false, breaking the Manager UI; it therefore
 * stays whatever the persisted copy holds.
 */
public final class ManagerConfigMerge {

    /** The top-level keys taken from git, in the order they are reported. */
    public static final List<String> GIT_KEYS = List.of("pages", "languages", "loadLocales");

    private ManagerConfigMerge() {
    }

    /**
     * Result of a config merge: the merged config and the git-owned keys whose value differs
     * from the persisted input.
     */
    public record Result(ManagerAppConfig merged, List<String> changedKeys) {
        public boolean changed() {
            return !changedKeys.isEmpty();
        }
    }

    /**
     * Merge git-owned keys from the git config onto the persisted config.
     * Returns a new ManagerAppConfig that is a deep copy of the persisted config with only
     * pages, languages and loadLocales replaced by deep copies of the git values, so any other
     * field (including ones a future OpenRemote version adds) is kept. Neither input is mutated
     * and the result shares no mutable state with them.
     *
     * @param persisted the user-persisted config (owns realms and manager)
     * @param git       the version-controlled config (owns pages, languages, loadLocales)
     * @return a Result containing the merged config and the keys that changed
     */
    public static Result merge(ManagerAppConfig persisted, ManagerAppConfig git) {
        ManagerAppConfig merged = deepCopy(persisted);
        ManagerAppConfig gitCopy = deepCopy(git);
        merged.setPages(gitCopy.getPages());
        merged.setLanguages(gitCopy.getLanguages());
        merged.setLoadLocales(gitCopy.isLoadLocales());

        JsonNode before = ValueUtil.JSON.valueToTree(persisted);
        JsonNode after = ValueUtil.JSON.valueToTree(merged);
        List<String> changedKeys = GIT_KEYS.stream()
                .filter(key -> !Objects.equals(before.get(key), after.get(key)))
                .toList();
        return new Result(merged, changedKeys);
    }

    /**
     * Compare two ManagerAppConfig objects by their JSON serialization.
     *
     * @param a first config
     * @param b second config
     * @return true if both serialize to the same JSON tree, false otherwise
     */
    public static boolean sameJson(ManagerAppConfig a, ManagerAppConfig b) {
        return ValueUtil.JSON.valueToTree(a).equals(ValueUtil.JSON.valueToTree(b));
    }

    private static ManagerAppConfig deepCopy(ManagerAppConfig config) {
        try {
            return ValueUtil.JSON.treeToValue(ValueUtil.JSON.valueToTree(config), ManagerAppConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not copy the manager configuration", e);
        }
    }
}
