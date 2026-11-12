import { FleetExportReport, type FleetExportOptions, type FleetExportRequest, type FleetQuery } from "model";
import { singlePrimaryRegister, toFleetQuery, type Token } from "./fleet-tokens";

export const CHOICE_KEY = "fleet.export.choice";

/** What the dialog remembers between visits. */
export interface RememberedChoice {
    assetType?: string;
    report?: FleetExportReport;
    period?: PeriodKey;
}

export type PeriodKey = "lastMonth" | "last3" | "last12" | "thisYear" | "previousYear" | "custom";

/** A period to pick: from/to as yyyy-MM-dd, first days of months for the monthly report. */
export interface PeriodChoice {
    key: PeriodKey;
    from: string;
    to: string;
    disabled: boolean;
}

const DEFAULT_MAX_DAYS = 366;
const DEFAULT_MAX_MONTHS = 24;
const DAY_MS = 24 * 60 * 60 * 1000;

const pad = (n: number) => String(n).padStart(2, "0");

/** Parses a plain YYYY-MM-DD string as UTC midnight. */
function utcDay(date: string): number {
    const [y, m, d] = date.split("-").map(Number);
    return Date.UTC(y, m - 1, d);
}

/** Month number since year 0 of a YYYY-MM or YYYY-MM-DD string. */
function monthIndex(date: string): number {
    const [y, m] = date.split("-").map(Number);
    return y * 12 + (m - 1);
}

/** First day (yyyy-MM-dd) of the month with this index. */
function firstOfMonth(index: number): string {
    return `${Math.floor(index / 12)}-${pad((index % 12) + 1)}-01`;
}

/** Last day (yyyy-MM-dd) of the month with this index. */
function lastOfMonth(index: number): string {
    const end = new Date(Date.UTC(Math.floor(index / 12), (index % 12) + 1, 0));
    return `${end.getUTCFullYear()}-${pad(end.getUTCMonth() + 1)}-${pad(end.getUTCDate())}`;
}

/**
 * The period choices for a report, in PeriodKey order. lastDay and lastMonth come from the options
 * response: the last complete day and month. This year is disabled while none of it is complete.
 */
export function periodChoices(report: FleetExportReport, lastDay: string, lastMonth: string): PeriodChoice[] {
    const monthly = report === FleetExportReport.MONTHLY;
    const last = monthIndex(lastMonth);
    const lastTo = monthly ? firstOfMonth(last) : lastDay;
    // Today is the day after lastDay, so on 1 January this year is already the new one.
    const year = new Date(utcDay(lastDay) + DAY_MS).getUTCFullYear();
    const choice = (key: PeriodKey, fromIndex: number, toIndex: number, to?: string): PeriodChoice => {
        const from = firstOfMonth(fromIndex);
        const end = to ?? (monthly ? firstOfMonth(toIndex) : lastOfMonth(toIndex));
        return { key, from, to: end, disabled: from > end || end > lastTo };
    };
    return [
        choice("lastMonth", last, last),
        choice("last3", last - 2, last),
        choice("last12", last - 11, last),
        choice("thisYear", year * 12, last, lastTo),
        choice("previousYear", (year - 1) * 12, year * 12 - 1),
        { key: "custom", from: "", to: "", disabled: false },
    ];
}

/** Translation key of the first problem, or undefined. The limits come from the options response. */
export function rangeProblem(report: FleetExportReport, from: string, to: string, options: FleetExportOptions): string | undefined {
    if (!from || !to) {
        return "app:fleet.export.error.missing";
    }
    if (from > to) {
        return "app:fleet.export.error.order";
    }
    if (report === FleetExportReport.MONTHLY) {
        if (monthIndex(to) > monthIndex(String(options.lastMonth))) {
            return "app:fleet.export.error.future";
        }
        if (monthIndex(to) - monthIndex(from) + 1 > (options.maxMonths ?? DEFAULT_MAX_MONTHS)) {
            return "app:fleet.export.error.tooLong";
        }
        return undefined;
    }
    if (to > String(options.lastDay)) {
        return "app:fleet.export.error.future";
    }
    if ((utcDay(to) - utcDay(from)) / DAY_MS + 1 > (options.maxDays ?? DEFAULT_MAX_DAYS)) {
        return "app:fleet.export.error.tooLong";
    }
    return undefined;
}

/** The meter type with the most devices, ties by name. */
export function defaultType(options: FleetExportOptions): string | undefined {
    const types = [...(options.types ?? [])];
    types.sort((a, b) => (b.deviceCount ?? 0) - (a.deviceCount ?? 0) || String(a.type).localeCompare(String(b.type)));
    return types[0]?.type;
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

export function toExportRequest(query: FleetQuery, assetType: string, report: FleetExportReport,
                                from: string, to: string): FleetExportRequest {
    const { text, types, parentIds, recursive, attributes, seen, status, realm } = query;
    const filter: FleetQuery = { text, types, parentIds, recursive, attributes, seen, status, realm };
    for (const key of Object.keys(filter) as (keyof FleetQuery)[]) {
        if (filter[key] === undefined) {
            delete filter[key];
        }
    }
    return { query: filter, assetType, report, from, to };
}

export function fileNameFrom(contentDisposition: string | undefined, fallback: string): string {
    const match = contentDisposition?.match(/filename\*?=(?:"([^"]+)"|([^;\s]+))/i);
    return match?.[1] ?? match?.[2] ?? fallback;
}

/** File name when the response names none. */
export function fallbackFileName(from: string, to: string): string {
    return `fleet-${from}_${to}.csv`;
}

/** A server message in the user's language: a translation key and its options. */
export type ServerProblem = { key: string; options?: Record<string, string> };

/**
 * The export's 400/403 messages the dialog translates. The wording comes from FleetExporter.plan
 * and FleetExportPeriod.parse; keep both sides in step. Anything else is shown as sent.
 */
const SERVER_PROBLEMS: { pattern: RegExp; key: string; names: string[] }[] = [
    { pattern: /^Select a meter type$/, key: "selectType", names: [] },
    { pattern: /^No matched devices of type (.+)$/, key: "noDevices", names: ["type"] },
    { pattern: /^Meter type (.+) has no readings to export$/, key: "notExportable", names: ["type"] },
    { pattern: /^No readable readings for (.+)$/, key: "notReadable", names: ["type"] },
    { pattern: /^Export too large: narrow the search or the period$/, key: "tooLarge", names: [] },
    { pattern: /^The first period must not be after the last$/, key: "order", names: [] },
    { pattern: /^The last (?:day|month) must be .*$/, key: "future", names: [] },
    { pattern: /^The range must not exceed .*$/, key: "tooLong", names: [] },
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
