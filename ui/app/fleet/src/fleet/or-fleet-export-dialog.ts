import { css, html, nothing, type PropertyValues, type TemplateResult } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { keyed } from "lit/directives/keyed.js";
import { OrElement } from "@openremote/or-element";
import { i18next, translate as translateMixin } from "@openremote/or-translate";
import "@openremote/or-vaadin-components/or-vaadin-dialog";
import "@openremote/or-vaadin-components/or-vaadin-button";
import type { FleetExportOptions, FleetExportReport, FleetQuery } from "model";
// The project's generated client, as in page-fleet.
import rest from "rest";
import {
    CHOICE_KEY, fallbackFileName, fileNameFrom, serverProblem, toExportRequest, type PeriodKey, type RememberedChoice,
} from "./fleet-export";
import "./or-fleet-export-form";
import type { FleetExportChoice } from "./or-fleet-export-form";

export class OrFleetExportCloseEvent extends CustomEvent<undefined> {
    public static readonly NAME = "or-fleet-export-close";

    constructor() {
        super(OrFleetExportCloseEvent.NAME, { bubbles: true, composed: true });
    }
}

/** A message to show: a translation key with its options, or text the server sent. */
type Problem = { key: string; options?: Record<string, string> } | { text: string };

function readRemembered(): RememberedChoice {
    try {
        const stored = window.localStorage.getItem(CHOICE_KEY);
        return stored ? JSON.parse(stored) as RememberedChoice : {};
    } catch {
        // Storage unavailable or damaged: the defaults apply.
        return {};
    }
}

function remember(choice: RememberedChoice): void {
    try {
        window.localStorage.setItem(CHOICE_KEY, JSON.stringify(choice));
    } catch {
        // Remembering the choice is best effort.
    }
}

/**
 * Dialog for the fleet CSV export: one meter type, a monthly or daily report and a period.
 * The caller owns the query; the dialog fires or-fleet-export-close when it is done.
 */
@customElement("or-fleet-export-dialog")
export class OrFleetExportDialog extends translateMixin(i18next)(OrElement) {

    static get styles() {
        return css`
            /* The dialog styles slotted footer content display: contents, which dissolves a button's own box. */
            or-vaadin-button[slot="footer"] { display: inline-flex; }
            or-vaadin-dialog::part(overlay) { width: min(560px, calc(100vw - 2 * var(--lumo-space-m))); }
            .status { padding-top: var(--lumo-space-s); font-size: var(--lumo-font-size-s); }
            .error { color: var(--lumo-error-text-color); padding: var(--lumo-space-s) 0; min-height: 1em; }
            .status:empty, .error:empty { display: none; }
        `;
    }

    @property({ attribute: false }) public query?: FleetQuery;
    @property({ type: Boolean }) public open = false;

    @state() protected _options?: FleetExportOptions;
    /** The starting choice handed to the form; the form keeps the user's changes. */
    @state() protected _assetType?: string;
    @state() protected _report?: FleetExportReport;
    @state() protected _period?: PeriodKey;
    @state() protected _choice?: FleetExportChoice;
    @state() protected _busy = false;
    @state() protected _problem?: Problem;

    /**
     * Drops an options answer that arrives after the dialog was closed or reopened. Each load also gets
     * a new form, so a reopened dialog starts from the remembered choice, not the last one shown.
     */
    protected _loadId = 0;

    protected updated(changed: PropertyValues): void {
        if (!changed.has("open")) { return; }
        if (this.open) {
            this._load();
        } else {
            this._loadId++;
        }
    }

    protected async _load(): Promise<void> {
        const loadId = ++this._loadId;
        this._options = undefined;
        this._choice = undefined;
        this._problem = undefined;
        this._busy = false;
        try {
            const response = await rest.api.FleetResource.exportOptions(this.query!);
            if (loadId !== this._loadId) { return; }
            // The form replaces anything that does not fit these options with its defaults.
            const remembered = readRemembered();
            this._assetType = remembered.assetType;
            this._report = remembered.report;
            this._period = remembered.period;
            this._options = response.data;
        } catch {
            if (loadId === this._loadId) { this._problem = { key: "app:fleet.export.error.failed" }; }
        }
    }

    protected _close(): void {
        if (this._busy) { return; }
        this.dispatchEvent(new OrFleetExportCloseEvent());
    }

    protected _onOpenedChanged(e: CustomEvent): void {
        // Esc closes the Vaadin dialog unless noCloseOnEsc is set, which it is while exporting.
        if (e.detail.value === false && this.open) { this._close(); }
    }

    protected _onChoice(e: CustomEvent<FleetExportChoice>): void {
        this._choice = e.detail;
        this._problem = undefined;
    }

    protected _canExport(): boolean {
        return !!this._options?.types?.length && !!this._choice && !this._choice.problem && !this._busy;
    }

    protected async _problemOf(e: any): Promise<Problem> {
        const status = e?.response?.status;
        if (status === 429) { return { key: "app:fleet.export.error.busy" }; }
        if (status === 400 || status === 403) {
            try {
                // With responseType blob the message body is a Blob.
                const data = e.response.data;
                const text = (typeof data === "string" ? data : await data?.text?.())?.trim();
                if (text) { return serverProblem(text) ?? { text }; }
            } catch {
                // Unreadable body: the generic message applies.
            }
        }
        return { key: "app:fleet.export.error.failed" };
    }

    protected async _export(): Promise<void> {
        if (!this._canExport()) { return; }
        const { assetType, report, period, from, to } = this._choice!;
        this._busy = true;
        this._problem = undefined;
        // A response that arrives after the dialog was closed or reopened is dropped.
        const loadId = this._loadId;
        try {
            const request = toExportRequest(this.query!, assetType!, report, from, to);
            const response = await rest.api.FleetResource.export(request, { responseType: "blob" });
            if (loadId !== this._loadId) { return; }
            const url = URL.createObjectURL(response.data as Blob);
            const link = document.createElement("a");
            link.href = url;
            link.download = fileNameFrom(response.headers?.["content-disposition"], fallbackFileName(from, to));
            document.body.appendChild(link);
            link.click();
            link.remove();
            // Revoking at once can lose the download in Safari and older Firefox.
            window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
            remember({ assetType, report, period });
            this._busy = false;
            this._close();
        } catch (e) {
            this._problem = await this._problemOf(e);
            this._busy = false;
        }
    }

    protected render(): TemplateResult {
        // With no exportable type the form shows a note instead of a problem.
        const rangeProblem = this._options?.types?.length ? this._choice?.problem : undefined;
        const problem: Problem | undefined = rangeProblem ? { key: rangeProblem } : this._problem;
        return html`
            <or-vaadin-dialog .opened=${this.open} .headerTitle=${i18next.t("app:fleet.export.title")}
                              .noCloseOnEsc=${this._busy} no-close-on-outside-click
                              @opened-changed=${(e: CustomEvent) => this._onOpenedChanged(e)}>
                ${keyed(this._loadId, html`
                    <or-fleet-export-form .options=${this._options} .assetType=${this._assetType} .report=${this._report}
                                          .period=${this._period} .busy=${this._busy}
                                          @or-fleet-export-form-change=${(e: CustomEvent<FleetExportChoice>) => this._onChoice(e)}></or-fleet-export-form>`)}
                <div class="status" role="status">${this._busy
                    ? html`<or-translate value="app:fleet.export.exporting"></or-translate>` : nothing}</div>
                <div class="error" role="alert">${problem
                    ? ("key" in problem ? html`<or-translate value=${problem.key} .options=${problem.options}></or-translate>` : problem.text)
                    : nothing}</div>
                <or-vaadin-button slot="footer" class="cancel" ?disabled=${this._busy} @click=${() => this._close()}>
                    <or-translate value="app:fleet.export.cancel"></or-translate>
                </or-vaadin-button>
                <or-vaadin-button slot="footer" class="export" theme="primary" ?disabled=${!this._canExport()}
                                  @click=${() => this._export()}>
                    <or-translate value="app:fleet.export.export"></or-translate>
                </or-vaadin-button>
            </or-vaadin-dialog>`;
    }
}
