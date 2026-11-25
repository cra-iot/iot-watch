package org.openremote.manager.iotwatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import org.openremote.manager.app.ConfigurationService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.manager.ManagerAppConfig;
import org.openremote.model.util.MapAccess;
import org.openremote.model.util.ValueUtil;

/**
 * OpenRemote reads a single manager_config.json, and the copy saved by the Appearance page shadows
 * the one shipped in the ConfigMap. This service re-applies the git-owned keys of the ConfigMap file
 * onto the persisted configuration at every manager start.
 */
public class IotWatchManagerConfigService implements ContainerService {

    private static final Logger LOG = Logger.getLogger(IotWatchManagerConfigService.class.getName());

    public static final String MANAGER_CONFIG_FILE = "manager_config.json";

    /** Top-level keys a manager_config.json may carry; anything else is most likely a typo. */
    static final Set<String> KNOWN_KEYS = Set.of("pages", "languages", "loadLocales", "realms", "manager");

    public enum Outcome { NO_GIT_FILE, INVALID_GIT_FILE, NO_CURRENT_CONFIG, IN_SYNC, SAVED, SAVE_FAILED }

    @Override
    public int getPriority() {
        // After ConfigurationService (default priority), which loads the persisted copy in its init().
        return DEFAULT_PRIORITY + 10;
    }

    @Override
    public void init(Container container) throws Exception {
        try {
            String docroot = MapAccess.getString(container.getConfig(),
                    ManagerWebService.OR_CUSTOM_APP_DOCROOT, ManagerWebService.OR_CUSTOM_APP_DOCROOT_DEFAULT);
            apply(Paths.get(docroot, MANAGER_CONFIG_FILE).toAbsolutePath(), container.getService(ConfigurationService.class));
        } catch (Exception e) {
            // A failed merge must never keep the manager from starting.
            LOG.log(Level.WARNING, "Could not apply git-managed " + MANAGER_CONFIG_FILE + " keys", e);
        }
    }

    @Override
    public void start(Container container) throws Exception {
        // No-op: everything happens in init().
    }

    @Override
    public void stop(Container container) throws Exception {
        // No-op.
    }

    /**
     * Applies the git-owned keys of {@code gitFile} onto the persisted configuration. Outcomes:
     * <ul>
     * <li>{@code NO_GIT_FILE}: the git file does not exist, nothing is applied.</li>
     * <li>{@code INVALID_GIT_FILE}: the git file cannot be read or parsed, nothing is applied.</li>
     * <li>{@code NO_CURRENT_CONFIG}: there is no persisted configuration to apply onto, nothing is applied.</li>
     * <li>{@code IN_SYNC}: the persisted configuration already matches the git keys, nothing is saved.</li>
     * <li>{@code SAVED}: the merged configuration was saved.</li>
     * <li>{@code SAVE_FAILED}: saving the merged configuration failed, the failure is logged.</li>
     * </ul>
     * The only exception that can escape is an {@link IllegalStateException} from the deep copy in
     * {@link ManagerConfigMerge#merge}; it is unreachable in practice, because the object was just
     * produced by the same mapper, and {@link #init} catches and logs it.
     */
    public static Outcome apply(Path gitFile, ConfigurationService configurationService) {
        if (!Files.isRegularFile(gitFile)) {
            LOG.info("No git-managed manager config at " + gitFile + ", nothing to apply");
            return Outcome.NO_GIT_FILE;
        }

        String gitJson;
        try {
            gitJson = Files.readString(gitFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Could not read git-managed manager config " + gitFile + ": " + e, e);
            return Outcome.INVALID_GIT_FILE;
        }
        ManagerAppConfig git = ValueUtil.parse(gitJson, ManagerAppConfig.class).orElse(null);
        if (git == null) {
            LOG.severe("Git-managed manager config " + gitFile + " could not be parsed as a manager configuration (the cause, if any, is logged just before this line), nothing applied");
            return Outcome.INVALID_GIT_FILE;
        }

        List<String> unknownKeys = unknownTopLevelKeys(gitJson);
        if (!unknownKeys.isEmpty()) {
            LOG.warning("Unknown top-level keys " + unknownKeys + " in " + gitFile + " are ignored (misspelt? known keys: "
                    + KNOWN_KEYS + "); a misspelt git-managed key removes that key from the persisted configuration");
        }

        ManagerAppConfig persisted = configurationService.getManagerConfig();
        if (persisted == null) {
            LOG.warning("No current manager configuration to apply " + gitFile + " onto, nothing applied");
            return Outcome.NO_CURRENT_CONFIG;
        }

        if (git.getManager() != null) {
            LOG.warning("manager is not git-managed and is not merged: remove it from " + gitFile
                    + "; on a fresh install without a persisted copy it would disable icons and descriptors in the Manager UI");
        }

        ManagerConfigMerge.Result result = ManagerConfigMerge.merge(persisted, git);
        if (!result.changed()) {
            LOG.info("Git-managed keys " + ManagerConfigMerge.GIT_KEYS + " already match " + gitFile);
            return Outcome.IN_SYNC;
        }

        // The save overwrites the file in place, so record the replaced values for recovery.
        JsonNode previous = ValueUtil.JSON.valueToTree(persisted);
        for (String key : result.changedKeys()) {
            LOG.info("Replacing persisted manager config key " + key + ", previous value: " + previous.get(key));
        }

        try {
            configurationService.saveManagerConfig(result.merged());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not save manager configuration merged from " + gitFile, e);
            return Outcome.SAVE_FAILED;
        }
        LOG.info("Applied " + result.changedKeys() + " from " + gitFile + " onto the persisted manager configuration");
        return Outcome.SAVED;
    }

    /** Top-level keys of {@code json} outside {@link #KNOWN_KEYS}; empty if it is not a JSON object. */
    static List<String> unknownTopLevelKeys(String json) {
        List<String> unknown = new ArrayList<>();
        try {
            JsonNode tree = ValueUtil.JSON.readTree(json);
            if (tree != null && tree.isObject()) {
                tree.fieldNames().forEachRemaining(key -> {
                    if (!KNOWN_KEYS.contains(key)) {
                        unknown.add(key);
                    }
                });
            }
        } catch (IOException e) {
            // Already parsed successfully as a typed config; nothing more to report.
        }
        return unknown;
    }
}
