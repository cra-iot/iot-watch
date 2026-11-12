import { css, html, nothing, type PropertyValues, type TemplateResult } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { OrElement } from "@openremote/or-element";
import { i18next, translate as translateMixin } from "@openremote/or-translate";
import type { FleetExportOptions, FleetExportOptionsAttribute, FleetExportOptionsType, FleetQuery } from "model";
// The project's generated client, as in page-fleet.
import rest from "rest";
import {
    defaultRange, fileNameFrom, initialSelection, rangeError, SELECTION_KEY, toExportRequest, type Selection,
} from "./fleet-export";

export class OrFleetExportCloseEvent extends CustomEvent<undefined> {
    public static readonly NAME = "or-fleet-export-close";

    constructor() {
        super(OrFleetExportCloseEvent.NAME, { bubbles: true, composed: true });
    }
}

/** A message to show: a translation key, or text the server sent. */
type Problem = { key: string } | { text: string };

function readRemembered(): Selection | undefined {
    try {
        const stored = window.localStorage.getItem(SELECTION_KEY);
        return stored ? JSON.parse(stored) as Selection : undefined;
    } catch {
        // Storage unavailable or damaged: the server's defaults apply.
        return undefined;
    }
}

function remember(selection: Selection): void {
    try {
        window.localStorage.setItem(SELECTION_KEY, JSON.stringify(selection));
    } catch {
        // Remembering the choice is best effort.
    }
}

/**
 * Dialog for the fleet CSV export: day range (UTC) and the attributes per asset type.
 * The caller owns the query; the dialog fires or-fleet-export-close when it is done.
 */
@customElement("or-fleet-export-dialog")
export class OrFleetExportDialog extends translateMixin(i18next)(OrElement) {

    static get styles() {
        return css`
            :host { color: var(--or-app-color3, #222); }
            dialog { border: 1px solid #ccc; border-radius: 6px; padding: 16px 20px; width: min(640px, 94vw);
                max-height: 90vh; box-sizing: border-box; color: inherit; background: var(--or-app-color1, #fff); }
            dialog::backdrop { background: rgba(0, 0, 0, 0.4); }
            h2 { margin: 0 0 12px; font-size: 18px; }
            .range { display: flex; gap: 16px; flex-wrap: wrap; }
            label.date { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
            .help { color: var(--or-app-color3, #4c4c4c); font-size: 12px; margin: 8px 0 12px; }
            section.type { border-top: 1px solid #e5e5e5; padding: 8px 0; }
            .head { display: flex; align-items: center; gap: 8px; }
            .head .name { font-weight: bold; flex: 1; }
            fieldset { border: 0; margin: 4px 0; padding: 0; }
            legend { font-size: 12px; color: #6b6b6b; padding: 0; }
            .attributes { display: flex; flex-wrap: wrap; gap: 2px 16px; }
            .attributes label { display: flex; align-items: center; gap: 4px; font-size: 13px; }
            .error { color: var(--or-app-color4, #e3000a); padding: 6px 0; min-height: 1em; }
            .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
            button { font: inherit; cursor: pointer; }
            button:disabled { cursor: default; opacity: 0.5; }
            button.export { background: var(--or-app-color4, #e3000a); color: #fff; border: 0; border-radius: 4px; padding: 6px 14px; }
            button.cancel { background: none; border: 1px solid #ccc; border-radius: 4px; padding: 6px 14px; color: inherit; }
            button.all, button.none { background: none; border: 0; color: var(--or-app-color4, #e3000a); text-decoration: underline; padding: 0; }
        `;
    }

    @property({ attribute: false }) public query?: FleetQuery;
    @property({ type: Boolean }) public open = false;

    @state() protected _options?: FleetExportOptions;
    @state() protected _selection: Selection = {};
    @state() protected _from = "";
    @state() protected _to = "";
    @state() protected _busy = false;
    @state() protected _problem?: Problem;

    /** Drops an options answer that arrives after the dialog was closed or reopened. */
    protected _loadId = 0;

    protected updated(changed: PropertyValues): void {
        if (!changed.has("open")) { return; }
        const dialog = this.shadowRoot?.querySelector("dialog");
        if (this.open) {
            // jsdom and old browsers may lack showModal.
            if (dialog && !dialog.open) {
                if (typeof dialog.showModal === "function") { dialog.showModal(); } else { dialog.setAttribute("open", ""); }
            }
            this._load();
        } else {
            this._loadId++;
            if (dialog?.open) {
                if (typeof dialog.close === "function") { dialog.close(); } else { dialog.removeAttribute("open"); }
            }
        }
    }

    protected async _load(): Promise<void> {
        const loadId = ++this._loadId;
        this._options = undefined;
        this._problem = undefined;
        this._busy = false;
        try {
            const response = await rest.api.FleetResource.exportOptions(this.query!);
            if (loadId !== this._loadId) { return; }
            const options = response.data;
            const range = defaultRange(options.maxTo);
            this._from = range.from;
            this._to = range.to;
            this._selection = initialSelection(options, readRemembered());
            this._options = options;
        } catch {
            if (loadId === this._loadId) { this._problem = { key: "app:fleet.export.error.failed" }; }
        }
    }

    protected _close(): void {
        this.dispatchEvent(new OrFleetExportCloseEvent());
    }

    protected _names(type: FleetExportOptionsType): string[] {
        return [...(type.identity ?? []), ...(type.measurements ?? [])].map((a) => a.name);
    }

    protected _setAll(type: FleetExportOptionsType, all: boolean): void {
        this._selection = { ...this._selection, [type.type]: all ? this._names(type) : [] };
    }

    protected _toggle(type: FleetExportOptionsType, name: string, on: boolean): void {
        const current = this._selection[type.type] ?? [];
        // Keep the options' order, not the click order.
        this._selection = {
            ...this._selection,
            [type.type]: this._names(type).filter((n) => (n === name ? on : current.includes(n))),
        };
    }

    protected _nothingSelected(): boolean {
        return Object.values(this._selection).every((names) => names.length === 0);
    }

    protected _rangeError(): string | undefined {
        return this._options ? rangeError(this._from, this._to, this._options.maxTo) : undefined;
    }

    protected async _problemOf(e: any): Promise<Problem> {
        const status = e?.response?.status;
        if (status === 429) { return { key: "app:fleet.export.error.busy" }; }
        if (status === 400 || status === 403) {
            try {
                // With responseType blob the message body is a Blob.
                const data = e.response.data;
                const text = (typeof data === "string" ? data : await data?.text?.())?.trim();
                if (text) { return { text }; }
            } catch {
                // Unreadable body: the generic message applies.
            }
        }
        return { key: "app:fleet.export.error.failed" };
    }

    protected async _export(): Promise<void> {
        if (this._busy || !this._options || this._rangeError() || this._nothingSelected()) { return; }
        this._busy = true;
        this._problem = undefined;
        try {
            const request = toExportRequest(this.query!, this._from, this._to, this._selection);
            const response = await rest.api.FleetResource.export(request, { responseType: "blob" });
            const url = URL.createObjectURL(response.data as Blob);
            const link = document.createElement("a");
            link.href = url;
            link.download = fileNameFrom(response.headers?.["content-disposition"], `fleet-${this._from}_${this._to}.csv`);
            document.body.appendChild(link);
            link.click();
            link.remove();
            URL.revokeObjectURL(url);
            remember(this._selection);
            this._busy = false;
            this._close();
        } catch (e) {
            this._problem = await this._problemOf(e);
            this._busy = false;
        }
    }

    protected _renderAttributes(type: FleetExportOptionsType, legend: string, attributes?: FleetExportOptionsAttribute[]): TemplateResult | typeof nothing {
        if (!attributes?.length) { return nothing; }
        const selected = this._selection[type.type] ?? [];
        return html`
            <fieldset>
                <legend><or-translate value=${legend}></or-translate></legend>
                <div class="attributes">
                    ${attributes.map((a) => html`
                        <label title=${a.name}>
                            <input type="checkbox" data-name=${a.name} .checked=${selected.includes(a.name)}
                                   @change=${(e: Event) => this._toggle(type, a.name, (e.target as HTMLInputElement).checked)}>
                            ${a.label ?? a.name}
                        </label>`)}
                </div>
            </fieldset>`;
    }

    protected _renderType(type: FleetExportOptionsType): TemplateResult {
        const label = i18next.t(`app:label.asset.${type.type}`, { defaultValue: type.type });
        return html`
            <section class="type">
                <div class="head">
                    <span class="name">${label} (${type.deviceCount})</span>
                    <button type="button" class="all" @click=${() => this._setAll(type, true)}><or-translate value="app:fleet.export.all"></or-translate></button>
                    <button type="button" class="none" @click=${() => this._setAll(type, false)}><or-translate value="app:fleet.export.none"></or-translate></button>
                </div>
                ${this._renderAttributes(type, "app:fleet.export.identity", type.identity)}
                ${this._renderAttributes(type, "app:fleet.export.measurements", type.measurements)}
            </section>`;
    }

    protected render(): TemplateResult {
        const types = this._options?.types ?? [];
        const total = types.reduce((sum, type) => sum + (type.deviceCount ?? 0), 0);
        const rangeProblem = this._rangeError();
        const problem: Problem | undefined = rangeProblem ? { key: rangeProblem } : this._problem;
        const maxTo = this._options?.maxTo;
        return html`
            <dialog aria-labelledby="title" @cancel=${(e: Event) => { e.preventDefault(); this._close(); }}>
                <h2 id="title"><or-translate value="app:fleet.export.title" .options=${{ count: total }}></or-translate></h2>
                <div class="range">
                    <label class="date"><span><or-translate value="app:fleet.export.from"></or-translate> (<or-translate value="app:fleet.export.utc"></or-translate>)</span>
                        <input class="from" type="date" max=${maxTo ?? nothing} .value=${this._from}
                               @input=${(e: Event) => { this._from = (e.target as HTMLInputElement).value; }}></label>
                    <label class="date"><span><or-translate value="app:fleet.export.to"></or-translate> (<or-translate value="app:fleet.export.utc"></or-translate>)</span>
                        <input class="to" type="date" max=${maxTo ?? nothing} .value=${this._to}
                               @input=${(e: Event) => { this._to = (e.target as HTMLInputElement).value; }}></label>
                </div>
                <p class="help"><or-translate value="app:fleet.export.help"></or-translate></p>
                ${types.map((type) => this._renderType(type))}
                <div class="error" role="alert">
                    ${problem ? ("key" in problem ? html`<or-translate value=${problem.key}></or-translate>` : problem.text) : nothing}
                </div>
                <div class="actions">
                    <button type="button" class="cancel" @click=${() => this._close()}><or-translate value="app:fleet.export.cancel"></or-translate></button>
                    <button type="button" class="export" ?disabled=${!this._options || !!rangeProblem || this._nothingSelected() || this._busy}
                            @click=${() => this._export()}><or-translate value="app:fleet.export.export"></or-translate></button>
                </div>
            </dialog>`;
    }
}
