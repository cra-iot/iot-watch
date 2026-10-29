import type { FleetQueryResult, FleetRow } from "model";

type Facet = NonNullable<NonNullable<FleetQueryResult["facets"]>["types"]>[number];

/** Mirrors FleetResultAssembler.SILENT_AFTER: nothing heard for this long means "silent". */
export const SILENT_AFTER_MS = 24 * 60 * 60 * 1000;

/** The server also groups by PARENT; the page does not offer it. */
export type GroupBy = "NONE" | "TYPE" | "STATUS";

export const GROUP_OPTIONS: GroupBy[] = ["NONE", "TYPE", "STATUS"];

export interface RowGroup {
    key: string;
    rows: FleetRow[];
    /** True when earlier rows of this group are on a previous page. */
    continued: boolean;
    /** A collapsed group: its rows left the paging (FleetQuery.collapsed), only its header shows. */
    collapsed: boolean;
}

/** Row status keys in the order the UI lists them; the server's FleetResultAssembler.status values. */
export const STATUS_KEYS = ["alarm", "silent", "ok"] as const;

/** Nothing heard for SILENT_AFTER_MS, or never; true for an alarm row too, unlike rowStatus. */
export function isStale(row: FleetRow, now: number): boolean {
    return row.lastSeen == null || row.lastSeen <= now - SILENT_AFTER_MS;
}

export function rowStatus(row: FleetRow, now: number): "alarm" | "silent" | "ok" {
    if ((row.alarms ?? []).length) {
        return "alarm";
    }
    return isStale(row, now) ? "silent" : "ok";
}

/** Same key the server groups and sorts by (FleetResultAssembler.groupKey). */
export function groupKey(row: FleetRow, groupBy: string, now: number): string {
    switch (groupBy) {
        case "TYPE": return row.type ?? "";
        case "STATUS": return rowStatus(row, now);
        default: return "";
    }
}

function facetsFor(result: FleetQueryResult, groupBy: string): Facet[] {
    const facets = result.facets;
    switch (groupBy) {
        case "TYPE": return facets?.types ?? [];
        case "STATUS": return facets?.status ?? [];
        default: return [];
    }
}

/** Facet counts by group key; rows without a group key fill the gap to `total`. */
function groupCounts(result: FleetQueryResult, groupBy: string): Map<string, number> {
    const counts = new Map<string, number>();
    let sum = 0;
    for (const facet of facetsFor(result, groupBy)) {
        counts.set(facet.key ?? "", facet.count ?? 0);
        sum += facet.count ?? 0;
    }
    const missing = (result.total ?? 0) - sum;
    if (missing > 0 && !counts.has("")) {
        counts.set("", missing);
    }
    return counts;
}

/** Same order as the server's group sort (String.compareTo, UTF-16 code units). */
const byKey = (a: RowGroup, b: RowGroup) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0);

/**
 * Splits the page into groups. The server sorts by group key first, so a group's rows are
 * contiguous. The first group is "continued" when its first row on this page is not its first
 * row overall, which is known from the facet counts of the open groups sorting before it.
 *
 * Collapsed groups have no rows on any page, so each one that still matches gets its header on
 * every page, in group order, where the user can open it again.
 */
export function segmentGroups(result: FleetQueryResult, groupBy: string, now: number, collapsed: string[] = []): RowGroup[] {
    const rows = result.rows ?? [];
    if (groupBy === "NONE" || !groupBy) {
        return rows.length ? [{ key: "", rows, continued: false, collapsed: false }] : [];
    }
    const groups: RowGroup[] = [];
    for (const row of rows) {
        const key = groupKey(row, groupBy, now);
        const last = groups[groups.length - 1];
        if (last && last.key === key) {
            last.rows.push(row);
        } else {
            groups.push({ key, rows: [row], continued: false, collapsed: false });
        }
    }
    const counts = groupCounts(result, groupBy);
    const first = groups[0];
    const offset = result.offset ?? 0;
    if (first && offset > 0) {
        if (counts.has(first.key)) {
            let before = 0;
            counts.forEach((count, key) => { if (key < first.key && !collapsed.includes(key)) { before += count; } });
            first.continued = offset > before;
        } else {
            first.continued = true;
        }
    }
    // The page keeps the server's order; each header goes before the first page group sorting after it.
    collapsed
        .filter((key) => counts.has(key) && !groups.some((group) => group.key === key))
        .sort()
        .forEach((key) => {
            const header = { key, rows: [], continued: false, collapsed: true };
            const index = groups.findIndex((group) => byKey(group, header) > 0);
            groups.splice(index < 0 ? groups.length : index, 0, header);
        });
    return groups;
}

/** Device and problem counts (from facets, never from this page) and the facet label, when there is one. */
export function groupInfo(
    result: FleetQueryResult, groupBy: string, key: string,
): { label?: string; count?: number; problems?: number } {
    const facet = facetsFor(result, groupBy).find((f) => (f.key ?? "") === key);
    if (facet) {
        return { label: facet.label, count: facet.count, problems: facet.problems };
    }
    return { count: groupCounts(result, groupBy).get(key) };
}

export interface Age {
    unit: "minute" | "hour" | "day";
    value: number;
}

/** Relative age of an epoch-millis timestamp; undefined when absent so the caller renders an em dash. */
export function relativeAge(lastSeen: unknown, now: number): Age | undefined {
    if (typeof lastSeen !== "number" || Number.isNaN(lastSeen)) {
        return undefined;
    }
    const minutes = Math.max(0, Math.floor((now - lastSeen) / 60000));
    if (minutes < 60) { return { unit: "minute", value: minutes }; }
    const hours = Math.floor(minutes / 60);
    if (hours < 48) { return { unit: "hour", value: hours }; }
    return { unit: "day", value: Math.floor(hours / 24) };
}
