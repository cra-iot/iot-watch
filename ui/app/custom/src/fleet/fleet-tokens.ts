// AssetModelUtil must come from @openremote/model, not from the local "model" package: the
// manager populates the @openremote/model copy at startup, and the local copy stays empty.
import { AssetModelUtil } from "@openremote/model";
import type { FleetQuery, FleetQueryAttributeFilter, FleetQueryOperator } from "model";

export type TokenKind =
    | "text" | "type" | "alarm" | "seen" | "delivery" | "serial"
    | "deveui" | "battery" | "reading" | "site" | "attr";

export type TokenOperator =
    | "EQUALS" | "CONTAINS" | "LESS_THAN" | "LESS_EQUALS"
    | "GREATER_THAN" | "GREATER_EQUALS" | "BETWEEN";

export interface Token {
    kind: TokenKind;
    value: string;
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

/** Token categories offered by the bar; alarm values come from the registered asset types. */
export function tokenDefinitions(): TokenDefinition[] {
    const alarms = new Map<string, string>();
    const types: { value: string; label: string }[] = [];

    AssetModelUtil.getAssetDescriptors()
        .filter((descriptor) => descriptor.descriptorType === "asset")
        .forEach((descriptor) => {
            const name = descriptor.name!;
            types.push({ value: name, label: name });
            const info = AssetModelUtil.getAssetTypeInfo(name);
            (info?.attributeDescriptors ?? []).forEach((attribute) => {
                // State booleans (server-side FleetColumns.STATE_BOOLEANS, e.g. valve_closed) are offered
                // too and filter on value == true; a server-published alarm list is the follow-up.
                if (attribute.type === "boolean" && attribute.name) {
                    // Prefer the descriptor's own label; the internal name stays hidden.
                    const label = (attribute.meta?.["label"] as string) ?? attribute.name;
                    alarms.set(attribute.name, label);
                }
            });
        });

    return [
        { kind: "type", labelKey: "fleet.token.type", values: types },
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
        { kind: "site", labelKey: "fleet.token.site" },
        { kind: "attr", labelKey: "fleet.token.attr" },
    ];
}

const KINDS = new Set<string>(tokenDefinitionKinds());

function tokenDefinitionKinds(): TokenKind[] {
    return ["type", "alarm", "seen", "delivery", "serial", "deveui", "battery", "reading", "site", "attr"];
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

export function parseSearch(input: string): Token[] {
    return segments(input).map<Token>((segment) => {
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
    });
}

function quoteIfNeeded(value: string): string {
    return value.includes(" ") ? `"${value}"` : value;
}

export function formatSearch(tokens: Token[]): string {
    return tokens.map((token) => {
        if (token.kind === "text") { return quoteIfNeeded(token.value); }
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

/** A BETWEEN token needs a numeric upper bound too. */
function rangeOk(token: Token): boolean {
    return token.operator !== "BETWEEN" || isNumeric(token.rangeValue);
}

export function toFleetQuery(tokens: Token[], extras: Record<string, unknown>): FleetQuery {
    const query: FleetQuery = {};
    // Only contract fields are copied; anything else in extras (primaryRegister) stays client-side.
    for (const key of ["groupBy", "sort", "columns", "offset", "limit"] as const) {
        if (extras[key] !== undefined) { (query as Record<string, unknown>)[key] = extras[key]; }
    }
    const text: string[] = [];
    const types: string[] = [];
    const parentIds: string[] = [];
    const attributes: FleetQueryAttributeFilter[] = [];

    for (const token of tokens) {
        switch (token.kind) {
            case "text": text.push(token.value); break;
            case "type": types.push(token.value); break;
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
                const [name, value] = token.value.split("=");
                if (name && value !== undefined) {
                    attributes.push({ name, operator: (token.operator ?? "EQUALS") as FleetQueryOperator, value });
                }
                break;
            }
            default: {
                const name = ATTRIBUTE_BY_KIND[token.kind];
                if (!name) { break; }
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

    if (text.length) { query.text = text.join(" "); }
    if (types.length) { query.types = types; }
    if (parentIds.length) { query.parentIds = parentIds; query.recursive = true; }
    if (attributes.length) { query.attributes = attributes; }
    return query;
}

export function toUrlParams(tokens: Token[], extras: Record<string, string>): URLSearchParams {
    const params = new URLSearchParams(extras);
    const q = formatSearch(tokens);
    if (q) { params.set("q", q); }
    return params;
}

export function fromUrlParams(params: URLSearchParams): { tokens: Token[] } {
    try {
        return { tokens: parseSearch(params.get("q") ?? "") };
    } catch {
        // A hand-edited or truncated URL must not blank the page.
        return { tokens: [] };
    }
}
