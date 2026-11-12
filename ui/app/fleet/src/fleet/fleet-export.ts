import type { FleetExportOptions, FleetExportRequest, FleetQuery } from "model";
import { singlePrimaryRegister, toFleetQuery, type Token } from "./fleet-tokens";

export const MAX_DAYS = 366;
export const SELECTION_KEY = "fleet.export.selection";

/** Selected attribute names per asset type. */
export type Selection = Record<string, string[]>;

const DAY_MS = 24 * 60 * 60 * 1000;

/** Parses a plain YYYY-MM-DD string as UTC midnight. */
function utcDay(date: string): number {
    const [y, m, d] = date.split("-").map(Number);
    return Date.UTC(y, m - 1, d);
}

/** Translation key of the first problem, or undefined. maxTo comes from the options response. */
export function rangeError(from: string, to: string, maxTo: string): string | undefined {
    if (!from || !to) {
        return "app:fleet.export.error.missing";
    }
    if (from > to) {
        return "app:fleet.export.error.order";
    }
    if (to > maxTo) {
        return "app:fleet.export.error.future";
    }
    if ((utcDay(to) - utcDay(from)) / DAY_MS + 1 > MAX_DAYS) {
        return "app:fleet.export.error.tooLong";
    }
    return undefined;
}

export function initialSelection(options: FleetExportOptions, remembered?: Selection): Selection {
    const selection: Selection = {};
    for (const type of options.types ?? []) {
        const attributes = [...(type.identity ?? []), ...(type.measurements ?? [])];
        const rememberedNames = remembered?.[type.type];
        const kept = rememberedNames
            ? attributes.filter((a) => rememberedNames.includes(a.name)).map((a) => a.name)
            : [];
        // A remembered choice whose every name is gone (renamed or no longer offered) means nothing.
        selection[type.type] = rememberedNames && (kept.length > 0 || rememberedNames.length === 0)
            ? kept
            : attributes.filter((a) => a.defaultSelected).map((a) => a.name);
    }
    return selection;
}

/**
 * The search's filter for an export: paging, sorting and grouping do not shape it. A reading:
 * token needs the primary register the current result reports, exactly as the search uses it.
 */
export function toExportQuery(tokens: Token[], columns: { name?: string; primary?: boolean }[] | undefined,
                              realm?: string): FleetQuery {
    const primaryRegister = singlePrimaryRegister(columns);
    const query = toFleetQuery(tokens, primaryRegister ? { primaryRegister } : {});
    if (realm) { query.realm = realm; }
    return query;
}

export function toExportRequest(query: FleetQuery, from: string, to: string, selection: Selection): FleetExportRequest {
    const { text, types, parentIds, recursive, attributes, seen, status, realm } = query;
    const filter: FleetQuery = { text, types, parentIds, recursive, attributes, seen, status, realm };
    for (const key of Object.keys(filter) as (keyof FleetQuery)[]) {
        if (filter[key] === undefined) {
            delete filter[key];
        }
    }
    const chosen: Record<string, string[]> = {};
    for (const [type, names] of Object.entries(selection)) {
        if (names.length > 0) {
            chosen[type] = names;
        }
    }
    return { query: filter, from, to, attributes: chosen };
}

export function fileNameFrom(contentDisposition: string | undefined, fallback: string): string {
    const match = contentDisposition?.match(/filename\*?=(?:"([^"]+)"|([^;\s]+))/i);
    return match?.[1] ?? match?.[2] ?? fallback;
}

/** File name when the response names none; the extension follows the content type. */
export function fallbackFileName(from: string, to: string, contentType: string | undefined): string {
    const extension = contentType?.toLowerCase().startsWith("application/zip") ? "zip" : "csv";
    return `fleet-${from}_${to}.${extension}`;
}

/** First..last day of maxTo's month, capped at maxTo. */
export function defaultRange(maxTo: string): { from: string; to: string } {
    return { from: `${maxTo.slice(0, 7)}-01`, to: maxTo };
}

/** A server message in the user's language: a translation key and its options. */
export type ServerProblem = { key: string; options?: Record<string, string> };

/**
 * The export's 400/403 messages the dialog translates. The wording comes from FleetExporter.plan
 * and FleetExportRange.parse; keep both sides in step. Anything else is shown as sent.
 */
const SERVER_PROBLEMS: { pattern: RegExp; key: string; names: string[] }[] = [
    { pattern: /^Select at least one attribute to export$/, key: "nothingSelected", names: [] },
    { pattern: /^No matched devices of type (.+)$/, key: "noDevices", names: ["type"] },
    { pattern: /^Attribute (.+?) cannot be exported for (.+)$/, key: "notExportable", names: ["attribute", "type"] },
    { pattern: /^Attribute (.+?) is not readable for (.+)$/, key: "notReadable", names: ["attribute", "type"] },
    { pattern: /^Export too large: narrow the search or the date range$/, key: "tooLarge", names: [] },
    { pattern: /^The first day must not be after the last day$/, key: "order", names: [] },
    { pattern: /^The last day must be yesterday or earlier$/, key: "future", names: [] },
    { pattern: /^The range must not exceed \d+ days$/, key: "tooLong", names: [] },
];

export function serverProblem(text: string): ServerProblem | undefined {
    for (const { pattern, key, names } of SERVER_PROBLEMS) {
        const match = text.match(pattern);
        if (match) {
            const options = Object.fromEntries(names.map((name, i) => [name, match[i + 1]]));
            return names.length ? { key: `app:fleet.export.error.${key}`, options } : { key: `app:fleet.export.error.${key}` };
        }
    }
    return undefined;
}
