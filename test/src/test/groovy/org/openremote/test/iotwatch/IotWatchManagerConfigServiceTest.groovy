package org.openremote.test.iotwatch

import org.openremote.manager.app.ConfigurationService
import org.openremote.manager.iotwatch.IotWatchManagerConfigService
import org.openremote.manager.iotwatch.IotWatchManagerConfigService.Outcome
import org.openremote.manager.web.ManagerWebService
import org.openremote.model.Container
import org.openremote.model.ContainerService
import org.openremote.model.manager.ManagerAppConfig
import org.openremote.model.util.ValueUtil
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class IotWatchManagerConfigServiceTest extends Specification {

    def setupSpec() { ValueUtil.initialise(null) }

    @TempDir Path dir

    static class StubConfigurationService extends ConfigurationService {
        ManagerAppConfig current
        List<ManagerAppConfig> saved = []
        boolean failSave
        @Override ManagerAppConfig getManagerConfig() { current }
        @Override void saveManagerConfig(ManagerAppConfig c) throws Exception {
            if (failSave) throw new IllegalStateException("read-only")
            saved << c
        }
    }

    static final String PERSISTED = '{"realms":{"default":{"appTitle":"Demo vodoměry | Příklad a.s.","headers":["assets","fleet"]}}}'
    static final String GIT = '{"pages":{"assets":{"tree":{"add":{"typesParent":{"default":{"include":["WaterMeterAsset"]}}}}}}}'

    Path gitFile(String json) {
        def p = dir.resolve("manager_config.json")
        Files.writeString(p, json, StandardCharsets.UTF_8)
        p
    }

    static ManagerAppConfig cfg(String json) { ValueUtil.parse(json, ManagerAppConfig).get() }
    static Map tree(ManagerAppConfig c) { ValueUtil.JSON.convertValue(c, Map) }

    def "a changed git file is saved once with realms kept and Czech characters intact"() {
        given:
        def cs = new StubConfigurationService(current: cfg(PERSISTED))
        when:
        def outcome = IotWatchManagerConfigService.apply(gitFile(GIT), cs)
        then:
        outcome == Outcome.SAVED
        cs.saved.size() == 1
        tree(cs.saved[0]).pages.assets.tree.add.typesParent.default.include == ["WaterMeterAsset"]
        tree(cs.saved[0]).realms.default.appTitle == "Demo vodoměry | Příklad a.s."
        tree(cs.saved[0]).realms.default.headers == ["assets", "fleet"]
    }

    def "an unchanged configuration is not saved"() {
        given:
        def cs = new StubConfigurationService(current: cfg('{"realms":{"default":{"appTitle":"T"}},' + GIT.substring(1)))
        expect:
        IotWatchManagerConfigService.apply(gitFile(GIT), cs) == Outcome.IN_SYNC
        cs.saved.isEmpty()
    }

    def "a fresh install serving the git file itself is in sync"() {
        given:
        def cs = new StubConfigurationService(current: cfg(GIT))
        expect:
        IotWatchManagerConfigService.apply(gitFile(GIT), cs) == Outcome.IN_SYNC
        cs.saved.isEmpty()
    }

    def "a missing git file does nothing"() {
        given:
        def cs = new StubConfigurationService(current: cfg(PERSISTED))
        expect:
        IotWatchManagerConfigService.apply(dir.resolve("missing.json"), cs) == Outcome.NO_GIT_FILE
        cs.saved.isEmpty()
    }

    def "an invalid git file does nothing"() {
        given:
        def cs = new StubConfigurationService(current: cfg(PERSISTED))
        expect:
        IotWatchManagerConfigService.apply(gitFile('{"pages": '), cs) == Outcome.INVALID_GIT_FILE
        cs.saved.isEmpty()
    }

    def "no current configuration means nothing is written"() {
        given:
        def cs = new StubConfigurationService(current: null)
        expect:
        IotWatchManagerConfigService.apply(gitFile(GIT), cs) == Outcome.NO_CURRENT_CONFIG
        cs.saved.isEmpty()
    }

    def "manager in the git file is ignored"() {
        given:
        def cs = new StubConfigurationService(current: cfg('{"manager":{"defaultLanguage":"cs"},' + PERSISTED.substring(1)))
        when:
        def outcome = IotWatchManagerConfigService.apply(gitFile('{"manager":{"defaultLanguage":"en"},' + GIT.substring(1)), cs)
        then:
        outcome == Outcome.SAVED
        tree(cs.saved[0]).manager.defaultLanguage == "cs"
    }

    def "a failing save is reported and does not throw"() {
        given:
        def cs = new StubConfigurationService(current: cfg(PERSISTED), failSave: true)
        expect:
        IotWatchManagerConfigService.apply(gitFile(GIT), cs) == Outcome.SAVE_FAILED
    }

    def "the service initialises after the configuration service"() {
        expect:
        new IotWatchManagerConfigService().getPriority() > ContainerService.DEFAULT_PRIORITY
        new IotWatchManagerConfigService().getPriority() < ContainerService.LOW_PRIORITY
    }

    def "unknown top-level keys in the git file are detected"() {
        expect:
        IotWatchManagerConfigService.unknownTopLevelKeys('{"page":{},"realms":{},"loadLocales":true,"extra":1}') == ["page", "extra"]
        IotWatchManagerConfigService.unknownTopLevelKeys(GIT).isEmpty()
        IotWatchManagerConfigService.unknownTopLevelKeys('[]').isEmpty()
    }

    def "init applies the git file from the custom app docroot"() {
        given:
        gitFile(GIT)
        def cs = new StubConfigurationService(current: cfg(PERSISTED))
        def container = Stub(Container) {
            getConfig() >> [(ManagerWebService.OR_CUSTOM_APP_DOCROOT): dir.toString()]
            getService(ConfigurationService) >> cs
        }
        when:
        new IotWatchManagerConfigService().init(container)
        then:
        cs.saved.size() == 1
        tree(cs.saved[0]).pages.assets.tree.add.typesParent.default.include == ["WaterMeterAsset"]
    }

    def "init does not throw when applying fails"() {
        given:
        gitFile(GIT)
        def container = Stub(Container) {
            getConfig() >> [(ManagerWebService.OR_CUSTOM_APP_DOCROOT): dir.toString()]
            getService(ConfigurationService) >> { throw new IllegalStateException("no service") }
        }
        when:
        new IotWatchManagerConfigService().init(container)
        then:
        noExceptionThrown()
    }
}
