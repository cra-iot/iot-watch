package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.ManagerConfigMerge
import org.openremote.model.manager.ManagerAppConfig
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class ManagerConfigMergeTest extends Specification {

    def setupSpec() { ValueUtil.initialise(null) }

    static ManagerAppConfig cfg(String json) { ValueUtil.parse(json, ManagerAppConfig).get() }
    static Map tree(ManagerAppConfig c) { ValueUtil.JSON.convertValue(c, Map) }

    static final String REALMS = '"realms":{"default":{"appTitle":"Demo vodoměry | Příklad a.s.",' +
        '"styles":":host > * {--or-app-color1:#FFFFFF;}","logo":"/api/master/configuration/manager/image/default/logo.png",' +
        '"headers":["language","logout","assets","fleet"],"notifications":{"languages":["cs","en"],"defaultLanguage":"cs"}},' +
        '"demo":{"appTitle":"Demo Praha","language":"en"}}'
    static final String PAGES = '"pages":{"assets":{"tree":{"add":{"typesParent":{"default":{"include":["WaterMeterAsset","GroupAsset"]}}}}}}'

    def "pages from git are added to a persisted config that only has realms"() {
        when:
        def r = ManagerConfigMerge.merge(cfg('{' + REALMS + '}'), cfg('{' + PAGES + '}'))
        then:
        r.changed()
        tree(r.merged()).pages.assets.tree.add.typesParent.default.include == ["WaterMeterAsset", "GroupAsset"]
        tree(r.merged()).realms == tree(cfg('{' + REALMS + '}')).realms
    }

    def "pages from git replace persisted pages"() {
        when:
        def r = ManagerConfigMerge.merge(
            cfg('{' + REALMS + ',"pages":{"assets":{"tree":{"add":{"typesParent":{"default":{"exclude":["LightAsset"]}}}}}}}'),
            cfg('{' + PAGES + '}'))
        then:
        r.changedKeys() == ["pages"]
        tree(r.merged()).pages.assets.tree.add.typesParent.default == [include: ["WaterMeterAsset", "GroupAsset"]]
    }

    def "an empty git file removes pages and languages and resets loadLocales, realms survive"() {
        when:
        def r = ManagerConfigMerge.merge(cfg('{"loadLocales":true,"languages":{"cs":"Čeština"},' + REALMS + ',' + PAGES + '}'), cfg('{}'))
        then:
        r.changed()
        def t = tree(r.merged())
        !t.containsKey("pages")
        !t.containsKey("languages")
        t.loadLocales == false
        t.realms == tree(cfg('{' + REALMS + '}')).realms
    }

    def "languages and loadLocales come from git"() {
        when:
        def r = ManagerConfigMerge.merge(cfg('{' + REALMS + '}'), cfg('{"loadLocales":true,"languages":{"cs":"Čeština","en":"English"}}'))
        then:
        r.changedKeys() == ["languages", "loadLocales"]
        tree(r.merged()).languages == [cs: "Čeština", en: "English"]
        tree(r.merged()).loadLocales == true
    }

    def "git realms and manager are ignored, persisted ones are kept"() {
        given:
        def persisted = cfg('{' + REALMS + ',"manager":{"defaultLanguage":"cs"}}')
        when:
        def r = ManagerConfigMerge.merge(persisted, cfg('{"realms":{"other":{"appTitle":"X"}},"manager":{"defaultLanguage":"en"},' + PAGES + '}'))
        then:
        tree(r.merged()).realms == tree(persisted).realms
        tree(r.merged()).manager.defaultLanguage == "cs"
    }

    def "identical content is reported unchanged and the inputs are not mutated"() {
        given:
        def persisted = cfg('{' + REALMS + ',' + PAGES + '}')
        def git = cfg('{' + PAGES + '}')
        def same = ManagerConfigMerge.merge(persisted, git)
        def persistedBefore = tree(persisted)
        def gitBefore = tree(git)
        def changing = cfg('{"loadLocales":true,"languages":{"cs":"Čeština"}}')
        def changingBefore = tree(changing)
        when:
        def r = ManagerConfigMerge.merge(persisted, changing)
        then:
        !same.changed()
        ManagerConfigMerge.sameJson(same.merged(), persisted)
        r.changed()
        !r.merged().is(persisted)
        tree(persisted) == persistedBefore
        tree(changing) == changingBefore
        tree(git) == gitBefore
    }

    def "a fresh install where the current config is the git file itself is unchanged"() {
        when:
        def r = ManagerConfigMerge.merge(cfg('{' + REALMS + ',' + PAGES + '}'), cfg('{' + REALMS + ',' + PAGES + '}'))
        then:
        !r.changed()
    }

    def "the merged config shares no mutable state with the git config"() {
        given:
        def git = cfg('{"languages":{"cs":"Čeština"},' + PAGES + '}')
        def r = ManagerConfigMerge.merge(cfg('{' + REALMS + '}'), git)
        when:
        r.merged().getLanguages().put("en", "English")
        r.merged().getPages().put("map", [:])
        then:
        git.getLanguages() == [cs: "Čeština"]
        !git.getPages().containsKey("map")
    }
}
