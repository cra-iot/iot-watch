import { css, html, nothing, TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { AppStateKeyed, Page, PageProvider, router } from "@openremote/or-app";
import { getAssetsRoute } from "@openremote/manager/routes";
import { EnhancedStore } from "@reduxjs/toolkit";
import type { FleetQueryResult } from "model";
// The project's generated client: manager.rest from @openremote/core is the stock OpenRemote
// client and has no FleetResource.
import rest from "rest";
import { resolveHidden } from "../fleet/fleet-columns";
import { DEFAULT_PAGE_SIZE, parsePageSize } from "../fleet/fleet-pager";
import {
    fromUrlParams, mergeSiteOptions, readingFollowUp, singlePrimaryRegister, textValues, toFleetQuery, toUrlParams,
    type SiteOption, type Token,
} from "../fleet/fleet-tokens";
import "../fleet/or-fleet-search";
import "../fleet/or-fleet-results";

export function pageFleetProvider(store: EnhancedStore<AppStateKeyed>): PageProvider<AppStateKeyed> {
    return {
        // The name is also the window title's translation key; "fleet" is a group of keys.
        name: "fleetPage",
        routes: ["fleet"],
        pageCreator: () => new PageFleet(store),
    };
}

/** Hidden table columns of this browser; the share URL's hide= wins over it. */
const HIDDEN_COLUMNS_KEY = "iot-watch.fleet.hiddenColumns";
/** Rows per page of this browser; the share URL's size= wins over it. */
const PAGE_SIZE_KEY = "iot-watch.fleet.pageSize";

@customElement("page-fleet")
export class PageFleet extends Page<AppStateKeyed> {

    static get styles() {
        // language=CSS
        return css`
            /* Centred with a cap, so wide screens get margins instead of a stretched table. */
            :host { display: flex; flex-direction: column; width: 100%; max-width: 1680px; margin: 0 auto;
                min-height: 0; padding: 12px 24px; box-sizing: border-box; }
            .error { color: var(--or-app-color4, #e3000a); padding: 8px 0; }
            .note { color: var(--or-app-color3, #4c4c4c); padding: 8px 0; }
        `;
    }

    @state() protected _tokens: Token[] = [];
    @state() protected _result?: FleetQueryResult;
    @state() protected _loading = false;
    @state() protected _groupBy = "NONE";
    @state() protected _offset = 0;
    @state() protected _pageSize = DEFAULT_PAGE_SIZE;
    @state() protected _sort = { field: "name", descending: false };
    @state() protected _columns?: string[];
    @state() protected _error?: string;
    @state() protected _hidden: string[] = [];
    /** Set when a reading: token could not be applied because no single primary register is known. */
    @state() protected _readingNote = false;
    /** Locations seen in results so far, for the site: token; keeps a chosen site's name after narrowing. */
    @state() protected _sites: SiteOption[] = [];

    /** Drops answers of superseded queries, so a slow earlier response cannot overwrite a newer one. */
    protected _requestId = 0;
    /** Realm being searched; a super user's realm picker changes it, the login realm does not. */
    protected _realm?: string;

    get name(): string {
        return "fleetPage";
    }

    constructor(store: EnhancedStore<AppStateKeyed>) {
        super(store);
    }

    stateChanged(state: AppStateKeyed): void {
        const realm = state.app.realm;
        if (realm === this._realm) { return; }
        const switched = this._realm !== undefined;
        this._realm = realm;
        if (!switched) { return; }
        // Tokens, sites and results name assets of the previous realm: start the search afresh.
        this._tokens = [];
        this._sites = [];
        this._result = undefined;
        this._offset = 0;
        this._readingNote = false;
        this._pushUrl();
        this._runQuery();
    }

    protected _readSetting(key: string): string | null {
        try {
            return window.localStorage.getItem(key);
        } catch {
            // Storage unavailable: the defaults apply.
            return null;
        }
    }

    protected _writeSetting(key: string, value: string): void {
        try {
            window.localStorage.setItem(key, value);
        } catch {
            // Remembering the choice is best effort; the URL still carries it.
        }
    }

    /** A changed hash (an edited URL, back/forward, a pasted link) is a new search; replaceState fires none. */
    protected _onHashChange = () => {
        if (window.location.hash.startsWith("#/fleet")) { this._applyUrl(); }
    };

    connectedCallback(): void {
        super.connectedCallback();
        window.addEventListener("hashchange", this._onHashChange);
    }

    disconnectedCallback(): void {
        window.removeEventListener("hashchange", this._onHashChange);
        super.disconnectedCallback();
    }

    protected firstUpdated(changedProperties: Map<string, any>): void {
        super.firstUpdated?.(changedProperties as any);
        this._applyUrl();
    }

    /** The search the URL describes, everything else at its default; then runs it. */
    protected _applyUrl(): void {
        // The route hash looks like "#/fleet/?q=...": the query string belongs to the page.
        const hash = window.location.hash;
        const restored = fromUrlParams(new URLSearchParams(hash.includes("?") ? hash.slice(hash.indexOf("?") + 1) : ""));
        this._tokens = restored.tokens;
        this._groupBy = restored.groupBy ?? "NONE";
        this._sort = restored.sort ?? { field: "name", descending: false };
        this._columns = restored.columns;
        this._hidden = resolveHidden(restored.hidden, this._readSetting(HIDDEN_COLUMNS_KEY));
        this._pageSize = restored.pageSize ?? parsePageSize(this._readSetting(PAGE_SIZE_KEY)) ?? DEFAULT_PAGE_SIZE;
        this._offset = 0;
        this._runQuery();
    }

    protected _pushUrl(): void {
        const params = toUrlParams(this._tokens, {}, {
            groupBy: this._groupBy, sort: this._sort, columns: this._columns, hidden: this._hidden, pageSize: this._pageSize,
        });
        try {
            // replaceState does not fire hashchange, so the router leaves the page alone. The slash
            // keeps "fleet" the first path segment, which or-header reads to mark the active tab.
            window.history.replaceState(window.history.state, "", `#/fleet/?${params.toString()}`);
        } catch {
            // A restricted history API only costs the shareable link.
        }
    }

    protected async _fetch(primaryRegister: string | undefined): Promise<FleetQueryResult> {
        const query = toFleetQuery(this._tokens, {
            groupBy: this._groupBy,
            offset: this._offset,
            limit: this._pageSize,
            sort: this._sort,
            // An empty list means "reset": send nothing and the server picks its default.
            ...(this._columns?.length ? { columns: this._columns } : {}),
            ...(primaryRegister ? { primaryRegister } : {}),
        });
        if (this._realm) { query.realm = this._realm; }
        const response = await rest.api.FleetResource.query(query);
        return response.data;
    }

    protected async _runQuery(): Promise<void> {
        const requestId = ++this._requestId;
        this._loading = true;
        this._error = undefined;
        try {
            // A reading: token needs the primary register of the one type in play. The previous
            // result usually knows it; when it does not (a shared URL) or it is stale, the result
            // of this query names it and the query runs once more with it.
            let primaryRegister = singlePrimaryRegister(this._result?.columns);
            let result = await this._fetch(primaryRegister);
            if (requestId !== this._requestId) { return; }
            let followUp = readingFollowUp(this._tokens, primaryRegister, result.columns);
            if (followUp.action === "rerun") {
                primaryRegister = followUp.primaryRegister;
                result = await this._fetch(primaryRegister);
                if (requestId !== this._requestId) { return; }
                followUp = readingFollowUp(this._tokens, primaryRegister, result.columns);
            }
            this._readingNote = followUp.action !== "none";
            this._result = result;
            this._rememberSites(result);
        } catch (e) {
            // Inline message, never a dialog: the bar must stay usable so the user can retry.
            if (requestId === this._requestId) { this._error = "app:fleet.error.queryFailed"; }
        } finally {
            if (requestId === this._requestId) { this._loading = false; }
        }
    }

    /**
     * Parent facets name the locations; a parent the caller may not read gets an empty label,
     * which the search bar shows as an unnamed location in the current language.
     */
    protected _rememberSites(result: FleetQueryResult): void {
        this._sites = mergeSiteOptions(this._sites, result.facets?.parents ?? [], "");
    }

    /** Names the searched values nothing matched; only for several values, one is obvious from the result. */
    protected _renderNotFound(): TemplateResult | typeof nothing {
        const count = textValues(this._tokens).length;
        const missing = this._result?.unmatchedText ?? [];
        if (count < 2 || !missing.length) { return nothing; }
        return html`<div class="note" role="status"><or-translate value="app:fleet.search.notFound"
            .options=${{ missing: missing.length, count, values: missing.join(", ") }}></or-translate></div>`;
    }

    protected render(): TemplateResult {
        return html`
            <or-fleet-search .tokens=${this._tokens} .sites=${this._sites}
                             @or-fleet-search-change=${(e: CustomEvent) => {
                                 this._tokens = e.detail.tokens;
                                 this._offset = 0;
                                 this._pushUrl();
                                 this._runQuery();
                             }}></or-fleet-search>
            ${this._error ? html`<div class="error"><or-translate value=${this._error}></or-translate></div>` : nothing}
            ${this._renderNotFound()}
            ${this._readingNote ? html`<div class="note" role="status"><or-translate value="app:fleet.reading.needsOneType"></or-translate></div>` : nothing}
            <or-fleet-results .result=${this._result} .groupBy=${this._groupBy} .loading=${this._loading}
                              .sort=${this._sort} .hiddenColumns=${this._hidden}
                              @or-fleet-row-click=${(e: CustomEvent) =>
                                  router.navigate(getAssetsRoute(false, e.detail.assetId))}
                              @or-fleet-page=${(e: CustomEvent) => {
                                  this._offset = e.detail.offset;
                                  this._runQuery();
                              }}
                              @or-fleet-page-size=${(e: CustomEvent) => {
                                  this._pageSize = e.detail.limit;
                                  this._writeSetting(PAGE_SIZE_KEY, String(this._pageSize));
                                  this._offset = 0;
                                  this._pushUrl();
                                  this._runQuery();
                              }}
                              @or-fleet-columns=${(e: CustomEvent) => {
                                  this._columns = e.detail.columns;
                                  this._pushUrl();
                                  this._runQuery();
                              }}
                              @or-fleet-sort=${(e: CustomEvent) => {
                                  this._sort = e.detail.sort;
                                  this._offset = 0;
                                  this._pushUrl();
                                  this._runQuery();
                              }}
                              @or-fleet-hidden=${(e: CustomEvent) => {
                                  // Display only: no query, the result already holds every column.
                                  this._hidden = e.detail.hidden;
                                  this._writeSetting(HIDDEN_COLUMNS_KEY, this._hidden.join(","));
                                  this._pushUrl();
                              }}
                              @or-fleet-group=${(e: CustomEvent) => {
                                  this._groupBy = e.detail.groupBy;
                                  this._offset = 0;
                                  this._pushUrl();
                                  this._runQuery();
                              }}></or-fleet-results>
        `;
    }
}
