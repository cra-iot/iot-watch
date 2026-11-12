import { css, html, nothing, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import { live } from "lit/directives/live.js";
import { Util } from "@openremote/core";
import { AssetModelUtil } from "@openremote/model";
import { getAssetsRoute } from "@openremote/manager/routes";
import { OrElement } from "@openremote/or-element";
import { i18next, translate as translateMixin } from "@openremote/or-translate";
import "@openremote/or-icon";
import type { FleetQueryResult, FleetRow } from "model";
import {
    allColumns, cellValue, DEFAULT_HIDDEN, DEVICE_DETAILS, EM_DASH, identityLine, offeredColumns, primaryRegisters,
    readingLabel, readingValue, selectedRegisters, visibleColumns, type FleetColumn, type HideKey,
} from "./fleet-columns";
import { GROUP_OPTIONS, groupInfo, isStale, relativeAge, rowStatus, segmentGroups, STATUS_KEYS, type RowGroup } from "./fleet-groups";
import { PAGE_SIZES, pageItems } from "./fleet-pager";

export class OrFleetRowClickEvent extends CustomEvent<{ assetId: string }> {
    public static readonly NAME = "or-fleet-row-click";

    constructor(assetId: string) {
        super(OrFleetRowClickEvent.NAME, { bubbles: true, composed: true, detail: { assetId } });
    }
}

export class OrFleetPageEvent extends CustomEvent<{ offset: number }> {
    public static readonly NAME = "or-fleet-page";

    constructor(offset: number) {
        super(OrFleetPageEvent.NAME, { bubbles: true, composed: true, detail: { offset } });
    }
}

export class OrFleetPageSizeEvent extends CustomEvent<{ limit: number }> {
    public static readonly NAME = "or-fleet-page-size";

    constructor(limit: number) {
        super(OrFleetPageSizeEvent.NAME, { bubbles: true, composed: true, detail: { limit } });
    }
}

export class OrFleetColumnsEvent extends CustomEvent<{ columns: string[] }> {
    public static readonly NAME = "or-fleet-columns";

    constructor(columns: string[]) {
        super(OrFleetColumnsEvent.NAME, { bubbles: true, composed: true, detail: { columns } });
    }
}

export class OrFleetSortEvent extends CustomEvent<{ sort: { field: string; descending: boolean } }> {
    public static readonly NAME = "or-fleet-sort";

    constructor(field: string, descending: boolean) {
        super(OrFleetSortEvent.NAME, { bubbles: true, composed: true, detail: { sort: { field, descending } } });
    }
}

export class OrFleetGroupEvent extends CustomEvent<{ groupBy: string }> {
    public static readonly NAME = "or-fleet-group";

    constructor(groupBy: string) {
        super(OrFleetGroupEvent.NAME, { bubbles: true, composed: true, detail: { groupBy } });
    }
}

export class OrFleetCollapseEvent extends CustomEvent<{ collapsed: string[] }> {
    public static readonly NAME = "or-fleet-collapse";

    constructor(collapsed: string[]) {
        super(OrFleetCollapseEvent.NAME, { bubbles: true, composed: true, detail: { collapsed } });
    }
}

export class OrFleetHiddenEvent extends CustomEvent<{ hidden: string[] }> {
    public static readonly NAME = "or-fleet-hidden";

    constructor(hidden: string[]) {
        super(OrFleetHiddenEvent.NAME, { bubbles: true, composed: true, detail: { hidden } });
    }
}

export class OrFleetStatusEvent extends CustomEvent<{ status: string[] }> {
    public static readonly NAME = "or-fleet-status";

    constructor(status: string[]) {
        super(OrFleetStatusEvent.NAME, { bubbles: true, composed: true, detail: { status } });
    }
}

export class OrFleetRefreshEvent extends CustomEvent<void> {
    public static readonly NAME = "or-fleet-refresh";

    constructor() {
        super(OrFleetRefreshEvent.NAME, { bubbles: true, composed: true });
    }
}

export class OrFleetClearEvent extends CustomEvent<void> {
    public static readonly NAME = "or-fleet-clear";

    constructor() {
        super(OrFleetClearEvent.NAME, { bubbles: true, composed: true });
    }
}

declare global {
    interface HTMLElementEventMap {
        [OrFleetRowClickEvent.NAME]: OrFleetRowClickEvent;
        [OrFleetPageEvent.NAME]: OrFleetPageEvent;
        [OrFleetPageSizeEvent.NAME]: OrFleetPageSizeEvent;
        [OrFleetColumnsEvent.NAME]: OrFleetColumnsEvent;
        [OrFleetSortEvent.NAME]: OrFleetSortEvent;
        [OrFleetGroupEvent.NAME]: OrFleetGroupEvent;
        [OrFleetCollapseEvent.NAME]: OrFleetCollapseEvent;
        [OrFleetHiddenEvent.NAME]: OrFleetHiddenEvent;
        [OrFleetStatusEvent.NAME]: OrFleetStatusEvent;
        [OrFleetRefreshEvent.NAME]: OrFleetRefreshEvent;
        [OrFleetClearEvent.NAME]: OrFleetClearEvent;
    }
}

/** Columns menu labels of the device details; "identity" is the meter's second line, not a column. */
const DETAIL_LABELS: Record<string, string> = {
    type: "fleet.column.type",
    deliveryPoint: "fleet.column.deliveryPoint",
    identity: "fleet.column.identity",
    location: "fleet.column.location",
    battery: "fleet.column.battery",
    lastSeen: "fleet.column.lastSeen",
    alarms: "fleet.column.alarms",
};

/** Non-colour status cue in front of the meter name; ok rows get none. */
const STATUS_ICONS: Record<string, string> = { alarm: "alert", silent: "clock-alert-outline" };

function translate(key: string, options?: Record<string, unknown>): string {
    return i18next.t(key.includes(":") ? key : `app:${key}`, options);
}

/** How often the relative times (last seen, updated) are redrawn. */
const TICK_MS = 30_000;

/** Placeholder rows while the first result loads. */
const SKELETON_ROWS = 8;

/** Util.resolveUnits has an optional second argument that must not receive a map index. */
const formatUnits = (units?: string[]) => Util.resolveUnits(units);

/**
 * Server-paged, server-sorted, optionally grouped result table. It only renders and emits events;
 * sorting, grouping, paging, column selection and hidden columns are applied by whoever owns the query.
 */
@customElement("or-fleet-results")
export class OrFleetResults extends translateMixin(i18next)(OrElement) {

    static get styles() {
        return css`
            /* Fills the height the page leaves it; only the table box scrolls, the bar and pager stay put. */
            :host {
                display: flex; flex-direction: column; flex: 1; min-height: 0; width: 100%; color: var(--or-app-color3, #222);
                /* The app has no amber of its own; this one keeps 4.5:1 on white. */
                --fleet-alarm-color: var(--or-app-color4, #e3000a);
                --fleet-silent-color: #9a5b00;
                /* Fixed, as the brand colour (color4) is red in some realms. */
                --fleet-ok-color: #2e7d32;
                --fleet-muted-color: #6b6b6b;
                --fleet-focus-color: var(--or-app-color3, #222);
                --fleet-stripe-color: color-mix(in srgb, var(--or-app-color2, #f5f5f5) 55%, var(--or-app-color1, #fff));
                --fleet-hover-color: color-mix(in srgb, var(--or-app-color5, #ccc) 35%, var(--or-app-color1, #fff));
            }
            .sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden;
                clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
            button:focus-visible, summary:focus-visible, input:focus-visible {
                outline: 2px solid var(--fleet-focus-color); outline-offset: 2px; }
            .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 8px 0; }
            .bar .spacer { flex: 1; }
            .bar .label { color: var(--fleet-muted-color); }
            .count { font-weight: 600; }
            .control { display: inline-flex; align-items: center; gap: 6px; }
            .toggle { display: inline-flex; border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; overflow: hidden; }
            .toggle button { border: none; background: var(--or-app-color1, #fff); padding: 6px 10px; cursor: pointer; color: inherit; }
            .toggle button[aria-pressed="true"] { background: var(--or-app-color4, #4d9d2a); color: #fff; }
            button.plain { border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; background: var(--or-app-color1, #fff); padding: 6px 10px; cursor: pointer; color: inherit; }
            button.plain:disabled { opacity: .5; cursor: default; }
            .picker { position: relative; }
            .picker summary { cursor: pointer; list-style: none; border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; padding: 6px 10px; background: var(--or-app-color1, #fff); }
            .picker .menu {
                position: absolute; right: 0; z-index: 10; min-width: 240px; max-height: 420px; overflow-y: auto;
                background: var(--or-app-color1, #fff); border: 1px solid var(--or-app-color5, #ccc);
                border-radius: 4px; box-shadow: 0 2px 8px rgba(0, 0, 0, .2); padding: 8px 12px;
            }
            .picker fieldset { border: none; margin: 0 0 8px; padding: 0; }
            .picker legend { padding: 4px 0; font-size: 12px; font-weight: 600; text-transform: uppercase;
                letter-spacing: .04em; color: var(--fleet-muted-color); }
            .picker label { display: flex; align-items: center; gap: 8px; padding: 4px 0; white-space: nowrap; }
            .picker label.locked { color: var(--fleet-muted-color); }
            .picker .reset { margin-top: 4px; width: 100%; }
            .body { display: flex; flex-direction: column; flex: 1; min-height: 0; }
            /* Scrolls both ways inside the screen, so a long page never pushes the pager off it. The minimum
               keeps a few rows on very short windows, where the page itself scrolls instead. Relative, so it
               is the containing block of the absolute .sr-only texts and clips them instead of the page growing. */
            .scroll { position: relative; overflow: auto; min-height: 200px;
                border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; }
            table { width: 100%; border-collapse: separate; border-spacing: 0; }
            th, td { text-align: left; padding: 10px 16px; border-bottom: 1px solid var(--or-app-color2, #eee);
                white-space: nowrap; vertical-align: top; }
            thead th { position: sticky; top: 0; z-index: 2; font-weight: 600; font-size: 13px;
                background: var(--or-app-color2, #f5f5f5); border-bottom-color: var(--or-app-color5, #ccc); }
            th.numeric, td.numeric { text-align: right; font-variant-numeric: tabular-nums; }
            th.sorted { color: var(--or-app-color3, #222); }
            .sort { display: inline-flex; align-items: baseline; gap: 4px; padding: 0; border: none; background: none;
                font: inherit; color: inherit; text-align: inherit; cursor: pointer; }
            .sort .arrow { min-width: 1ch; }
            .sort:not(:hover) .arrow.hint { visibility: hidden; }
            .sort .arrow.hint { color: var(--fleet-muted-color); }
            th.numeric .sort { flex-direction: row-reverse; }
            /* Row colour comes from --row-bg so the frozen cell always matches the rest of its row. */
            tbody tr.row { --row-bg: var(--or-app-color1, #fff); }
            tbody tr.row.stripe { --row-bg: var(--fleet-stripe-color); }
            tbody tr.row:hover { --row-bg: var(--fleet-hover-color); }
            tbody td { background: var(--row-bg); }
            .meter { position: sticky; left: 0; z-index: 1; }
            thead th.meter { z-index: 3; background: var(--or-app-color2, #f5f5f5); }
            /* A soft edge only while content is scrolled underneath the frozen column. */
            .scroll.scrolled .meter { box-shadow: 4px 0 6px -4px rgba(0, 0, 0, .25); }
            .scroll.scrolled tr.row.alarm td.meter { box-shadow: inset 3px 0 0 var(--fleet-alarm-color), 4px 0 6px -4px rgba(0, 0, 0, .25); }
            .scroll.scrolled tr.row.silent td.meter { box-shadow: inset 3px 0 0 var(--fleet-silent-color), 4px 0 6px -4px rgba(0, 0, 0, .25); }
            .meter .name { font-weight: 500; }
            .meter .status-icon { --or-icon-width: 16px; --or-icon-height: 16px; vertical-align: -3px; margin-right: 4px; }
            tr.alarm .status-icon { --or-icon-fill: var(--fleet-alarm-color); }
            tr.silent .status-icon { --or-icon-fill: var(--fleet-silent-color); }
            .meter .identity { margin-top: 2px; font-size: 12px; color: var(--fleet-muted-color); font-variant-numeric: tabular-nums; }
            /* Status as a left edge: alarm red, silent amber, ok nothing. */
            tr.row td.meter { box-shadow: inset 3px 0 0 transparent; }
            tr.row.alarm td.meter { box-shadow: inset 3px 0 0 var(--fleet-alarm-color); }
            tr.row.silent td.meter { box-shadow: inset 3px 0 0 var(--fleet-silent-color); }
            td.age.stale { color: var(--fleet-silent-color); }
            /* Which register the reading is. */
            td .what { margin-top: 2px; font-size: 12px; color: var(--fleet-muted-color); }
            /* Same second line as the meter's identity. */
            td.age .when { margin-top: 2px; font-size: 12px; color: var(--fleet-muted-color); font-variant-numeric: tabular-nums; }
            td.actions, th.actions { width: 1%; padding-top: 4px; padding-bottom: 4px; text-align: right; }
            .open { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px;
                padding: 0; border: none; border-radius: 4px; background: transparent; color: inherit; cursor: pointer; text-decoration: none;
                --or-icon-width: 20px; --or-icon-height: 20px; }
            .open:hover { background: rgba(0, 0, 0, .08); }
            .open:focus-visible { outline: 2px solid var(--fleet-focus-color); outline-offset: 2px; }
            tr.divider th { padding: 0; background: var(--or-app-color1, #fff); border-bottom-color: var(--or-app-color5, #ccc); }
            tbody + tbody tr.divider th { border-top: 12px solid var(--or-app-color1, #fff); }
            .group-header { position: sticky; left: 0; display: inline-flex; align-items: baseline; gap: 12px;
                border: none; cursor: pointer; background: transparent; color: inherit; padding: 8px 12px;
                text-align: left; font-size: 14px; }
            .group-header .name { font-weight: 600; }
            .group-header .meta { color: var(--fleet-muted-color); font-weight: normal; }
            .group-header .continued { font-style: italic; color: var(--fleet-muted-color); font-weight: normal; }
            .chip { display: inline-block; margin-right: 4px; padding: 0 8px; border-radius: 12px;
                border: 1px solid var(--fleet-alarm-color); color: inherit; font-size: 12px; line-height: 20px; }
            .pager { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 12px; padding: 8px 0; }
            .pager select { padding: 5px 6px; border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px;
                background: var(--or-app-color1, #fff); color: inherit; font: inherit; }
            .pages { display: inline-flex; align-items: center; gap: 4px; }
            .pages button.plain { min-width: 34px; padding: 6px 8px; font-variant-numeric: tabular-nums; }
            .pages button[aria-current="page"] { background: var(--or-app-color4, #4d9d2a); border-color: var(--or-app-color4, #4d9d2a); color: #fff; cursor: default; }
            .pages .gap { padding: 0 4px; color: var(--fleet-muted-color); }
            .body { position: relative; }
            .loading .scroll { opacity: .5; pointer-events: none; transition: opacity .15s; }
            /* An indeterminate bar over the table's top edge while a query runs. */
            .progress { position: absolute; top: 0; left: 0; right: 0; z-index: 4; height: 3px; overflow: hidden; border-radius: 4px 4px 0 0; }
            .progress::after { content: ""; position: absolute; top: 0; bottom: 0; left: -40%; width: 40%;
                background: var(--or-app-color4, #4d9d2a); animation: fleet-progress 1.2s ease-in-out infinite; }
            @keyframes fleet-progress { to { left: 100%; } }
            .skeleton td { padding: 14px 16px; }
            .skeleton .bone { height: 12px; border-radius: 6px; background: var(--or-app-color2, #eee);
                animation: fleet-pulse 1.4s ease-in-out infinite; }
            @keyframes fleet-pulse { 50% { opacity: .45; } }
            @media (prefers-reduced-motion: reduce) {
                .progress::after, .skeleton .bone { animation: none; }
                .progress::after { left: 0; width: 100%; opacity: .5; }
            }
            .empty { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px 24px;
                border: 1px dashed var(--or-app-color5, #ccc); border-radius: 4px; }
            .empty .title { font-weight: 600; color: var(--or-app-color3, #222); }
            .empty or-icon { --or-icon-width: 32px; --or-icon-height: 32px; --or-icon-fill: var(--fleet-muted-color); }
            .statuses { display: inline-flex; flex-wrap: wrap; gap: 6px; }
            .status-chip { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 16px;
                border: 1px solid var(--or-app-color5, #ccc); background: var(--or-app-color1, #fff);
                color: inherit; font: inherit; font-size: 13px; cursor: pointer; }
            .status-chip:hover { background: var(--fleet-hover-color); }
            .status-chip[aria-pressed="true"] { border-color: var(--or-app-color3, #222); box-shadow: inset 0 0 0 1px var(--or-app-color3, #222); }
            .status-chip .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--fleet-ok-color); }
            .status-chip.alarm .dot { background: var(--fleet-alarm-color); }
            .status-chip.silent .dot { background: var(--fleet-silent-color); }
            .status-chip .n { font-weight: 600; font-variant-numeric: tabular-nums; }
            .updated { display: inline-flex; align-items: center; gap: 2px; color: var(--fleet-muted-color); font-size: 13px; }
            .icon-button { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px;
                padding: 0; border: none; border-radius: 4px; background: transparent; color: inherit; cursor: pointer;
                --or-icon-width: 20px; --or-icon-height: 20px; }
            .icon-button:hover { background: rgba(0, 0, 0, .08); }
            .icon-button:disabled { opacity: .5; cursor: default; }
            /* On a phone only the button stays, with the age in its tooltip, so the bar takes fewer rows. */
            @media screen and (max-width: 768px) {
                .updated .age-text { display: none; }
                /* Still names the toggle group through aria-labelledby; Columns then fits next to it. */
                #group-label { display: none; }
            }
        `;
    }

    @property({ attribute: false })
    public result?: FleetQueryResult;

    @property({ type: String })
    public groupBy = "NONE";

    @property({ type: Boolean })
    public loading = false;

    /** Current sort, only used to show the header state. */
    @property({ attribute: false })
    public sort?: { field: string; descending: boolean };

    /** Hidden columns (fleet-columns HIDE_KEYS); the page owns, persists and shares them. */
    @property({ attribute: false })
    public hiddenColumns: string[] = [...DEFAULT_HIDDEN];

    /** Group keys the user collapsed; the page owns them, the server pages without their rows. */
    @property({ attribute: false })
    public collapsed: string[] = [];

    /** Statuses the search filters on (status: tokens); none means every status. */
    @property({ attribute: false })
    public status: string[] = [];

    /** True when the search has any filter, so an empty result can offer to clear it. */
    @property({ type: Boolean })
    public filtered = false;

    /** When the shown result arrived (epoch millis). */
    @property({ attribute: false })
    public updatedAt?: number;

    /** The result is the shown one reloaded (a refresh), so the table keeps its scroll position. */
    @property({ type: Boolean })
    public refreshed = false;

    protected _timer?: ReturnType<typeof setInterval>;

    connectedCallback() {
        super.connectedCallback();
        // Redraws relative times; a hidden tab skips the work.
        this._timer = setInterval(() => { if (!document.hidden) { this.requestUpdate(); } }, TICK_MS);
    }

    disconnectedCallback() {
        clearInterval(this._timer);
        super.disconnectedCallback();
    }

    /**
     * A new result (another page, sort or search) starts at its first row; a refresh keeps the
     * position. The box keeps its sideways scroll either way.
     */
    protected updated(changed: Map<string, unknown>) {
        if (changed.has("result") && !this.refreshed) {
            const scroll = this.shadowRoot?.querySelector<HTMLElement>(".scroll");
            if (scroll) { scroll.scrollTop = 0; }
        }
    }

    protected _toggleStatus(key: string) {
        this.dispatchEvent(new OrFleetStatusEvent(this.status.includes(key)
            ? this.status.filter((k) => k !== key)
            : [...this.status, key]));
    }

    protected _toggleGroup(key: string) {
        this.dispatchEvent(new OrFleetCollapseEvent(this.collapsed.includes(key)
            ? this.collapsed.filter((k) => k !== key)
            : [...this.collapsed, key]));
    }

    protected _toggleColumn(result: FleetQueryResult, key: string, checked: boolean) {
        const selected = selectedRegisters(result).filter((k) => k !== key);
        this.dispatchEvent(new OrFleetColumnsEvent(checked ? [...selected, key] : selected));
    }

    protected _toggleHidden(key: HideKey, shown: boolean) {
        const rest = this.hiddenColumns.filter((k) => k !== key);
        this.dispatchEvent(new OrFleetHiddenEvent(shown ? rest : [...rest, key]));
    }

    protected _reset() {
        this.dispatchEvent(new OrFleetHiddenEvent([...DEFAULT_HIDDEN]));
        // Only a changed register selection re-queries.
        if (this.result && selectedRegisters(this.result).length > 0) {
            this.dispatchEvent(new OrFleetColumnsEvent([]));
        }
    }

    protected _alarmLabel(row: FleetRow, name: string): string {
        const descriptor = row.type ? AssetModelUtil.getAttributeDescriptor(name, row.type) : undefined;
        return Util.getAttributeLabel(undefined, descriptor, row.type, false, name);
    }

    protected _statusLabel(key: string): string {
        return translate(`fleet.status.${key}`);
    }

    protected _columnLabel(column: FleetColumn): string {
        return column.labelKey ? translate(column.labelKey) : column.label;
    }

    /** Exact time of the last data, as a tooltip on the relative age, or a note that no data arrived yet. */
    protected _lastSeenTitle(lastSeen: unknown): string {
        if (typeof lastSeen !== "number" || Number.isNaN(lastSeen)) { return translate("fleet.statusHint.never"); }
        return new Intl.DateTimeFormat(i18next.language || "en", { dateStyle: "medium", timeStyle: "short" }).format(lastSeen);
    }

    protected _statusHint(row: FleetRow, status: string): string {
        return status === "silent" && row.lastSeen == null ? translate("fleet.statusHint.never") : translate(`fleet.statusHint.${status}`);
    }

    protected _renderAge(lastSeen: unknown, now: number): string {
        const age = relativeAge(lastSeen, now);
        if (!age) { return EM_DASH; }
        const formatter = new Intl.RelativeTimeFormat(i18next.language || "en", { numeric: "always", style: "short" });
        return formatter.format(-age.value, age.unit);
    }

    protected _renderMeter(row: FleetRow, status: string): TemplateResult {
        const identity = this.hiddenColumns.includes("identity") ? undefined : identityLine(row);
        const name = row.name === undefined || row.name === null || row.name === "" ? EM_DASH : String(row.name);
        return html`
            <div class="name">${STATUS_ICONS[status] ? html`<or-icon class="status-icon" aria-hidden="true" title=${this._statusHint(row, status)} icon=${STATUS_ICONS[status]}></or-icon>` : nothing}${name}${status !== "ok" ? html`<span class="sr-only">, ${this._statusHint(row, status)}</span>` : nothing}</div>
            ${identity ? html`<div class="identity">${identity}</div>` : nothing}`;
    }

    protected _renderCell(result: FleetQueryResult, row: FleetRow, column: FleetColumn, status: string, now: number): TemplateResult | string {
        if (column.attribute) {
            return cellValue(row, column);
        }
        switch (column.key) {
            case "name":
                return this._renderMeter(row, status);
            case "type":
                return row.type ? Util.getAssetTypeLabel(row.type) : EM_DASH;
            case "reading":
                return this._renderReading(result, row, column);
            case "lastSeen":
                // The age to scan, the exact time of the last message below it; never reported has neither.
                return relativeAge(row.lastSeen, now)
                    ? html`<div>${this._renderAge(row.lastSeen, now)}</div><div class="when">${this._lastSeenTitle(row.lastSeen)}</div>`
                    : html`${EM_DASH}<span class="sr-only">, ${this._lastSeenTitle(row.lastSeen)}</span>`;
            case "batteryLevel":
                return typeof row.batteryLevel === "number" && !Number.isNaN(row.batteryLevel) ? `${row.batteryLevel} %` : EM_DASH;
            case "alarms": {
                const alarms: string[] = row.alarms ?? [];
                return alarms.length
                    ? html`${alarms.map((name) => html`<span class="chip">${this._alarmLabel(row, name)}</span>`)}`
                    : EM_DASH;
            }
            default: {
                const value = (row as Record<string, unknown>)[column.key];
                return value === undefined || value === null || value === "" ? EM_DASH : String(value);
            }
        }
    }

    /** The value, and below it what it is; with one register the header names it instead. */
    protected _renderReading(result: FleetQueryResult, row: FleetRow, column: FleetColumn): TemplateResult | string {
        const value = readingValue(row, result, formatUnits);
        const label = column.register ? undefined : readingLabel(row, result);
        if (value === EM_DASH || !label) { return value; }
        return html`<div>${value}</div><div class="what">${label}</div>`;
    }

    /** Mixed types: which register each type's Reading shows, as the header's tooltip. */
    protected _readingTitle(result: FleetQueryResult, column: FleetColumn): string | undefined {
        if (column.key !== "reading" || column.register) { return undefined; }
        const lines = primaryRegisters(result).map((d) => `${Util.getAssetTypeLabel(d.assetType!)}: ${d.label ?? d.name}`);
        return lines.length ? [translate("fleet.reading.perType"), ...lines].join("\n") : undefined;
    }

    /** `stale` marks an old last seen even on an alarm row, whose status hides the silence. */
    protected _cellClass(column: FleetColumn, status: string, stale = false): string {
        return [
            column.key === "name" ? "meter" : "",
            column.numeric ? "numeric" : "",
            column.key === "lastSeen" ? `age${stale ? " stale" : ""}` : "",
        ].filter(Boolean).join(" ");
    }

    protected _renderColumnsMenu(result: FleetQueryResult): TemplateResult {
        const registers = [
            ...allColumns(result, formatUnits).filter((c) => c.attribute).map((column) => ({ column, checked: true })),
            ...offeredColumns(result, formatUnits).map((column) => ({ column, checked: false })),
        ];
        return html`
            <details class="picker">
                <summary>${translate("fleet.columns.picker")}</summary>
                <div class="menu">
                    <fieldset>
                        <legend>${translate("fleet.columns.details")}</legend>
                        <label class="locked"><input type="checkbox" checked disabled>${translate("fleet.column.meter")}</label>
                        ${DEVICE_DETAILS.map((key) => html`
                            <label>
                                <input type="checkbox" .checked=${!this.hiddenColumns.includes(key)}
                                       @change=${(ev: Event) => this._toggleHidden(key, (ev.target as HTMLInputElement).checked)}>
                                ${translate(DETAIL_LABELS[key])}
                            </label>`)}
                    </fieldset>
                    <fieldset>
                        <legend>${translate("fleet.columns.measurements")}</legend>
                        <label>
                            <input type="checkbox" .checked=${!this.hiddenColumns.includes("reading")}
                                   @change=${(ev: Event) => this._toggleHidden("reading", (ev.target as HTMLInputElement).checked)}>
                            ${translate("fleet.column.reading")}
                        </label>
                        ${registers.map(({ column, checked }) => html`
                            <label>
                                <input type="checkbox" .checked=${checked}
                                       @change=${(ev: Event) => this._toggleColumn(result, column.key, (ev.target as HTMLInputElement).checked)}>
                                ${column.label}${column.units ? html` <small>(${column.units})</small>` : nothing}
                            </label>`)}
                    </fieldset>
                    <button type="button" class="plain reset" @click=${() => this._reset()}>
                        ${translate("fleet.columns.reset")}
                    </button>
                </div>
            </details>`;
    }

    /** Counts before the status filter, so every chip keeps its number; an older server sends only facets. */
    protected _renderStatusChips(result: FleetQueryResult): TemplateResult {
        const counts = new Map((result.statusCounts?.length ? result.statusCounts : result.facets?.status ?? [])
            .map((facet) => [facet.key ?? "", facet.count ?? 0]));
        return html`
            <span class="statuses" role="group" aria-label=${translate("fleet.token.status")}>
                ${STATUS_KEYS.map((key) => html`
                    <button type="button" class="status-chip ${key}" aria-pressed=${this.status.includes(key) ? "true" : "false"}
                            title=${translate(`fleet.statusHint.${key}`)} @click=${() => this._toggleStatus(key)}>
                        <span class="dot" aria-hidden="true"></span>
                        <span>${this._statusLabel(key)}</span>
                        <span class="n">${counts.get(key) ?? 0}</span>
                    </button>`)}
            </span>`;
    }

    protected _renderUpdated(now: number): TemplateResult {
        const seconds = this.updatedAt === undefined ? 0 : Math.max(0, Math.floor((now - this.updatedAt) / 1000));
        const age = seconds < 60
            ? translate("fleet.updated.justNow")
            : translate("fleet.updated.ago", { age: this._renderAge(this.updatedAt, now) });
        const label = translate("fleet.updated.refresh");
        return html`
            <span class="updated">
                <span class="age-text" title=${this.updatedAt === undefined ? nothing : this._lastSeenTitle(this.updatedAt)}>${age}</span>
                <button type="button" class="icon-button" aria-label=${label} title=${`${label} (${age})`} ?disabled=${this.loading}
                        @click=${() => this.dispatchEvent(new OrFleetRefreshEvent())}>
                    <or-icon aria-hidden="true" icon="refresh"></or-icon>
                </button>
            </span>`;
    }

    protected _renderResultBar(result: FleetQueryResult, now: number): TemplateResult {
        return html`
            <div class="bar">
                <span class="count">${translate("fleet.result.count", { count: result.total ?? 0 })}</span>
                ${this._renderStatusChips(result)}
                <span class="spacer"></span>
                ${this._renderUpdated(now)}
                <span class="control">
                    <span class="label" id="group-label">${translate("fleet.groupBy.label")}</span>
                    <span class="toggle" role="group" aria-labelledby="group-label">
                        ${GROUP_OPTIONS.map((option) => html`
                            <button type="button" aria-pressed=${this.groupBy === option ? "true" : "false"}
                                    @click=${() => { if (this.groupBy !== option) { this.dispatchEvent(new OrFleetGroupEvent(option)); } }}>
                                ${translate(`fleet.groupBy.${option.toLowerCase()}`)}
                            </button>`)}
                    </span>
                </span>
                ${this._renderColumnsMenu(result)}
                <slot name="actions"></slot>
            </div>`;
    }

    protected _renderRows(result: FleetQueryResult, rows: FleetRow[], columns: FleetColumn[], now: number): TemplateResult[] {
        // The stripe index is per call, so it restarts in every group.
        return rows.map((row, index) => {
            const status = rowStatus(row, now);
            const stale = isStale(row, now);
            const label = translate("fleet.row.open");
            return html`
                <tr class="row ${status}${index % 2 === 1 ? " stripe" : ""}">
                    ${columns.map((c) => html`<td class=${this._cellClass(c, status, stale)}>${this._renderCell(result, row, c, status, now)}</td>`)}
                    <td class="actions">
                        <a class="open" href=${`#/${getAssetsRoute(false, row.id)}`} aria-label=${label} title=${label}
                           @click=${(ev: MouseEvent) => this._onOpen(ev, row.id)}>
                            <or-icon aria-hidden="true" icon="open-in-new"></or-icon>
                        </a>
                    </td>
                </tr>`;
        });
    }

    /** A plain click navigates in the app; Ctrl/Cmd/Shift-click and middle-click keep the browser's new tab or window. */
    protected _onOpen(ev: MouseEvent, assetId: string) {
        if (ev.button !== 0 || ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey) { return; }
        ev.preventDefault();
        this.dispatchEvent(new OrFleetRowClickEvent(assetId));
    }

    protected _groupName(key: string, label?: string): string {
        if (this.groupBy === "STATUS") { return this._statusLabel(key); }
        if (this.groupBy === "TYPE" && key) { return Util.getAssetTypeLabel(key); }
        return label ?? (key || EM_DASH);
    }

    /** A group is a tbody whose first row is a full-width divider, so columns align across groups. */
    protected _renderGroup(result: FleetQueryResult, group: RowGroup, columns: FleetColumn[], now: number): TemplateResult {
        const info = groupInfo(result, this.groupBy, group.key);
        const open = !group.collapsed;
        const name = this._groupName(group.key, info.label);
        return html`
            <tbody>
                <tr class="divider">
                    <th scope="rowgroup" colspan=${columns.length + 1}>
                        <button type="button" class="group-header" aria-expanded=${open ? "true" : "false"}
                                @click=${() => this._toggleGroup(group.key)}>
                            <span aria-hidden="true">${open ? "▾" : "▸"}</span>
                            <span class="name">${name}</span>
                            ${group.continued
                                ? html`<span class="continued">${translate("fleet.group.continued")}</span>`
                                : html`
                                    ${info.count !== undefined ? html`<span class="meta">${translate("fleet.group.devices", { count: info.count })}</span>` : nothing}`}
                        </button>
                    </th>
                </tr>
                ${open ? this._renderRows(result, group.rows, columns, now) : nothing}
            </tbody>`;
    }

    /** Clicking the sorted column flips its direction; any other column starts ascending. */
    protected _renderSortButton(column: FleetColumn, label: TemplateResult, sorted: boolean): TemplateResult {
        const descending = sorted && !!this.sort?.descending;
        return html`
            <button type="button" class="sort"
                    @click=${() => this.dispatchEvent(new OrFleetSortEvent(column.key, sorted ? !descending : false))}>
                <span>${label}</span>
                <span class="arrow${sorted ? "" : " hint"}" aria-hidden="true">${descending ? "↓" : "↑"}</span>
            </button>`;
    }

    protected _renderTable(result: FleetQueryResult, groups: RowGroup[], columns: FleetColumn[], now: number): TemplateResult {
        const sortField = this.sort?.field;
        return html`
            <div class="scroll" @scroll=${(ev: Event) => (ev.currentTarget as HTMLElement).classList.toggle("scrolled", (ev.currentTarget as HTMLElement).scrollLeft > 0)}>
                <table>
                    <thead><tr>
                        ${columns.map((c) => {
                            const sorted = sortField === c.key;
                            const label = html`${this._columnLabel(c)}${c.units ? html` <small>(${c.units})</small>` : nothing}`;
                            return html`
                                <th scope="col" class="${this._cellClass(c, "")}${sorted ? " sorted" : ""}" title=${this._readingTitle(result, c) ?? nothing}
                                    aria-sort=${sorted ? (this.sort?.descending ? "descending" : "ascending") : "none"}>
                                    ${c.sortable ? this._renderSortButton(c, label, sorted) : label}
                                </th>`;
                        })}
                        <th scope="col" class="actions"><span class="sr-only">${translate("fleet.column.actions")}</span></th>
                    </tr></thead>
                    ${this.groupBy === "NONE"
                        ? html`<tbody>${this._renderRows(result, groups[0].rows, columns, now)}</tbody>`
                        : groups.map((group) => this._renderGroup(result, group, columns, now))}
                </table>
            </div>`;
    }

    protected _renderPager(result: FleetQueryResult): TemplateResult | typeof nothing {
        // The pages cover the open groups only; a server without collapsing sends no paged count.
        const total = result.paged ?? result.total ?? 0;
        const offset = result.offset ?? 0;
        const limit = result.limit || (result.rows ?? []).length || 1;
        // Shown while a smaller page size would page, so a larger choice can be taken back.
        if (total <= Math.min(limit, PAGE_SIZES[0])) { return nothing; }
        const from = total === 0 ? 0 : offset + 1;
        const to = Math.min(offset + (result.rows ?? []).length, total);
        const current = Math.floor(offset / limit);
        const count = Math.ceil(total / limit);
        return html`
            <div class="pager">
                <label class="control">
                    <span class="label">${translate("fleet.pager.pageSize")}</span>
                    <select .value=${live(String(limit))} @change=${(ev: Event) => this.dispatchEvent(new OrFleetPageSizeEvent(Number((ev.target as HTMLSelectElement).value)))}>
                        ${PAGE_SIZES.map((size) => html`<option value=${size} ?selected=${size === limit}>${size}</option>`)}
                    </select>
                </label>
                <span>${translate("fleet.pager.range", { from, to, total })}</span>
                ${count > 1 ? html`
                    <nav class="pages" aria-label=${translate("fleet.pager.pages")}>
                        <button type="button" class="plain" ?disabled=${current <= 0}
                                @click=${() => this.dispatchEvent(new OrFleetPageEvent((current - 1) * limit))}>
                            ${translate("fleet.pager.previous")}
                        </button>
                        ${pageItems(current, count).map((item) => item === "gap"
                            ? html`<span class="gap" aria-hidden="true">…</span>`
                            : html`<button type="button" class="plain" aria-current=${item === current ? "page" : nothing}
                                           aria-label=${translate("fleet.pager.page", { page: item + 1 })}
                                           @click=${() => { if (item !== current) { this.dispatchEvent(new OrFleetPageEvent(item * limit)); } }}>
                                       ${item + 1}
                                   </button>`)}
                        <button type="button" class="plain" ?disabled=${current >= count - 1}
                                @click=${() => this.dispatchEvent(new OrFleetPageEvent((current + 1) * limit))}>
                            ${translate("fleet.pager.next")}
                        </button>
                    </nav>` : nothing}
            </div>`;
    }

    /** Grey bars in the shape of the default columns while the first result loads. */
    protected _renderSkeleton(): TemplateResult {
        const widths = ["70%", "50%", "40%", "30%", "40%"];
        // The announcement stays outside the busy box: screen readers may hold back live text inside one.
        return html`
            <span class="sr-only" role="status">${translate("fleet.loading")}</span>
            <div class="body loading" aria-busy="true">
                <div class="progress" aria-hidden="true"></div>
                <div class="scroll">
                    <table class="skeleton" aria-hidden="true">
                        ${Array.from({ length: SKELETON_ROWS }, () => html`
                            <tr>${widths.map((width) => html`<td><div class="bone" style="width: ${width}"></div></td>`)}</tr>`)}
                    </table>
                </div>
            </div>`;
    }

    /** Nothing matched: say whether the search or the realm is empty, and offer the way out. */
    protected _renderEmpty(): TemplateResult {
        return html`
            <div class="empty" role="status">
                <or-icon aria-hidden="true" icon=${this.filtered ? "magnify-close" : "database-off-outline"}></or-icon>
                <span class="title">${translate(this.filtered ? "fleet.emptyState.filtered" : "fleet.emptyState.realm")}</span>
                ${this.filtered ? html`
                    <span>${translate("fleet.emptyState.hint")}</span>
                    <button type="button" class="plain" @click=${() => this.dispatchEvent(new OrFleetClearEvent())}>
                        ${translate("fleet.emptyState.clear")}
                    </button>` : nothing}
            </div>`;
    }

    protected render() {
        const result = this.result;
        if (!result) { return this.loading ? this._renderSkeleton() : nothing; }
        const now = Date.now();
        const columns = visibleColumns(result, this.hiddenColumns, formatUnits);
        const groups = segmentGroups(result, this.groupBy, now, this.collapsed);
        return html`
            ${this._renderResultBar(result, now)}
            <div class="body${this.loading ? " loading" : ""}" aria-busy=${this.loading ? "true" : "false"}>
                ${this.loading ? html`<div class="progress" aria-hidden="true"></div>` : nothing}
                ${groups.length === 0 ? this._renderEmpty() : this._renderTable(result, groups, columns, now)}
            </div>
            ${this._renderPager(result)}`;
    }
}
