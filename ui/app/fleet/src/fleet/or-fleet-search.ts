import { css, html, nothing, type TemplateResult } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { AssetModelUtil } from "@openremote/model";
import { OrElement } from "@openremote/or-element";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-vaadin-components/or-vaadin-text-field";
import "@openremote/or-vaadin-components/or-vaadin-number-field";
import "@openremote/or-vaadin-components/or-vaadin-select";
import {
    formatSearch, parseSearch, siteLabel, tokenDefinitions, validateToken,
    type SiteOption, type Token, type TokenDefinition, type TokenOperator, type TokenSuggestion,
} from "./fleet-tokens";

export class OrFleetSearchChangeEvent extends CustomEvent<{ tokens: Token[] }> {
    public static readonly NAME = "or-fleet-search-change";

    constructor(tokens: Token[]) {
        super(OrFleetSearchChangeEvent.NAME, {
            bubbles: true,
            composed: true,
            detail: { tokens },
        });
    }
}

declare global {
    interface HTMLElementEventMap {
        [OrFleetSearchChangeEvent.NAME]: OrFleetSearchChangeEvent;
    }
}

const NUMERIC_OPERATORS: { value: TokenOperator; label: string }[] = [
    { value: "EQUALS", label: "=" },
    { value: "GREATER_THAN", label: ">" },
    { value: "GREATER_EQUALS", label: ">=" },
    { value: "LESS_THAN", label: "<" },
    { value: "LESS_EQUALS", label: "<=" },
    { value: "BETWEEN", label: "..." },
];

function translate(key: string): string {
    return i18next.t(key.includes(":") ? key : `app:${key}`);
}

/**
 * Token search bar: chips for the committed tokens and a free input that offers token categories,
 * their values and (for numeric kinds) an operator + number editor. Fires OrFleetSearchChangeEvent
 * whenever the token list changes.
 */
@customElement("or-fleet-search")
export class OrFleetSearch extends OrElement {

    static get styles() {
        return css`
            :host { display: block; position: relative; width: 100%; }
            .bar {
                display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
                padding: 4px 8px; border: 1px solid var(--or-app-color5, #ccc);
                border-radius: 4px; background: var(--or-app-color1, #fff);
            }
            .chip {
                display: inline-flex; align-items: center; gap: 4px; padding: 2px 4px 2px 10px;
                border-radius: 12px; background: var(--or-app-color2, #f0f0f0);
                font-size: 14px; white-space: nowrap;
            }
            .chip button {
                border: none; background: none; cursor: pointer; font-size: 16px; line-height: 1;
                padding: 0 6px; color: inherit;
            }
            or-vaadin-text-field { flex: 1; min-width: 200px; }
            .panel {
                position: absolute; z-index: 10; left: 0; right: 0; top: 100%; margin-top: 2px;
                background: var(--or-app-color1, #fff); border: 1px solid var(--or-app-color5, #ccc);
                border-radius: 4px; box-shadow: 0 2px 8px rgba(0, 0, 0, .2);
                max-height: 320px; overflow-y: auto;
            }
            .panel:focus { outline: none; }
            .error { padding: 8px 12px; color: var(--or-app-color4, #e3000a); }
            .option { padding: 8px 12px; cursor: pointer; }
            .option:hover { background: var(--or-app-color2, #f0f0f0); }
            .editor { display: flex; align-items: end; gap: 8px; padding: 8px 12px; }
            .editor button { height: 36px; padding: 0 12px; cursor: pointer; }
        `;
    }

    @property({ type: Array })
    public tokens: Token[] = [];

    /** Known locations for site:, by name; the token keeps the parent id. Empty means free input. */
    @property({ type: Array })
    public sites: SiteOption[] = [];

    @state() protected _input = "";
    @state() protected _open = false;
    @state() protected _operator: TokenOperator = "EQUALS";
    @state() protected _number = "";
    @state() protected _rangeNumber = "";
    @state() protected _error?: { reason: string; suggestions: TokenSuggestion[] };

    constructor() {
        super();
        // Host-level so the bar and the panel (siblings in the shadow root) share one focus scope.
        this.addEventListener("focusout", (ev) => this._onFocusOut(ev as FocusEvent));
    }

    protected _definitions(): TokenDefinition[] {
        return tokenDefinitions(this.sites);
    }

    /** The category being typed, e.g. `alarm:` or `battery:>`, with whatever follows the colon. */
    protected _activeKind(): { definition: TokenDefinition; rest: string } | undefined {
        const colon = this._input.indexOf(":");
        if (colon <= 0) { return undefined; }
        const kind = this._input.slice(0, colon);
        const definition = this._definitions().find((d) => d.kind === kind);
        return definition ? { definition, rest: this._input.slice(colon + 1) } : undefined;
    }

    protected _emit(tokens: Token[]) {
        this.tokens = tokens;
        this.dispatchEvent(new OrFleetSearchChangeEvent(tokens));
    }

    protected _attributeNames(): string[] {
        const names = new Set<string>();
        AssetModelUtil.getAssetDescriptors()
            .filter((descriptor) => descriptor.descriptorType === "asset")
            .forEach((descriptor) => {
                (AssetModelUtil.getAssetTypeInfo(descriptor.name!)?.attributeDescriptors ?? [])
                    .forEach((attribute) => attribute.name && names.add(attribute.name));
            });
        return [...names];
    }

    /** Refuses the whole batch when any token is invalid: the input stays, the error is shown, nothing is emitted. */
    protected _add(added: Token[]) {
        if (!added.length) { return; }
        const definitions = this._definitions();
        const names = this._attributeNames();
        for (const token of added) {
            const result = validateToken(token, definitions, names);
            if (result.ok === false) {
                this._error = { reason: result.reason, suggestions: result.suggestions };
                this._open = true;
                return;
            }
        }
        this._error = undefined;
        this._input = "";
        this._number = "";
        this._rangeNumber = "";
        this._operator = "EQUALS";
        this._emit([...this.tokens, ...added]);
    }

    protected _remove(index: number) {
        this._emit(this.tokens.filter((_, i) => i !== index));
    }

    /** Commits the typed text; for a value list kind a partial label/value picks the first match. */
    protected _commitInput() {
        const text = this._input.trim();
        if (!text) { return; }
        const active = this._activeKind();
        const options = active?.definition.values;
        if (active && options && active.rest) {
            const needle = active.rest.toLowerCase();
            const exact = options.find((o) => o.value.toLowerCase() === needle);
            const match = exact ?? this._filterValues(options, active.rest)[0];
            if (match) {
                this._add([{ kind: active.definition.kind, value: match.value }]);
                return;
            }
        }
        this._add(parseSearch(text));
    }

    protected _commitNumeric(kind: Token["kind"]) {
        if (this._number.trim() === "") { return; }
        if (this._operator === "BETWEEN") {
            if (this._rangeNumber.trim() === "") { return; }
            this._add([{ kind, operator: "BETWEEN", value: this._number, rangeValue: this._rangeNumber }]);
        } else {
            this._add([{ kind, operator: this._operator, value: this._number }]);
        }
    }

    protected _filterValues(values: { value: string; label: string }[], needle: string) {
        const lower = needle.toLowerCase();
        return values.filter((v) => v.label.toLowerCase().includes(lower) || v.value.toLowerCase().includes(lower));
    }

    protected _onKeydown(ev: KeyboardEvent) {
        if (ev.key === "Enter") {
            ev.preventDefault();
            this._commitInput();
        } else if (ev.key === "Backspace" && this._input === "" && this.tokens.length) {
            this._remove(this.tokens.length - 1);
        } else if (ev.key === "Escape") {
            this._open = false;
        }
    }

    /** Closes the panel only when focus leaves the component; the select overlay lives outside our shadow DOM. */
    protected _onFocusOut(ev: FocusEvent) {
        const next = ev.relatedTarget as Node | null;
        if (next && (this.shadowRoot!.contains(next) || this.contains(next))) { return; }
        if (next instanceof Element && next.closest("vaadin-select-overlay, vaadin-select-item, vaadin-select-list-box")) { return; }
        this._open = false;
    }

    protected _chipLabel(token: Token): string {
        if (token.kind === "text") { return token.value; }
        const definition = this._definitions().find((d) => d.kind === token.kind);
        const label = definition ? translate(definition.labelKey) : token.kind;
        // A site chip never shows the parent id, even before the page knows the location's name.
        const valueLabel = token.kind === "site"
            ? siteLabel(this.sites, token.value, translate("fleet.group.unnamedLocation"))
            : definition?.values?.find((v) => v.value === token.value)?.label;
        // formatSearch renders `kind:<operator><value>`; reuse it for the operator part.
        const rendered = formatSearch([valueLabel ? { ...token, value: valueLabel } : token]);
        return `${label}: ${rendered.slice(token.kind.length + 1)}`;
    }

    protected _renderPanel(): TemplateResult | typeof nothing {
        if (!this._open) { return nothing; }
        const active = this._activeKind();
        // Keep focus in the input while the pointer is on the panel.
        const keepFocus = (ev: Event) => {
            if ((ev.target as HTMLElement).closest(".option")) { ev.preventDefault(); }
        };

        if (this._error) {
            return html`<div class="panel" tabindex="-1" @mousedown=${keepFocus}>
                <div class="error" role="alert">${translate(`fleet.token.error.${this._error.reason}`)}</div>
                ${this._error.suggestions.map((s) => html`
                    <div class="option" @click=${() => {
                        this._error = undefined;
                        this._input = s.input;
                        if (s.commit) { this._commitInput(); }
                    }}>${s.label}</div>`)}
            </div>`;
        }

        if (!active) {
            const needle = this._input.trim().toLowerCase();
            const definitions = this._definitions()
                .filter((d) => !needle || d.kind.startsWith(needle) || translate(d.labelKey).toLowerCase().includes(needle));
            if (!definitions.length) { return nothing; }
            return html`<div class="panel" @mousedown=${keepFocus}>
                ${definitions.map((d) => html`
                    <div class="option" @click=${() => { this._input = `${d.kind}:`; }}>
                        ${translate(d.labelKey)} <small>(${d.kind}:)</small>
                    </div>`)}
            </div>`;
        }

        const { definition, rest } = active;
        if (definition.values) {
            const values = this._filterValues(definition.values, rest);
            return html`<div class="panel" @mousedown=${keepFocus}>
                ${values.map((v) => html`
                    <div class="option" @click=${() => this._add([{ kind: definition.kind, value: v.value }])}>
                        ${v.label}
                    </div>`)}
            </div>`;
        }
        if (definition.numeric) {
            const between = this._operator === "BETWEEN";
            return html`<div class="panel" tabindex="-1">
                <div class="editor">
                    <or-vaadin-select
                        style="width: 90px"
                        .items=${NUMERIC_OPERATORS}
                        .value=${this._operator}
                        @value-changed=${(ev: CustomEvent) => { this._operator = (ev.detail.value || "EQUALS") as TokenOperator; }}
                    ></or-vaadin-select>
                    <or-vaadin-number-field
                        .value=${this._number}
                        @value-changed=${(ev: CustomEvent) => { this._number = ev.detail.value; }}
                        @keydown=${(ev: KeyboardEvent) => { if (ev.key === "Enter") { this._commitNumeric(definition.kind); } }}
                    ></or-vaadin-number-field>
                    ${between ? html`
                        <or-vaadin-number-field
                            .value=${this._rangeNumber}
                            @value-changed=${(ev: CustomEvent) => { this._rangeNumber = ev.detail.value; }}
                            @keydown=${(ev: KeyboardEvent) => { if (ev.key === "Enter") { this._commitNumeric(definition.kind); } }}
                        ></or-vaadin-number-field>` : nothing}
                    <button type="button" @click=${() => this._commitNumeric(definition.kind)}>
                        ${translate("fleet.token.add")}
                    </button>
                </div>
            </div>`;
        }
        // Free text kinds (serial, deveui, ...) are typed into the main input and committed with Enter.
        return nothing;
    }

    protected render() {
        return html`
            <div class="bar">
                ${this.tokens.map((token, index) => html`
                    <span class="chip">${this._chipLabel(token)}
                        <button type="button" aria-label=${translate("fleet.token.remove")}
                                @click=${() => this._remove(index)}>&times;</button>
                    </span>`)}
                <or-vaadin-text-field
                    .value=${this._input}
                    placeholder=${translate("fleet.token.placeholder")}
                    @value-changed=${(ev: CustomEvent) => {
                        const value = ev.detail.value ?? "";
                        if (value !== this._input) { this._error = undefined; }
                        this._input = value;
                    }}
                    @focus=${() => { this._open = true; }}
                    @keydown=${(ev: KeyboardEvent) => this._onKeydown(ev)}
                ></or-vaadin-text-field>
            </div>
            ${this._renderPanel()}`;
    }
}
