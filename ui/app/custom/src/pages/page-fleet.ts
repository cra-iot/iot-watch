import { css, html, nothing, TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { manager } from "@openremote/core";
import { AppStateKeyed, Page, PageProvider, router } from "@openremote/or-app";
import { i18next } from "@openremote/or-translate";
import { EnhancedStore } from "@reduxjs/toolkit";
import type { FleetQueryResult } from "model";
// The project's generated client: manager.rest from @openremote/core is the stock OpenRemote
// client and has no FleetResource.
import rest from "rest";
import { fromUrlParams, Token, toFleetQuery, toUrlParams, parseSearch } from "../fleet/fleet-tokens";
import "../fleet/or-fleet-search";
import "../fleet/or-fleet-results";

interface SavedView {
    name: string;
    q: string;
    groupBy: string;
}

export function pageFleetProvider(store: EnhancedStore<AppStateKeyed>): PageProvider<AppStateKeyed> {
    return {
        name: "fleet",
        routes: ["fleet"],
        pageCreator: () => new PageFleet(store),
    };
}

const t = (key: string) => i18next.t(`app:${key}`);

/** Built-in views, used while a realm has none stored. Attribute names stay out: they come from the model. */
function defaultViews(): SavedView[] {
    return [
        { name: t("fleet.views.all"), q: "", groupBy: "PARENT" },
        { name: t("fleet.views.alarms"), q: "", groupBy: "STATUS" },
        { name: t("fleet.views.silent"), q: "seen:>24h", groupBy: "NONE" },
    ];
}

@customElement("page-fleet")
export class PageFleet extends Page<AppStateKeyed> {

    static get styles() {
        // language=CSS
        return css`
            :host { display: flex; flex-direction: column; width: 100%; padding: 12px 24px; box-sizing: border-box; }
            .views { display: flex; align-items: center; gap: 8px; padding: 8px 0; }
            .views select { padding: 6px; border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; }
            .error { color: var(--or-app-color4, #e3000a); padding: 8px 0; }
        `;
    }

    @state() protected _tokens: Token[] = [];
    @state() protected _result?: FleetQueryResult;
    @state() protected _loading = false;
    @state() protected _groupBy = "PARENT";
    @state() protected _offset = 0;
    @state() protected _sort = { field: "name", descending: false };
    @state() protected _columns?: string[];
    @state() protected _error?: string;
    @state() protected _views: SavedView[] = [];

    /** Drops answers of superseded queries, so a slow earlier response cannot overwrite a newer one. */
    protected _requestId = 0;

    get name(): string {
        return "fleet";
    }

    constructor(store: EnhancedStore<AppStateKeyed>) {
        super(store);
    }

    stateChanged(_state: AppStateKeyed): void {
    }

    protected get _viewsKey(): string {
        return `iot-watch.fleet.views.${manager.displayRealm}`;
    }

    protected _loadViews(): SavedView[] {
        try {
            const stored = window.localStorage.getItem(this._viewsKey);
            if (stored) {
                const parsed = JSON.parse(stored);
                if (Array.isArray(parsed)) {
                    return parsed.filter((v) => v && typeof v.name === "string" && typeof v.q === "string");
                }
            }
        } catch {
            // Storage unavailable or corrupt: fall through to the defaults.
        }
        const views = defaultViews();
        try {
            window.localStorage.setItem(this._viewsKey, JSON.stringify(views));
        } catch {
            // Seeding is best effort.
        }
        return views;
    }

    protected firstUpdated(changedProperties: Map<string, any>): void {
        super.firstUpdated?.(changedProperties as any);
        this._views = this._loadViews();
        // The route hash looks like "#/fleet?q=...": the query string belongs to the page.
        const hash = window.location.hash;
        const restored = fromUrlParams(new URLSearchParams(hash.includes("?") ? hash.slice(hash.indexOf("?") + 1) : ""));
        this._tokens = restored.tokens;
        if (restored.groupBy) { this._groupBy = restored.groupBy; }
        if (restored.sort) { this._sort = restored.sort; }
        if (restored.columns) { this._columns = restored.columns; }
        this._runQuery();
    }

    protected _pushUrl(): void {
        const params = toUrlParams(this._tokens, {}, {
            groupBy: this._groupBy, sort: this._sort, columns: this._columns,
        });
        try {
            // replaceState does not fire hashchange, so the router leaves the page alone.
            window.history.replaceState(window.history.state, "", `#/fleet?${params.toString()}`);
        } catch {
            // A restricted history API only costs the shareable link.
        }
    }

    protected async _runQuery(): Promise<void> {
        const requestId = ++this._requestId;
        this._loading = true;
        this._error = undefined;
        try {
            // primaryRegister lets a reading: token resolve; it is only meaningful when the last
            // result held one type, otherwise toFleetQuery drops the token.
            const primaries = (this._result?.columns ?? []).filter((c: any) => c.primary);
            const query = toFleetQuery(this._tokens, {
                groupBy: this._groupBy,
                offset: this._offset,
                sort: this._sort,
                // An empty list means "reset": send nothing and the server picks its default.
                ...(this._columns?.length ? { columns: this._columns } : {}),
                ...(primaries.length === 1 ? { primaryRegister: primaries[0].name } : {}),
            });
            const response = await rest.api.FleetResource.query(query);
            if (requestId === this._requestId) { this._result = response.data; }
        } catch (e) {
            // Inline message, never a dialog: the bar must stay usable so the user can retry.
            if (requestId === this._requestId) { this._error = "app:fleet.error.queryFailed"; }
        } finally {
            if (requestId === this._requestId) { this._loading = false; }
        }
    }

    protected _applyView(index: number): void {
        const view = this._views[index];
        if (!view) { return; }
        this._tokens = parseSearch(view.q);
        this._groupBy = view.groupBy;
        this._offset = 0;
        this._pushUrl();
        this._runQuery();
    }

    protected render(): TemplateResult {
        return html`
            ${this._views.length ? html`
                <div class="views">
                    <label for="views"><or-translate value="app:fleet.views.label"></or-translate></label>
                    <select id="views" @change=${(e: Event) => {
                        const select = e.target as HTMLSelectElement;
                        this._applyView(Number(select.value));
                        select.value = "";
                    }}>
                        <option value="" selected disabled></option>
                        ${this._views.map((view, i) => html`<option value=${i}>${view.name}</option>`)}
                    </select>
                </div>` : nothing}
            <or-fleet-search .tokens=${this._tokens}
                             @or-fleet-search-change=${(e: CustomEvent) => {
                                 this._tokens = e.detail.tokens;
                                 this._offset = 0;
                                 this._pushUrl();
                                 this._runQuery();
                             }}></or-fleet-search>
            ${this._error ? html`<div class="error"><or-translate value=${this._error}></or-translate></div>` : nothing}
            <or-fleet-results .result=${this._result} .groupBy=${this._groupBy} .loading=${this._loading}
                              .sort=${this._sort}
                              @or-fleet-row-click=${(e: CustomEvent) =>
                                  router.navigate(`assets/${e.detail.assetId}`)}
                              @or-fleet-page=${(e: CustomEvent) => {
                                  this._offset = e.detail.offset;
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
                              @or-fleet-group=${(e: CustomEvent) => {
                                  this._groupBy = e.detail.groupBy;
                                  this._offset = 0;
                                  this._pushUrl();
                                  this._runQuery();
                              }}></or-fleet-results>
        `;
    }
}
