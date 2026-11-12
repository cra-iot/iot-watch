import { css, html, nothing, TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { AppStateKeyed, Page, PageProvider, router } from "@openremote/or-app";
import { getAssetsRoute } from "@openremote/manager/routes";
import { EnhancedStore } from "@reduxjs/toolkit";
import type { FleetQuery, FleetQueryResult } from "model";
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
import "../fleet/or-fleet-export-dialog";

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
            /* A panel as on the stock manager pages (page-users), with a wider cap and slimmer padding for
               the table. No page title: the header's active tab names the page. */
            :host { display: flex; flex-direction: column; width: 100%; min-height: 0; padding: 8px 12px; box-sizing: border-box; }
            .panel { display: flex; flex-direction: column; flex: 1; min-height: 0; width: 100%; max-width: 1680px;
                margin: 0 auto; padding: 8px 12px; box-sizing: border-box;
                background-color: var(--or-app-color1, #fff); border: 1px solid #e5e5e5; border-radius: 5px; }
            .error { color: var(--or-app-color4, #e3000a); padding: 8px 0; }
            .tools { display: flex; justify-content: flex-end; padding: 4px 0; }
            .tools button { font: inherit; cursor: pointer; background: none; border: 1px solid #ccc; border-radius: 4px;
                padding: 4px 12px; color: var(--or-app-color3, #222); }
            .tools button:disabled { cursor: default; opacity: 0.5; }
            .note { color: var(--or-app-color3, #4c4c4c); padding: 8px 0; }
            @media screen and (max-width: 768px) {
                :host { padding: 0 0 12px; }
                .panel { border-radius: 0; border-left: 0; border-right: 0; padding: 8px 16px; }
            }
        `;
    }

    @state() protected _tokens: Token[] = [];
    @state() protected _result?: FleetQueryResult;
    @state() protected _loading = false;
    @state() protected _groupBy = "NONE";
    /** Group keys the user collapsed; reset with the grouping, the realm and the URL, kept across searches. */
    @state() protected _collapsed: string[] = [];
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
    /** When the shown result arrived. */
    @state() protected _updatedAt?: number;
    /** The shown result reloaded the one before it (refresh); a superseded refresh never sets it. */
    @state() protected _refreshed = false;
    @state() protected _exportOpen = false;
    /** The search the export dialog is open for; fixed while it is open. */
    protected _exportQuery?: FleetQuery;

    /** The status: token values, kept per tokens array so the results table gets a stable array. */
    protected _statusCache?: { tokens: Token[]; status: string[] };

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
        this._collapsed = [];
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
        this._collapsed = [];
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
            ...(this._collapsed.length ? { collapsed: this._collapsed } : {}),
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

    /** False when this query failed; a query overtaken by a newer one counts as done. */
    protected async _runQuery(refresh = false): Promise<boolean> {
        const requestId = ++this._requestId;
        this._loading = true;
        this._error = undefined;
        try {
            // A reading: token needs the primary register of the one type in play. The previous
            // result usually knows it; when it does not (a shared URL) or it is stale, the result
            // of this query names it and the query runs once more with it.
            let primaryRegister = singlePrimaryRegister(this._result?.columns);
            let result = await this._fetch(primaryRegister);
            if (requestId !== this._requestId) { return true; }
            let followUp = readingFollowUp(this._tokens, primaryRegister, result.columns);
            if (followUp.action === "rerun") {
                primaryRegister = followUp.primaryRegister;
                result = await this._fetch(primaryRegister);
                if (requestId !== this._requestId) { return true; }
                followUp = readingFollowUp(this._tokens, primaryRegister, result.columns);
            }
            this._readingNote = followUp.action !== "none";
            this._result = result;
            this._refreshed = refresh;
            this._updatedAt = Date.now();
            this._rememberSites(result);
            return true;
        } catch (e) {
            // Inline message, never a dialog: the bar must stay usable so the user can retry.
            if (requestId === this._requestId) { this._error = "app:fleet.error.queryFailed"; }
            return requestId !== this._requestId;
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

    protected _status(): string[] {
        if (this._statusCache?.tokens !== this._tokens) {
            const status = this._tokens.filter((token) => token.kind === "status").map((token) => token.value);
            this._statusCache = { tokens: this._tokens, status };
        }
        return this._statusCache.status;
    }

    /** Replaces the status: tokens, keeping every other filter. */
    protected _setStatus(status: string[]): void {
        this._tokens = [
            ...this._tokens.filter((token) => token.kind !== "status"),
            ...status.map((value): Token => ({ kind: "status", value })),
        ];
        this._offset = 0;
        this._pushUrl();
        this._runQuery();
    }

    protected _openExport(): void {
        // The filter only: paging, sorting and grouping do not shape an export.
        const query = toFleetQuery(this._tokens, {});
        if (this._realm) { query.realm = this._realm; }
        this._exportQuery = query;
        this._exportOpen = true;
    }

    protected render(): TemplateResult {
        return html`
            <div class="panel">
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
                <div class="tools">
                    <button type="button" ?disabled=${this._loading || !this._result?.total} @click=${() => this._openExport()}>
                        <or-translate value="app:fleet.export.button"></or-translate>
                    </button>
                </div>
                <or-fleet-results .result=${this._result} .groupBy=${this._groupBy} .collapsed=${this._collapsed} .loading=${this._loading}
                                  .sort=${this._sort} .hiddenColumns=${this._hidden} .updatedAt=${this._updatedAt} .refreshed=${this._refreshed}
                                  .status=${this._status()}
                                  .filtered=${this._tokens.length > 0}
                                  @or-fleet-status=${(e: CustomEvent) => this._setStatus(e.detail.status)}
                                  @or-fleet-refresh=${() => this._runQuery(true)}
                                  @or-fleet-clear=${() => {
                                      this._tokens = [];
                                      this._offset = 0;
                                      this._readingNote = false;
                                      this._pushUrl();
                                      this._runQuery();
                                  }}
                                  @or-fleet-row-click=${(e: CustomEvent) =>
                                      router.navigate(getAssetsRoute(false, e.detail.assetId))}
                                  @or-fleet-page=${(e: CustomEvent) => {
                                      this._offset = e.detail.offset;
                                      this._runQuery();
                                  }}
                                  @or-fleet-page-size=${async (e: CustomEvent) => {
                                      // The pager pages by the size of the shown result, so a size that
                                      // failed to load is dropped and the shown page stays as it was.
                                      const previous = { pageSize: this._pageSize, offset: this._offset };
                                      this._pageSize = e.detail.limit;
                                      this._offset = 0;
                                      if (await this._runQuery()) {
                                          this._writeSetting(PAGE_SIZE_KEY, String(this._pageSize));
                                          this._pushUrl();
                                      } else {
                                          this._pageSize = previous.pageSize;
                                          this._offset = previous.offset;
                                      }
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
                                  @or-fleet-collapse=${(e: CustomEvent) => {
                                      // Collapsing changes which rows the pages hold: back to the first page.
                                      this._collapsed = e.detail.collapsed;
                                      this._offset = 0;
                                      this._runQuery();
                                  }}
                                  @or-fleet-group=${(e: CustomEvent) => {
                                      this._groupBy = e.detail.groupBy;
                                      this._collapsed = [];
                                      this._offset = 0;
                                      this._pushUrl();
                                      this._runQuery();
                                  }}></or-fleet-results>
                <or-fleet-export-dialog .query=${this._exportQuery} .open=${this._exportOpen}
                                        @or-fleet-export-close=${() => { this._exportOpen = false; }}></or-fleet-export-dialog>
            </div>
        `;
    }
}
