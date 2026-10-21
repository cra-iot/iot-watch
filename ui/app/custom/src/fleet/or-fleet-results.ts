import { css, html, nothing, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import { Util } from "@openremote/core";
import { AssetModelUtil } from "@openremote/model";
import { OrElement } from "@openremote/or-element";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-vaadin-components/or-vaadin-select";
import type { FleetQueryResult, FleetRow } from "model";
import { cellValue, columnsFrom, EM_DASH, offeredColumns, type FleetColumn } from "./fleet-columns";
import { groupInfo, problemCount, relativeAge, segmentGroups, type RowGroup } from "./fleet-groups";

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

declare global {
    interface HTMLElementEventMap {
        [OrFleetRowClickEvent.NAME]: OrFleetRowClickEvent;
        [OrFleetPageEvent.NAME]: OrFleetPageEvent;
        [OrFleetColumnsEvent.NAME]: OrFleetColumnsEvent;
        [OrFleetSortEvent.NAME]: OrFleetSortEvent;
        [OrFleetGroupEvent.NAME]: OrFleetGroupEvent;
    }
}

const GROUP_OPTIONS = ["NONE", "PARENT", "TYPE", "STATUS"];

function translate(key: string, options?: Record<string, unknown>): string {
    return i18next.t(key.includes(":") ? key : `app:${key}`, options);
}

/** Util.resolveUnits has an optional second argument that must not receive a map index. */
const formatUnits = (units?: string[]) => Util.resolveUnits(units);

/**
 * Server-paged, server-sorted, optionally grouped result table. It only renders and emits events;
 * sorting, grouping, paging and column selection are applied by whoever owns the query.
 */
@customElement("or-fleet-results")
export class OrFleetResults extends OrElement {

    static get styles() {
        return css`
            :host { display: block; width: 100%; color: var(--or-app-color3, #222); }
            .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 8px 0; }
            .bar .spacer { flex: 1; }
            .count { font-weight: 600; }
            .toggle { display: inline-flex; border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; overflow: hidden; }
            .toggle button { border: none; background: var(--or-app-color1, #fff); padding: 6px 10px; cursor: pointer; color: inherit; }
            .toggle button[aria-pressed="true"] { background: var(--or-app-color4, #4d9d2a); color: #fff; }
            button.plain { border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; background: var(--or-app-color1, #fff); padding: 6px 10px; cursor: pointer; color: inherit; }
            button.plain:disabled { opacity: .5; cursor: default; }
            .picker { position: relative; }
            .picker summary { cursor: pointer; list-style: none; border: 1px solid var(--or-app-color5, #ccc); border-radius: 4px; padding: 6px 10px; background: var(--or-app-color1, #fff); }
            .picker .menu {
                position: absolute; right: 0; z-index: 10; min-width: 220px; max-height: 320px; overflow-y: auto;
                background: var(--or-app-color1, #fff); border: 1px solid var(--or-app-color5, #ccc);
                border-radius: 4px; box-shadow: 0 2px 8px rgba(0, 0, 0, .2); padding: 8px;
            }
            .picker label { display: flex; align-items: center; gap: 8px; padding: 4px 0; white-space: nowrap; }
            .picker .reset { margin-top: 6px; width: 100%; }
            .scroll { overflow-x: auto; }
            table { width: 100%; border-collapse: collapse; }
            th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--or-app-color2, #eee); white-space: nowrap; }
            th { font-weight: 600; background: var(--or-app-color2, #f5f5f5); }
            td.numeric, th.numeric { text-align: right; }
            tbody tr.row { cursor: pointer; }
            tbody tr.row:hover, tbody tr.row:focus { background: var(--or-app-color2, #f0f0f0); outline: none; }
            .group-header { display: flex; align-items: center; gap: 12px; width: 100%; border: none; cursor: pointer;
                background: var(--or-app-color2, #f5f5f5); color: inherit; padding: 8px 12px; text-align: left; font-size: 15px; }
            .group-header .name { font-weight: 600; }
            .group-header .meta { color: var(--or-app-color5, #777); }
            .group-header .problems { color: var(--or-app-color4, #e3000a); }
            .group-header .continued { font-style: italic; color: var(--or-app-color5, #777); }
            .chip { display: inline-block; margin-right: 4px; padding: 1px 8px; border-radius: 12px;
                background: var(--or-app-color4, #e3000a); color: #fff; font-size: 12px; }
            .empty { padding: 24px; text-align: center; color: var(--or-app-color5, #777); }
            .pager { display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding: 8px 0; }
            .loading { opacity: .5; pointer-events: none; }
        `;
    }

    @property({ attribute: false })
    public result?: FleetQueryResult;

    @property({ type: String })
    public groupBy = "NONE";

    @property({ type: Boolean })
    public loading = false;

    /** Current sort, only used to show the select state. */
    @property({ attribute: false })
    public sort?: { field: string; descending: boolean };

    /** Groups the user collapsed, by key. Reset when the grouping dimension changes. */
    protected _collapsed = new Set<string>();

    protected willUpdate(changed: Map<string, unknown>) {
        if (changed.has("groupBy")) {
            this._collapsed = new Set();
        }
    }

    protected _toggleGroup(key: string) {
        const next = new Set(this._collapsed);
        if (!next.delete(key)) { next.add(key); }
        this._collapsed = next;
        this.requestUpdate();
    }

    protected _selectedAttributes(result: FleetQueryResult): string[] {
        return columnsFrom(result, formatUnits).filter((c) => c.attribute).map((c) => c.key);
    }

    protected _toggleColumn(result: FleetQueryResult, key: string, checked: boolean) {
        const selected = this._selectedAttributes(result).filter((k) => k !== key);
        this.dispatchEvent(new OrFleetColumnsEvent(checked ? [...selected, key] : selected));
    }

    protected _alarmLabel(row: FleetRow, name: string): string {
        const descriptor = row.type ? AssetModelUtil.getAttributeDescriptor(name, row.type) : undefined;
        return Util.getAttributeLabel(undefined, descriptor, row.type, false, name);
    }

    protected _statusLabel(key: string): string {
        return translate(`fleet.status.${key}`);
    }

    protected _renderAge(lastSeen: unknown, now: number): string {
        const age = relativeAge(lastSeen, now);
        if (!age) { return EM_DASH; }
        const formatter = new Intl.RelativeTimeFormat(i18next.language || "en", { numeric: "always", style: "short" });
        return formatter.format(-age.value, age.unit);
    }

    protected _renderCell(row: FleetRow, column: FleetColumn, now: number): TemplateResult | string {
        if (column.attribute) {
            return cellValue(row, column);
        }
        switch (column.key) {
            case "lastSeen":
                return this._renderAge(row.lastSeen, now);
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

    protected _renderResultBar(result: FleetQueryResult, columns: FleetColumn[]): TemplateResult {
        const sortable = columns.filter((c) => c.sortable);
        const field = this.sort?.field ?? "";
        const items = sortable.map((c) => ({ value: c.key, label: c.labelKey ? translate(c.labelKey) : c.label }));
        const offered = offeredColumns(result, formatUnits);
        const selected = columns.filter((c) => c.attribute);
        const shown = (result.rows ?? []).length;
        return html`
            <div class="bar">
                <span class="count">${translate("fleet.result.count", { shown, total: result.total ?? 0 })}</span>
                <span class="spacer"></span>
                <or-vaadin-select
                    style="width: 180px"
                    aria-label=${translate("fleet.sort.label")}
                    .items=${items}
                    .value=${field}
                    @value-changed=${(ev: CustomEvent) => {
                        const next = ev.detail.value as string;
                        if (next && next !== field) { this.dispatchEvent(new OrFleetSortEvent(next, false)); }
                    }}
                ></or-vaadin-select>
                <button type="button" class="plain" ?disabled=${!field}
                        aria-label=${translate(this.sort?.descending ? "fleet.sort.descending" : "fleet.sort.ascending")}
                        @click=${() => this.dispatchEvent(new OrFleetSortEvent(field, !this.sort?.descending))}>
                    ${this.sort?.descending ? "↓" : "↑"}
                </button>
                <span class="toggle" role="group" aria-label=${translate("fleet.groupBy.label")}>
                    ${GROUP_OPTIONS.map((option) => html`
                        <button type="button" aria-pressed=${this.groupBy === option ? "true" : "false"}
                                @click=${() => { if (this.groupBy !== option) { this.dispatchEvent(new OrFleetGroupEvent(option)); } }}>
                            ${translate(`fleet.groupBy.${option.toLowerCase()}`)}
                        </button>`)}
                </span>
                <details class="picker">
                    <summary>${translate("fleet.columns.picker")}</summary>
                    <div class="menu">
                        ${[...selected.map((c) => ({ column: c, checked: true })), ...offered.map((c) => ({ column: c, checked: false }))].map(({ column, checked }) => html`
                            <label>
                                <input type="checkbox" .checked=${checked}
                                       @change=${(ev: Event) => this._toggleColumn(result, column.key, (ev.target as HTMLInputElement).checked)}>
                                ${column.label}
                            </label>`)}
                        <button type="button" class="plain reset"
                                @click=${() => this.dispatchEvent(new OrFleetColumnsEvent([]))}>
                            ${translate("fleet.columns.reset")}
                        </button>
                    </div>
                </details>
            </div>`;
    }

    protected _renderTable(rows: FleetRow[], columns: FleetColumn[], now: number): TemplateResult {
        return html`
            <div class="scroll">
                <table>
                    <thead><tr>
                        ${columns.map((c) => html`
                            <th class=${c.numeric ? "numeric" : ""}>
                                ${c.labelKey ? translate(c.labelKey) : c.label}${c.units ? html` <small>(${c.units})</small>` : nothing}
                            </th>`)}
                    </tr></thead>
                    <tbody>
                        ${rows.map((row) => html`
                            <tr class="row" tabindex="0"
                                @click=${() => this.dispatchEvent(new OrFleetRowClickEvent(row.id))}
                                @keydown=${(ev: KeyboardEvent) => { if (ev.key === "Enter") { this.dispatchEvent(new OrFleetRowClickEvent(row.id)); } }}>
                                ${columns.map((c) => html`<td class=${c.numeric ? "numeric" : ""}>${this._renderCell(row, c, now)}</td>`)}
                            </tr>`)}
                    </tbody>
                </table>
            </div>`;
    }

    protected _groupName(result: FleetQueryResult, key: string, label?: string): string {
        if (this.groupBy === "STATUS") { return this._statusLabel(key); }
        if (this.groupBy === "PARENT" && !key) { return translate("fleet.group.noParent"); }
        return label ?? (key || EM_DASH);
    }

    protected _renderGroup(result: FleetQueryResult, group: RowGroup, columns: FleetColumn[], now: number): TemplateResult {
        const info = groupInfo(result, this.groupBy, group.key);
        const problems = problemCount(group, info.count, now);
        const open = !this._collapsed.has(group.key);
        return html`
            <section>
                <button type="button" class="group-header" aria-expanded=${open ? "true" : "false"}
                        @click=${() => this._toggleGroup(group.key)}>
                    <span>${open ? "▾" : "▸"}</span>
                    ${group.continued
                        ? html`<span class="name">${this._groupName(result, group.key, info.label)}</span>
                               <span class="continued">${translate("fleet.group.continued")}</span>`
                        : html`<span class="name">${this._groupName(result, group.key, info.label)}</span>
                               ${info.count !== undefined ? html`<span class="meta">${translate("fleet.group.devices", { n: info.count })}</span>` : nothing}
                               ${problems ? html`<span class="problems">${translate("fleet.group.problems", { n: problems })}</span>` : nothing}`}
                </button>
                ${open ? this._renderTable(group.rows, columns, now) : nothing}
            </section>`;
    }

    protected _renderPager(result: FleetQueryResult): TemplateResult | typeof nothing {
        const total = result.total ?? 0;
        const offset = result.offset ?? 0;
        const limit = result.limit || (result.rows ?? []).length || 1;
        if (total <= limit) { return nothing; }
        const from = total === 0 ? 0 : offset + 1;
        const to = Math.min(offset + (result.rows ?? []).length, total);
        return html`
            <div class="pager">
                <span>${translate("fleet.pager.range", { from, to, total })}</span>
                <button type="button" class="plain" ?disabled=${offset <= 0}
                        @click=${() => this.dispatchEvent(new OrFleetPageEvent(Math.max(0, offset - limit)))}>
                    ${translate("fleet.pager.previous")}
                </button>
                <button type="button" class="plain" ?disabled=${offset + limit >= total}
                        @click=${() => this.dispatchEvent(new OrFleetPageEvent(offset + limit))}>
                    ${translate("fleet.pager.next")}
                </button>
            </div>`;
    }

    protected render() {
        const result = this.result;
        if (!result) { return nothing; }
        const now = Date.now();
        const columns = columnsFrom(result, formatUnits);
        const groups = segmentGroups(result, this.groupBy, now);
        return html`
            ${this._renderResultBar(result, columns)}
            <div class=${this.loading ? "loading" : ""} aria-busy=${this.loading ? "true" : "false"}>
                ${groups.length === 0
                    ? html`<div class="empty">${translate("fleet.empty")}</div>`
                    : this.groupBy === "NONE"
                        ? this._renderTable(groups[0].rows, columns, now)
                        : groups.map((group) => this._renderGroup(result, group, columns, now))}
            </div>
            ${this._renderPager(result)}`;
    }
}
