import { css, html, nothing, type PropertyValues, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import { Util } from "@openremote/core";
import { OrElement } from "@openremote/or-element";
import { i18next, translate as translateMixin } from "@openremote/or-translate";
import "@openremote/or-vaadin-components/or-vaadin-select";
import "@openremote/or-vaadin-components/or-vaadin-radio-group";
import "@openremote/or-vaadin-components/or-vaadin-date-picker";
import "@openremote/or-vaadin-components/or-vaadin-badge";
import { FleetExportReport, type FleetExportOptions } from "model";
import { defaultType, periodChoices, rangeProblem, type PeriodChoice, type PeriodKey } from "./fleet-export";

/** The form's current choice, resolved to a range; problem is a translation key. */
export interface FleetExportChoice {
    assetType?: string;
    report: FleetExportReport;
    period: PeriodKey;
    from: string;
    to: string;
    problem?: string;
}

export class OrFleetExportFormChangeEvent extends CustomEvent<FleetExportChoice> {
    public static readonly NAME = "or-fleet-export-form-change";

    constructor(choice: FleetExportChoice) {
        super(OrFleetExportFormChangeEvent.NAME, { bubbles: true, composed: true, detail: choice });
    }
}

const REPORTS = [FleetExportReport.MONTHLY, FleetExportReport.DAILY];
const DEFAULT_MAX_MONTHS = 24;

const t = (key: string, options?: Record<string, unknown>) => i18next.t(`app:fleet.export.${key}`, options);

/** A yyyy-MM-dd day as a UTC date, so formatting does not shift it by the browser's zone. */
const utcDate = (day: string) => new Date(`${day}T00:00:00Z`);

function formatRange(report: FleetExportReport, from: string, to: string): string {
    const format = new Intl.DateTimeFormat(i18next.language, report === FleetExportReport.MONTHLY
        ? { month: "short", year: "numeric", timeZone: "UTC" }
        : { day: "numeric", month: "numeric", year: "numeric", timeZone: "UTC" });
    const start = format.format(utcDate(from));
    const end = format.format(utcDate(to));
    return start === end ? start : `${start} – ${end}`;
}

/** The first days of the last count months up to lastMonth (YYYY-MM), newest first. */
function recentMonths(lastMonth: string, count: number): string[] {
    const [year, month] = lastMonth.split("-").map(Number);
    return Array.from({ length: count }, (_, i) => {
        const date = new Date(Date.UTC(year, month - 1 - i, 1));
        return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-01`;
    });
}

/**
 * The export choices: meter type, report and period. Owns no data: the dialog passes the options
 * and the starting choice, and the form fires or-fleet-export-form-change with every resolved choice.
 */
@customElement("or-fleet-export-form")
export class OrFleetExportForm extends translateMixin(i18next)(OrElement) {

    static get styles() {
        return css`
            :host { display: flex; flex-direction: column; gap: var(--lumo-space-m); }
            or-vaadin-select, or-vaadin-radio-group { width: 100%; }
            vaadin-radio-button { box-sizing: border-box; width: 100%; margin: 0 0 var(--lumo-space-s);
                padding: var(--lumo-space-s) var(--lumo-space-m); border: 1px solid var(--lumo-contrast-20pct);
                border-radius: var(--lumo-border-radius-m); background: var(--lumo-base-color); }
            vaadin-radio-button[checked] { border-color: var(--lumo-primary-color); }
            vaadin-radio-button[disabled] { background: var(--lumo-contrast-5pct); }
            .card { display: flex; flex-direction: column; gap: 2px; }
            .name { display: flex; align-items: center; flex-wrap: wrap; gap: var(--lumo-space-s); font-weight: 600; }
            .hint { color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s); }
            .range { display: flex; flex-wrap: wrap; gap: var(--lumo-space-m); }
            .range > * { flex: 1 1 160px; min-width: 0; }
            .note { margin: 0; }
        `;
    }

    @property({ attribute: false }) public options?: FleetExportOptions;
    @property({ attribute: false }) public assetType?: string;
    @property({ attribute: false }) public report: FleetExportReport = FleetExportReport.MONTHLY;
    @property({ attribute: false }) public period: PeriodKey = "lastMonth";
    @property({ attribute: false }) public customFrom = "";
    @property({ attribute: false }) public customTo = "";
    @property({ type: Boolean }) public busy = false;

    protected _choices(): PeriodChoice[] {
        return this.options ? periodChoices(this.report, String(this.options.lastDay), String(this.options.lastMonth)) : [];
    }

    protected willUpdate(changed: PropertyValues): void {
        if (!this.options) { return; }
        const types = this.options.types ?? [];
        if (!types.some((type) => type.type === this.assetType)) {
            this.assetType = defaultType(this.options);
        }
        if (!REPORTS.includes(this.report)) {
            this.report = FleetExportReport.MONTHLY;
        }
        const choices = this._choices();
        if (!choices.some((choice) => choice.key === this.period && !choice.disabled)) {
            this.period = "lastMonth";
        }
        // A custom range starts as last month; the other report's range does not fit the pickers.
        const reportSwitched = changed.has("report") && changed.get("report") !== undefined;
        if (this.period === "custom" && (reportSwitched || !this.customFrom || !this.customTo)) {
            const lastMonth = choices.find((choice) => choice.key === "lastMonth")!;
            this.customFrom = lastMonth.from;
            this.customTo = lastMonth.to;
        }
    }

    protected updated(changed: PropertyValues): void {
        const watched = ["options", "assetType", "report", "period", "customFrom", "customTo"];
        if (this.options && watched.some((name) => changed.has(name))) {
            this.dispatchEvent(new OrFleetExportFormChangeEvent(this._resolve()));
        }
    }

    protected _resolve(): FleetExportChoice {
        const custom = this.period === "custom";
        const choice = this._choices().find((c) => c.key === this.period);
        const from = custom ? this.customFrom : choice?.from ?? "";
        const to = custom ? this.customTo : choice?.to ?? "";
        const problem = this.assetType ? rangeProblem(this.report, from, to, this.options!) : "app:fleet.export.error.selectType";
        const resolved: FleetExportChoice = { assetType: this.assetType, report: this.report, period: this.period, from, to };
        return problem ? { ...resolved, problem } : resolved;
    }

    protected _renderType(): TemplateResult | typeof nothing {
        const types = this.options?.types ?? [];
        if (types.length < 2) { return nothing; }
        const items = types.map((type) => ({
            label: `${Util.getAssetTypeLabel(type.type) || type.type} (${type.deviceCount ?? 0})`,
            value: type.type,
        }));
        return html`
            <or-vaadin-select class="type" label=${t("type")} .items=${items} .value=${this.assetType ?? ""} ?disabled=${this.busy}
                              @value-changed=${(e: CustomEvent) => { if (e.detail.value) { this.assetType = e.detail.value; } }}></or-vaadin-select>`;
    }

    protected _renderReport(): TemplateResult {
        const card = (value: string, name: string, hint: string, badge = false) => html`
            <vaadin-radio-button value=${value} ?disabled=${badge || this.busy}>
                <label slot="label" class="card">
                    <span class="name">${t(name)}${badge
                        ? html`<or-vaadin-badge><or-translate value="app:fleet.export.inPreparation"></or-translate></or-vaadin-badge>`
                        : nothing}</span>
                    <span class="hint">${t(hint)}</span>
                </label>
            </vaadin-radio-button>`;
        return html`
            <or-vaadin-radio-group class="report" theme="vertical" .value=${this.report}
                                   @value-changed=${(e: CustomEvent) => {
                                       if (REPORTS.includes(e.detail.value)) { this.report = e.detail.value; }
                                   }}>
                ${card(FleetExportReport.MONTHLY, "report.monthly", "report.monthlyHint")}
                ${card(FleetExportReport.DAILY, "report.daily", "report.dailyHint")}
                ${card("custom", "report.custom", "report.customHint", true)}
            </or-vaadin-radio-group>`;
    }

    protected _renderPeriod(): TemplateResult {
        const items = this._choices().map((choice) => {
            const name = t(`period.${choice.key}`);
            const ranged = choice.key !== "custom" && !choice.disabled;
            return { label: ranged ? `${name} (${formatRange(this.report, choice.from, choice.to)})` : name, value: choice.key, disabled: choice.disabled };
        });
        return html`
            <or-vaadin-select class="period" label=${`${t("period")} (${t("czechTime")})`} .items=${items} .value=${this.period}
                              ?disabled=${this.busy}
                              @value-changed=${(e: CustomEvent) => { if (e.detail.value) { this.period = e.detail.value; } }}></or-vaadin-select>`;
    }

    protected _renderCustom(): TemplateResult | typeof nothing {
        if (this.period !== "custom" || !this.options) { return nothing; }
        const setFrom = (e: CustomEvent) => { this.customFrom = e.detail.value ?? ""; };
        const setTo = (e: CustomEvent) => { this.customTo = e.detail.value ?? ""; };
        if (this.report === FleetExportReport.MONTHLY) {
            const format = new Intl.DateTimeFormat(i18next.language, { month: "long", year: "numeric", timeZone: "UTC" });
            const months = recentMonths(String(this.options.lastMonth), this.options.maxMonths ?? DEFAULT_MAX_MONTHS)
                .map((month) => ({ label: format.format(utcDate(month)), value: month }));
            return html`
                <div class="range">
                    <or-vaadin-select class="from" label=${t("from")} .items=${months} .value=${this.customFrom} ?disabled=${this.busy}
                                      @value-changed=${setFrom}></or-vaadin-select>
                    <or-vaadin-select class="to" label=${t("to")} .items=${months} .value=${this.customTo} ?disabled=${this.busy}
                                      @value-changed=${setTo}></or-vaadin-select>
                </div>`;
        }
        const lastDay = String(this.options.lastDay);
        return html`
            <div class="range">
                <or-vaadin-date-picker class="from" label=${t("from")} .max=${lastDay} .value=${this.customFrom} ?disabled=${this.busy}
                                       @value-changed=${setFrom}></or-vaadin-date-picker>
                <or-vaadin-date-picker class="to" label=${t("to")} .max=${lastDay} .value=${this.customTo} ?disabled=${this.busy}
                                       @value-changed=${setTo}></or-vaadin-date-picker>
            </div>`;
    }

    protected render(): TemplateResult | typeof nothing {
        if (!this.options) { return nothing; }
        if (!this.options.types?.length) {
            return html`<p class="note"><or-translate value="app:fleet.export.noTypes"></or-translate></p>`;
        }
        return html`
            ${this._renderType()}
            ${this._renderReport()}
            ${this._renderPeriod()}
            ${this._renderCustom()}
        `;
    }
}
