// Declare require method which we'll use for importing webpack resources (using ES6 imports will confuse typescript parser)
import {combineReducers, configureStore} from "@reduxjs/toolkit";
import "@openremote/or-app";
import themeCss from "@openremote/theme";
import {AnyAction, appReducer, AppStateKeyed, HeaderConfig, HeaderItem, OrApp, PageProvider, RealmAppConfig} from "@openremote/or-app";
import {headerItemAccount, headerItemLanguage, headerItemLogout, headerItemAssets} from "@openremote/manager/headers";
import {pageAssetsReducer, pageAssetsProvider} from "@openremote/manager/pages/page-assets";
import "./pages/page-fleet";
import {pageFleetProvider} from "./pages/page-fleet";

const rootReducer = combineReducers({
    app: appReducer,
    assets: pageAssetsReducer
});

type RootState = ReturnType<typeof rootReducer>;

export const store = configureStore({
    reducer: rootReducer
});

const orApp = new OrApp(store);

export const DefaultPagesConfig: PageProvider<any>[] = [
    pageFleetProvider(store), // Fleet search and overview page, the landing page
    pageAssetsProvider(store) // Standard manager asset page, opened from a fleet row
];

// Header entry for the fleet search and overview page
export function headerItemFleet<S extends AppStateKeyed, A extends AnyAction>(orApp: OrApp<S>): HeaderItem {
    return {
        icon: "magnify",
        href: "fleet",
        text: "app:fleetPage",
    };
}

export const DefaultHeaderMainMenu: {[name: string]: HeaderItem} = {
    fleet: headerItemFleet(orApp),
    assets: headerItemAssets(orApp)
};

export const DefaultHeaderSecondaryMenu: {[name: string]: HeaderItem} = {
    language: headerItemLanguage(orApp),
    account: headerItemAccount(orApp),
    logout: headerItemLogout(orApp)
};

export const DefaultHeaderConfig: HeaderConfig = {
    mainMenu: Object.values(DefaultHeaderMainMenu),
    secondaryMenu: Object.values(DefaultHeaderSecondaryMenu)
};

export const DefaultRealmConfig: RealmAppConfig = {
    appTitle: "IoT Watch Fleet",
    header: DefaultHeaderConfig,
    styles: ":host > * {--or-app-color2: #F0F0F0; --or-app-color3: #22211f; --or-app-color4: #e3000a; --or-app-color5: #CCCCCC;}",
};

// Configure manager connection and i18next settings.
// No realm here on purpose: each customer has its own realm, so OrApp reads it from the
// URL (/fleet/?realm=<realm>); without the parameter OpenRemote falls back to master.
orApp.managerConfig = {
    loadTranslations: ["app", "or"]
};

// Configure app pages and per realm styling/settings
orApp.appConfig = {
    pages: [...DefaultPagesConfig],
    languages: {
        en: "english",
        nl: "dutch",
        cs: "czech"
    },
    superUserHeader: DefaultHeaderConfig,
    realms: {
        default: {...DefaultRealmConfig, header: DefaultHeaderConfig}
    }
};

// Apply theme to the Manager app
const style = document.createElement("style");
style.id = "orDefaultTheme";
style.textContent = themeCss;
document.head.appendChild(style);

document.body.appendChild(orApp);
