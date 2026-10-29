// AssetModelUtil must come from @openremote/model, not from the local "model" package: the
// manager populates the @openremote/model copy at startup, and the local copy stays empty.
import { AssetModelUtil, type AttributeDescriptor } from "@openremote/model";
import type { FleetQuery, FleetQueryAttributeFilter, FleetQueryOperator } from "model";
import { parseHidden, serializeHidden } from "./fleet-columns";
import { GROUP_OPTIONS, STATUS_KEYS } from "./fleet-groups";
import { DEFAULT_PAGE_SIZE, parsePageSize } from "./fleet-pager";

export type TokenKind =
    | "text" | "type" | "status" | "alarm" | "seen" | "delivery" | "serial"
    | "deveui" | "battery" | "reading" | "site" | "attr";

export type TokenOperator =
    | "EQUALS" | "CONTAINS" | "LESS_THAN" | "LESS_EQUALS"
    | "GREATER_THAN" | "GREATER_EQUALS" | "BETWEEN";

export interface Token {
    kind: TokenKind;
    /** For a list, the values joined by a space. */
    value: string;
    /** Free-text list: two or more identifiers, each matched on its own. */
    values?: string[];
    operator?: TokenOperator;
    rangeValue?: string;
}

export interface TokenDefinition {
    kind: TokenKind;
    labelKey: string;
    /** Value suggestions; built from descriptors so a new meter type needs no change here. */
    values?: { value: string; label: string }[];
    numeric?: boolean;
}

const SYMBOLS: [string, TokenOperator][] = [
    [">=", "GREATER_EQUALS"], ["<=", "LESS_EQUALS"],
    [">", "GREATER_THAN"], ["<", "LESS_THAN"], ["=", "EQUALS"],
];

const ATTRIBUTE_BY_KIND: Partial<Record<TokenKind, string>> = {
    delivery: "delivery_point_id",
    serial: "meter_id",
    deveui: "devEui",
    battery: "battery_level",
};

export interface SiteOption {
    /** The parent asset id: what the token, the query and the URL carry. */
    value: string;
    /** The parent's name, shown on the chip and in the suggestions. */
    label: string;
}

/**
 * Adds the parent facets of a result to the locations already known, by id. A facet without a
 * label is a location the caller may not read: it keeps a name learnt earlier, otherwise it is
 * shown as `unnamed` - never by its id, which stays in the token and the URL only.
 */
export function mergeSiteOptions(
    known: SiteOption[], parents: { key?: string; label?: string }[], unnamed: string,
): SiteOption[] {
    const byId = new Map(known.map((site) => [site.value, site.label]));
    parents.forEach((facet) => {
        if (facet.key) { byId.set(facet.key, facet.label ?? byId.get(facet.key) ?? unnamed); }
    });
    return [...byId]
        .map(([value, label]) => ({ value, label }))
        .sort((a, b) => a.label.localeCompare(b.label));
}

/** The name a site: chip shows for a parent id; `unnamed` when the page does not know it. */
export function siteLabel(sites: SiteOption[], id: string, unnamed: string): string {
    return sites.find((site) => site.value === id)?.label ?? unnamed;
}

/**
 * Token categories offered by the bar; alarm values come from the registered asset types.
 * `sites` are the locations the page knows (from the last result's parent facet); when
 * given, site: picks from them by name instead of taking a raw asset id. `typeLabel` names an
 * asset type for the type: values and `alarmLabel` an alarm attribute (Util.getAssetTypeLabel and
 * Util.getAttributeLabel in the app, so both follow the language; kept out to stay pure).
 */
/** The descriptor's own label, else its internal name. */
function descriptorLabel(attribute: AttributeDescriptor): string {
    return (attribute.meta?.["label"] as string) ?? attribute.name!;
}

export function tokenDefinitions(
    sites: SiteOption[] = [], typeLabel: (name: string) => string = (name) => name,
    alarmLabel: (attribute: AttributeDescriptor) => string = descriptorLabel,
    statusLabel: (key: string) => string = (key) => key,
): TokenDefinition[] {
    const alarms = new Map<string, string>();
    const types: { value: string; label: string }[] = [];

    AssetModelUtil.getAssetDescriptors()
        .filter((descriptor) => descriptor.descriptorType === "asset")
        .forEach((descriptor) => {
            const name = descriptor.name!;
            types.push({ value: name, label: typeLabel(name) });
            const info = AssetModelUtil.getAssetTypeInfo(name);
            (info?.attributeDescriptors ?? []).forEach((attribute) => {
                // State booleans (server-side FleetColumns.STATE_BOOLEANS, e.g. valve_closed) are offered
                // too and filter on value == true; a server-published alarm list is the follow-up.
                if (attribute.type === "boolean" && attribute.name && !alarms.has(attribute.name)) {
                    alarms.set(attribute.name, alarmLabel(attribute));
                }
            });
        });

    return [
        { kind: "type", labelKey: "fleet.token.type", values: types },
        { kind: "status", labelKey: "fleet.token.status", values: STATUS_KEYS.map((key) => ({ value: key, label: statusLabel(key) })) },
        {
            kind: "alarm", labelKey: "fleet.token.alarm",
            values: [...alarms].map(([value, label]) => ({ value, label })),
        },
        { kind: "seen", labelKey: "fleet.token.seen" },
        { kind: "delivery", labelKey: "fleet.token.delivery" },
        { kind: "serial", labelKey: "fleet.token.serial" },
        { kind: "deveui", labelKey: "fleet.token.deveui" },
        { kind: "battery", labelKey: "fleet.token.battery", numeric: true },
        { kind: "reading", labelKey: "fleet.token.reading", numeric: true },
        { kind: "site", labelKey: "fleet.token.site", ...(sites.length ? { values: sites } : {}) },
        { kind: "attr", labelKey: "fleet.token.attr" },
    ];
}

const KINDS = new Set<string>(tokenDefinitionKinds());

function tokenDefinitionKinds(): TokenKind[] {
    return ["type", "status", "alarm", "seen", "delivery", "serial", "deveui", "battery", "reading", "site", "attr"];
}

/** Splits on whitespace but keeps double-quoted runs together; an unterminated quote runs to the end. */
function segments(input: string): string[] {
    const out: string[] = [];
    let current = "";
    let quoted = false;
    for (const char of input) {
        if (char === '"') {
            quoted = !quoted;
            current += char;
        } else if (char === " " && !quoted) {
            if (current) { out.push(current); current = ""; }
        } else {
            current += char;
        }
    }
    if (current) { out.push(current); }
    return out;
}

function unquote(value: string): string {
    return value.replace(/^"/, "").replace(/"$/, "");
}

/** A device identifier (devEUI, serial, delivery point): long enough and with a digit, unlike a word of a name. */
const IDENTIFIER = /^(?=.*\d)[^\s"]{6,}$/;

/**
 * Free-text values are ORed by the server, so a typed name must not fall apart into words:
 * consecutive plain words form one phrase ("Albertova 23 A"). Consecutive identifiers form one
 * list token whose values each match on their own (pasted devEUIs). A quoted run stands alone.
 */
export function parseSearch(input: string): Token[] {
    const tokens: Token[] = [];
    let phrase: Token | undefined;
    let list: Token | undefined;
    for (const segment of segments(input)) {
        const token = parseSegment(segment);
        const free = token.kind === "text" && !segment.startsWith('"');
        const identifier = free && IDENTIFIER.test(segment);
        if (free && !identifier && phrase) {
            phrase.value = `${phrase.value} ${token.value}`;
            continue;
        }
        if (identifier && list) {
            const values = list.values ?? [list.value];
            if (!values.includes(token.value)) {
                list.values = [...values, token.value];
                list.value = list.values.join(" ");
            }
            continue;
        }
        tokens.push(token);
        phrase = free && !identifier ? token : undefined;
        list = identifier ? token : undefined;
    }
    return tokens;
}

/**
 * The identifiers of a pasted list - separated by spaces, commas, semicolons or line breaks, as
 * copied from a spreadsheet or an e-mail - or undefined when the text is not such a list.
 */
export function identifierList(text: string): string[] | undefined {
    const values = [...new Set(text.split(/[\s,;]+/).filter(Boolean))];
    return values.length >= 2 && values.every((value) => IDENTIFIER.test(value)) ? values : undefined;
}

/** Every free-text value of the tokens, a list's values one by one. */
export function textValues(tokens: Token[]): string[] {
    return tokens.filter((token) => token.kind === "text").flatMap((token) => token.values ?? [token.value]);
}

function parseSegment(segment: string): Token {
    const colon = segment.indexOf(":");
    const kind = colon > 0 ? segment.slice(0, colon) : "";
    if (!KINDS.has(kind)) {
        return { kind: "text", value: unquote(segment) };
    }
    let rest = segment.slice(colon + 1);
    const symbol = SYMBOLS.find(([text]) => rest.startsWith(text));
    if (symbol) {
        rest = rest.slice(symbol[0].length);
        return { kind: kind as TokenKind, operator: symbol[1], value: unquote(rest) };
    }
    const range = rest.split("..");
    if (range.length === 2) {
        return {
            kind: kind as TokenKind, operator: "BETWEEN",
            value: unquote(range[0]), rangeValue: unquote(range[1]),
        };
    }
    return { kind: kind as TokenKind, value: unquote(rest) };
}

function quoteIfNeeded(value: string): string {
    return value.includes(" ") ? `"${value}"` : value;
}

export function formatSearch(tokens: Token[]): string {
    return tokens.map((token) => {
        if (token.kind === "text") { return token.values ? token.values.join(" ") : quoteIfNeeded(token.value); }
        if (token.operator === "BETWEEN") {
            return `${token.kind}:${token.value}..${token.rangeValue ?? ""}`;
        }
        const symbol = token.operator
            ? (SYMBOLS.find(([, op]) => op === token.operator)?.[0] ?? "")
            : "";
        return `${token.kind}:${symbol}${quoteIfNeeded(token.value)}`;
    }).join(" ");
}

/** "24h" -> "PT24H", "7d" -> "P7D", "30m" -> "PT30M". */
function toIsoDuration(value: string): string | undefined {
    const match = /^(\d+)\s*([hdm])$/i.exec(value.trim());
    if (!match) { return undefined; }
    const [, amount, unit] = match;
    switch (unit.toLowerCase()) {
        case "d": return `P${amount}D`;
        case "h": return `PT${amount}H`;
        default: return `PT${amount}M`;
    }
}

function isNumeric(value: string | undefined): boolean {
    return value !== undefined && value.trim() !== "" && Number.isFinite(Number(value));
}

/**
 * Kinds whose value is text (or a name/id) rather than a number: a comparison or a range on them
 * has no meaning, and the server rejects one with a 400 (FleetResourceImpl.validateOperators).
 * attr: belongs here because its value is always sent as a string.
 */
const TEXT_KINDS = new Set<TokenKind>(["delivery", "serial", "deveui", "site", "type", "status", "alarm", "attr"]);

/** True for `>`, `>=`, `<`, `<=` or a `..` range on a text kind; `=` and no operator are fine. */
function comparisonOnText(token: Token): boolean {
    return TEXT_KINDS.has(token.kind) && token.operator !== undefined && token.operator !== "EQUALS";
}

/** A BETWEEN token needs a numeric upper bound too. */
function rangeOk(token: Token): boolean {
    return token.operator !== "BETWEEN" || isNumeric(token.rangeValue);
}

export function toFleetQuery(tokens: Token[], extras: Record<string, unknown>): FleetQuery {
    const query: FleetQuery = {};
    // Only contract fields are copied; anything else in extras (primaryRegister) stays client-side.
    for (const key of ["groupBy", "collapsed", "sort", "columns", "offset", "limit"] as const) {
        if (extras[key] !== undefined) { (query as Record<string, unknown>)[key] = extras[key]; }
    }
    const text: string[] = [];
    const types: string[] = [];
    const status: string[] = [];
    const parentIds: string[] = [];
    const attributes: FleetQueryAttributeFilter[] = [];

    for (const token of tokens) {
        switch (token.kind) {
            case "text": text.push(...(token.values ?? [token.value])); break;
            case "type": types.push(token.value); break;
            case "status": status.push(token.value); break;
            case "site": parentIds.push(token.value); break;
            case "alarm":
                attributes.push({ name: token.value, operator: "EQUALS" as FleetQueryOperator, value: true });
                break;
            case "seen": {
                const duration = toIsoDuration(token.value);
                if (duration) {
                    // ">" means "older than": a bigger age, not a bigger timestamp.
                    query.seen = token.operator === "LESS_THAN" || token.operator === "LESS_EQUALS"
                        ? { newerThan: duration }
                        : { olderThan: duration };
                }
                break;
            }
            case "reading": {
                // The primary register of the selected type, as the last result reported it.
                // The client keeps no type -> attribute table; when nothing is known yet the
                // token is dropped rather than guessed at.
                const name = extras.primaryRegister as string | undefined;
                if (name && isNumeric(token.value) && rangeOk(token)) {
                    attributes.push({
                        name, operator: (token.operator ?? "EQUALS") as FleetQueryOperator, value: Number(token.value),
                        ...(token.operator === "BETWEEN" ? { rangeValue: Number(token.rangeValue) } : {}),
                    });
                }
                break;
            }
            case "attr": {
                // A URL is not validated by the bar: drop what the server would reject.
                if (comparisonOnText(token)) { break; }
                const [name, value] = token.value.split("=");
                if (name && value !== undefined) {
                    attributes.push({ name, operator: (token.operator ?? "EQUALS") as FleetQueryOperator, value });
                }
                break;
            }
            default: {
                const name = ATTRIBUTE_BY_KIND[token.kind];
                if (!name || comparisonOnText(token)) { break; }
                const numeric = token.kind === "battery";
                if (numeric && !(isNumeric(token.value) && rangeOk(token))) { break; }
                attributes.push({
                    name,
                    operator: (token.operator ?? (numeric ? "EQUALS" : "CONTAINS")) as FleetQueryOperator,
                    value: numeric ? Number(token.value) : token.value,
                    ...(numeric && token.operator === "BETWEEN" ? { rangeValue: Number(token.rangeValue) } : {}),
                });
            }
        }
    }

    // Each value is matched on its own and the matches are ORed.
    if (text.length) { query.text = text; }
    if (types.length) { query.types = types; }
    if (status.length) { query.status = status; }
    if (parentIds.length) { query.parentIds = parentIds; query.recursive = true; }
    if (attributes.length) { query.attributes = attributes; }
    return query;
}

export function hasReadingToken(tokens: Token[]): boolean {
    return tokens.some((token) => token.kind === "reading");
}

/** The primary register of a result, when exactly one primary column is in it. */
export function singlePrimaryRegister(columns: { name?: string; primary?: boolean }[] | undefined): string | undefined {
    const primaries = (columns ?? []).filter((column) => column.primary && column.name);
    return primaries.length === 1 ? primaries[0].name : undefined;
}

export type ReadingFollowUp =
    /** Nothing to do: no reading token, or it was applied with the result's own register. */
    | { action: "none" }
    /** The reading token was dropped or used the wrong register: query again with this one. */
    | { action: "rerun"; primaryRegister: string }
    /** No single primary register: the reading filter needs exactly one asset type. */
    | { action: "unresolved" };

/**
 * Decides what a reading: token needs after a result arrived. The client keeps no type ->
 * register table, so the register comes from the result itself: a URL shared with a reading:
 * token has no previous result, so the first query drops the token and its result names the
 * register to re-run with. `sent` is the register the query used, if any.
 */
export function readingFollowUp(
    tokens: Token[], sent: string | undefined, columns: { name?: string; primary?: boolean }[] | undefined,
): ReadingFollowUp {
    if (!hasReadingToken(tokens)) { return { action: "none" }; }
    const primary = singlePrimaryRegister(columns);
    if (primary) {
        return primary === sent ? { action: "none" } : { action: "rerun", primaryRegister: primary };
    }
    const primaries = (columns ?? []).filter((column) => column.primary).length;
    // An applied filter that matched nothing leaves no columns; that is an answer, not a failure.
    return sent && primaries === 0 ? { action: "none" } : { action: "unresolved" };
}

export interface FleetView {
    groupBy?: string;
    sort?: { field: string; descending: boolean };
    columns?: string[];
    /** Hidden table columns (fleet-columns HIDE_KEYS); an empty list means "show all". */
    hidden?: string[];
    /** Rows per page; one of fleet-pager PAGE_SIZES. */
    pageSize?: number;
}

// PARENT is a server grouping the page no longer offers; an old link with it falls back to none.
const GROUP_VALUES = new Set<string>(GROUP_OPTIONS);

/**
 * Writes the search (q) and, when given, its view: group, sort ("-field" = descending), columns and
 * hidden columns (hide, omitted while it equals the defaults) and the page size (size, omitted at the default).
 */
export function toUrlParams(tokens: Token[], extras: Record<string, string>, view: FleetView = {}): URLSearchParams {
    const params = new URLSearchParams(extras);
    const q = formatSearch(tokens);
    if (q) { params.set("q", q); }
    if (view.groupBy) { params.set("group", view.groupBy); }
    if (view.sort?.field) { params.set("sort", `${view.sort.descending ? "-" : ""}${view.sort.field}`); }
    if (view.columns?.length) { params.set("columns", view.columns.join(",")); }
    const hide = view.hidden ? serializeHidden(view.hidden) : undefined;
    if (hide !== undefined) { params.set("hide", hide); }
    if (view.pageSize && view.pageSize !== DEFAULT_PAGE_SIZE) { params.set("size", String(view.pageSize)); }
    return params;
}

/** Never throws; a missing or invalid parameter is simply absent from the result. */
export function fromUrlParams(params: URLSearchParams): { tokens: Token[] } & FleetView {
    const result: { tokens: Token[] } & FleetView = { tokens: [] };
    try {
        result.tokens = parseSearch(params.get("q") ?? "");
    } catch {
        // A hand-edited or truncated URL must not blank the page.
    }
    const group = params.get("group");
    if (group && GROUP_VALUES.has(group)) { result.groupBy = group; }
    const sort = params.get("sort");
    if (sort) {
        const descending = sort.startsWith("-");
        const field = descending ? sort.slice(1) : sort;
        if (field) { result.sort = { field, descending }; }
    }
    const columns = (params.get("columns") ?? "").split(",").map((c) => c.trim()).filter(Boolean);
    if (columns.length) { result.columns = columns; }
    // "hide=" (empty) is meaningful: every column shown.
    const hidden = parseHidden(params.get("hide"));
    if (hidden) { result.hidden = hidden; }
    const pageSize = parsePageSize(params.get("size"));
    if (pageSize) { result.pageSize = pageSize; }
    return result;
}

export interface TokenSuggestion {
    label: string;
    /** Replaces the input text when picked. */
    input: string;
    /** True when picking it completes a token and should be committed straight away. */
    commit?: boolean;
}

export type TokenValidation =
    | { ok: true }
    | {
        ok: false;
        reason: "emptyValue" | "unknownValue" | "unknownAttribute" | "unknownKind" | "notNumeric" | "invalidDuration"
            | "operatorNotAllowed";
        suggestions: TokenSuggestion[];
    };

/** Classic Levenshtein distance; inputs are short kind names. */
function editDistance(a: string, b: string): number {
    let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
        const current = [i];
        for (let j = 1; j <= b.length; j++) {
            current[j] = Math.min(
                previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
            );
        }
        previous = current;
    }
    return previous[b.length];
}

function kindSuggestions(definitions: TokenDefinition[], word?: string): TokenSuggestion[] {
    return definitions
        .filter((d) => !word || d.kind.startsWith(word.slice(0, 3)) || editDistance(word, d.kind) <= 2)
        .map((d) => ({ label: `${d.kind}:`, input: `${d.kind}:` }));
}

/**
 * Checks a token against the known kinds, value lists and attribute names, so the bar never
 * emits a query the server would silently answer with "no matches". Pure: the caller supplies
 * the descriptors and the attribute names.
 */
export function validateToken(
    token: Token, definitions: TokenDefinition[], knownAttributeNames: string[],
): TokenValidation {
    if (token.kind === "text") {
        const colon = token.value.indexOf(":");
        const word = colon > 0 ? token.value.slice(0, colon).toLowerCase() : "";
        // A "word:" that is not a kind but looks like a mistyped one is not free text.
        if (word.length >= 2 && !definitions.some((d) => d.kind === word)) {
            const close = definitions.filter((d) =>
                (word.length >= 3 && d.kind.startsWith(word.slice(0, 3))) || editDistance(word, d.kind) <= 2);
            if (close.length) {
                return {
                    ok: false, reason: "unknownKind",
                    suggestions: close.map((d) => ({ label: `${d.kind}:`, input: `${d.kind}:` })),
                };
            }
        }
        return { ok: true };
    }

    const definition = definitions.find((d) => d.kind === token.kind);
    if (!definition) {
        return { ok: false, reason: "unknownKind", suggestions: kindSuggestions(definitions) };
    }
    if (comparisonOnText(token)) {
        return { ok: false, reason: "operatorNotAllowed", suggestions: [] };
    }
    if (token.kind === "attr") {
        const [name, value] = token.value.split("=");
        if (!name || !value) {
            return { ok: false, reason: "emptyValue", suggestions: [] };
        }
        if (!knownAttributeNames.includes(name)) {
            const needle = name.toLowerCase();
            return {
                ok: false, reason: "unknownAttribute",
                suggestions: knownAttributeNames
                    .filter((n) => n.toLowerCase().includes(needle) || editDistance(n.toLowerCase(), needle) <= 2)
                    .slice(0, 20)
                    .map((n) => ({ label: n, input: `attr:${n}=` })),
            };
        }
        return { ok: true };
    }
    if (token.value.trim() === "") {
        return {
            ok: false, reason: "emptyValue",
            suggestions: (definition.values ?? []).map((v) => ({
                label: v.label, input: `${token.kind}:${v.value}`, commit: true,
            })),
        };
    }
    if (definition.values && !definition.values.some((v) => v.value === token.value)) {
        const needle = token.value.toLowerCase();
        return {
            ok: false, reason: "unknownValue",
            suggestions: definition.values
                .filter((v) => v.value.toLowerCase().includes(needle) || v.label.toLowerCase().includes(needle))
                .map((v) => ({ label: v.label, input: `${token.kind}:${v.value}`, commit: true })),
        };
    }
    if (definition.numeric && !(isNumeric(token.value) && rangeOk(token))) {
        return { ok: false, reason: "notNumeric", suggestions: [] };
    }
    if (token.kind === "seen" && !toIsoDuration(token.value)) {
        return { ok: false, reason: "invalidDuration", suggestions: [] };
    }
    return { ok: true };
}
