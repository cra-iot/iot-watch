/*
 * IoT Watch manager app: the OpenRemote 1.32.1 manager app (ui/app/manager/src/index.ts upstream)
 * with the fleet page added to the main menu. It is deployed to {customAppDocroot}/manager, where
 * the manager serves it at /manager instead of the built-in app, so keep it in step with upstream
 * when upgrading OpenRemote.
 *
 * Copyright 2026, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import {combineReducers, configureStore} from "@reduxjs/toolkit";
import "@openremote/or-app";
import themeCss from "@openremote/theme";
import {AppConfig, appReducer, HeaderConfig, HeaderItem, OrApp, PageProvider, RealmAppConfig} from "@openremote/or-app";
import type {ManagerAppConfig} from "@openremote/model";
import {
    headerItemAccount, headerItemAssets, headerItemConfiguration, headerItemExport, headerItemGatewayConnection,
    headerItemGatewayTunnel, headerItemInsights, headerItemLanguage, headerItemLogout, headerItemLogs, headerItemMap,
    headerItemNotifications, headerItemProvisioning, headerItemRealms, headerItemRoles, headerItemRules,
    headerItemServices, headerItemUsers,
} from "@openremote/manager/headers";
import {PageMapConfig, pageMapProvider, pageMapReducer} from "@openremote/manager/pages/page-map";
import {PageAssetsConfig, pageAssetsProvider, pageAssetsReducer} from "@openremote/manager/pages/page-assets";
import {pageGatewayProvider} from "@openremote/manager/pages/page-gateway";
import {PageInsightsConfig, pageInsightsProvider} from "@openremote/manager/pages/page-insights";
import {pageServicesProvider} from "@openremote/manager/pages/page-services";
import {PageRulesConfig, pageRulesProvider} from "@openremote/manager/pages/page-rules";
import {PageLogsConfig, pageLogsProvider} from "@openremote/manager/pages/page-logs";
import {pageAccountProvider} from "@openremote/manager/pages/page-account";
import {pageUsersProvider} from "@openremote/manager/pages/page-users";
import {pageRolesProvider} from "@openremote/manager/pages/page-roles";
import {pageRealmsProvider} from "@openremote/manager/pages/page-realms";
import {pageExportProvider} from "@openremote/manager/pages/page-export";
import {pageProvisioningProvider} from "@openremote/manager/pages/page-provisioning";
import {pageConfigurationProvider} from "@openremote/manager/pages/page-configuration";
import {pageAlarmsProvider} from "@openremote/manager/pages/page-alarms";
import {pageGatewayTunnelProvider} from "@openremote/manager/pages/page-gateway-tunnel";
import {pageNotificationsProvider} from "@openremote/manager/pages/page-notifications";
import {pageFleetProvider} from "./pages/page-fleet";

declare let MANAGER_URL: string | undefined;

const rootReducer = combineReducers({
    app: appReducer,
    map: pageMapReducer,
    assets: pageAssetsReducer,
});

type RootState = ReturnType<typeof rootReducer>;

export const store = configureStore({
    reducer: rootReducer,
    // Disable serializableCheck to improve performance in developement (normally disabled in production)
    middleware: (getDefaultMiddleware) => getDefaultMiddleware({serializableCheck: false}),
});

const orApp = new OrApp(store);

export const DefaultPagesConfig: PageProvider<any>[] = [
    pageMapProvider(store),
    pageAssetsProvider(store),
    pageGatewayProvider(store),
    pageGatewayTunnelProvider(store),
    pageLogsProvider(store),
    pageInsightsProvider(store),
    pageServicesProvider(store),
    pageRulesProvider(store),
    pageFleetProvider(store),
    pageAccountProvider(store),
    pageRolesProvider(store),
    pageUsersProvider(store),
    pageRealmsProvider(store),
    pageExportProvider(store),
    pageProvisioningProvider(store),
    pageConfigurationProvider(store),
    pageAlarmsProvider(store),
    pageNotificationsProvider(store),
];

/** Main menu entry of the fleet search and overview page; "fleet" in a realm's headers in manager_config.json. */
export function headerItemFleet(): HeaderItem {
    return {
        icon: "magnify",
        href: "fleet",
        text: "app:fleetPage",
    };
}

export const DefaultHeaderMainMenu: {[name: string]: HeaderItem} = {
    map: headerItemMap(orApp),
    assets: headerItemAssets(orApp),
    rules: headerItemRules(orApp),
    services: headerItemServices(orApp),
    insights: headerItemInsights(orApp),
    fleet: headerItemFleet(),
};

export const DefaultHeaderSecondaryMenu: {[name: string]: HeaderItem} = {
    gatewayConnection: headerItemGatewayConnection(orApp),
    gatewayTunnel: headerItemGatewayTunnel(orApp),
    language: headerItemLanguage(orApp),
    logs: headerItemLogs(orApp),
    notifications: headerItemNotifications(orApp),
    account: headerItemAccount(orApp),
    users: headerItemUsers(orApp),
    roles: headerItemRoles(orApp),
    realms: headerItemRealms(orApp),
    export: headerItemExport(orApp),
    provisioning: headerItemProvisioning(orApp),
    appearance: headerItemConfiguration(orApp),
    logout: headerItemLogout(orApp),
};

export const DefaultHeaderConfig: HeaderConfig = {
    mainMenu: Object.values(DefaultHeaderMainMenu),
    secondaryMenu: Object.values(DefaultHeaderSecondaryMenu),
};

export const DefaultRealmConfig: RealmAppConfig = {
    appTitle: "OpenRemote Manager",
    header: DefaultHeaderConfig,
};

/** Translations of this app ("app" namespace: fleet page, asset and attribute labels), shipped next to index.html. */
const APP_TRANSLATIONS_PATH = "locales/{{lng}}/{{ns}}.json";

// Try and load the app config from JSON and if anything is found amalgamate it with default
const managerURL =
    MANAGER_URL ||
    window.location.protocol +
    "//" +
    window.location.hostname +
    (window.location.port ? ":" + window.location.port : "") +
    window.location.pathname.replace(/\/[^/]+\/?$/, "");
const configURL = managerURL + "/api/master/configuration/manager";

fetch(configURL)
    .then<ManagerAppConfig>(async (result) => {
        let appConfig: ManagerAppConfig;

        if (result.status === 200) {
            appConfig = (await result.json()) as ManagerAppConfig;
        }

        return {...appConfig};
    })
    .then((appConfig: ManagerAppConfig) => {
        // Set locales and load path
        if (!appConfig.manager) {
            appConfig.manager = {};
        }

        // Unlike upstream, the "app" namespace is always loaded and comes from this app unless
        // manager_config.json names another path: the fleet page has no text without it.
        appConfig.manager.loadTranslations = ["app", "or"];
        if (!appConfig.manager.translationsLoadPath) {
            appConfig.manager.translationsLoadPath = APP_TRANSLATIONS_PATH;
        }

        orApp.managerConfig = appConfig.manager;

        orApp.appConfigProvider = (manager) => {
            // Build pages
            let pages: PageProvider<any>[] = [...DefaultPagesConfig];
            const isAdmin = manager.isSuperUser() && manager.username === "admin";
            const applyConfigToAdmin =
                appConfig.manager.applyConfigToAdmin !== undefined ? appConfig.manager.applyConfigToAdmin : true;

            if ((!isAdmin || applyConfigToAdmin) && appConfig.pages) {
                // Replace any supplied page configs
                pages = pages.map((pageProvider) => {
                    const config: {[p: string]: any} | undefined = appConfig.pages[pageProvider.name];

                    switch (pageProvider.name) {
                        case "map": {
                            pageProvider = config ? pageMapProvider(store, config as PageMapConfig) : pageProvider;
                            break;
                        }
                        case "assets": {
                            pageProvider = config ? pageAssetsProvider(store, config as PageAssetsConfig) : pageProvider;
                            break;
                        }
                        case "rules": {
                            const newConfig = (config || {
                                rules: {},
                            }) as PageRulesConfig;
                            if (!newConfig.rules?.notifications) {
                                newConfig.rules.notifications = Object.fromEntries(
                                    Object.entries(appConfig.realms).map((entry) => [entry[0], entry[1].notifications])
                                );
                            }
                            pageProvider = pageRulesProvider(store, newConfig);
                            break;
                        }
                        case "insights": {
                            pageProvider = config ? pageInsightsProvider(store, config as PageInsightsConfig) : pageProvider;
                            break;
                        }
                        case "logs": {
                            pageProvider = config ? pageLogsProvider(store, config as PageLogsConfig) : pageProvider;
                            break;
                        }
                    }

                    return pageProvider;
                });
            }

            const orAppConfig: AppConfig<RootState> = {
                pages,
                superUserHeader: DefaultHeaderConfig,
            };

            // Configure realms
            if (!appConfig.realms) {
                orAppConfig.realms = {
                    default: {...DefaultRealmConfig, header: DefaultHeaderConfig},
                };
            } else {
                orAppConfig.realms = {};
                const defaultRealm = appConfig.realms.default
                    ? {...DefaultRealmConfig, ...appConfig.realms.default}
                    : DefaultRealmConfig;
                orAppConfig.realms.default = defaultRealm;

                Object.entries(appConfig.realms).forEach(([name, realmConfig]) => {
                    const normalisedConfig = {...defaultRealm, ...realmConfig};

                    let headers = DefaultHeaderConfig;

                    if (normalisedConfig.headers) {
                        headers = {
                            mainMenu: [],
                            secondaryMenu: [],
                        };
                        normalisedConfig.headers.forEach((pageName) => {
                            // Insert header
                            if (DefaultHeaderMainMenu.hasOwnProperty(pageName)) {
                                headers.mainMenu.push(DefaultHeaderMainMenu[pageName]);
                            } else if (DefaultHeaderSecondaryMenu.hasOwnProperty(pageName)) {
                                headers.secondaryMenu!.push(DefaultHeaderSecondaryMenu[pageName]);
                            }
                        });
                    }

                    orAppConfig.realms[name] = {...defaultRealm, header: headers, ...(realmConfig as RealmAppConfig)};
                });
            }

            // When the page is not present in the header, move the PageProvider to the end of the array.
            // This is to prevent the landing page for the user not being visible in the header
            const realmAppConfig = orAppConfig.realms[manager.displayRealm] || orAppConfig.realms.default;
            if (realmAppConfig) {
                const headerPaths = [...realmAppConfig.header.mainMenu, ...realmAppConfig.header.secondaryMenu].map(
                    (item) => item.href
                );
                orAppConfig.pages = pages
                    .filter((pageProvider) => headerPaths.includes(pageProvider.name))
                    .concat(pages.filter((pageProvider) => !headerPaths.includes(pageProvider.name)));
            }

            // If the user does not have a preferred language configured (in Keycloak),
            // we need to update it with their preferred language from other sources. (consoles, realm configuration etc.)
            // Check local storage for set language, otherwise use language set in config
            manager.getUserPreferredLanguage().then((userLang: string | undefined) => {
                if (!userLang) {
                    manager
                        .getConsolePreferredLanguage()
                        .then((consoleLang: string | undefined) => {
                            if (consoleLang) {
                                manager.language = consoleLang;
                            } else if (orAppConfig.realms[manager.displayRealm]) {
                                manager.language = orAppConfig.realms[manager.displayRealm].language;
                            } else if (orAppConfig.realms.default) {
                                manager.language = orAppConfig.realms.default.language;
                            } else {
                                manager.language = "en";
                            }
                        })
                        .catch((reason) => {
                            console.error("Failed to initialise app: " + reason);
                        });
                }
            });

            return orAppConfig;
        };

        // Apply theme to the Manager app
        const style = document.createElement("style");
        style.id = "orDefaultTheme";
        style.textContent = themeCss;
        document.head.appendChild(style);

        // Load app
        document.body.appendChild(orApp);
    });
