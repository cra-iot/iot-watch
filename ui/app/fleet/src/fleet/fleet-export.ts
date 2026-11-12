import type { FleetExportOptions, FleetExportRequest, FleetQuery } from "model";

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
        selection[type.type] = rememberedNames
            ? attributes.filter((a) => rememberedNames.includes(a.name)).map((a) => a.name)
            : attributes.filter((a) => a.defaultSelected).map((a) => a.name);
    }
    return selection;
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

/** First..last day of maxTo's month, capped at maxTo. */
export function defaultRange(maxTo: string): { from: string; to: string } {
    return { from: `${maxTo.slice(0, 7)}-01`, to: maxTo };
}
