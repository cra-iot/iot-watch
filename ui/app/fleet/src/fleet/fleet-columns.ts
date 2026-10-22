import type { FleetQueryResult, FleetRow } from "model";

export const EM_DASH = "—";

/**
 * Unit keys (["metre","cubed"]) to display text. The results component passes
 * Util.resolveUnits from @openremote/core; this module does not import it, so it stays a pure
 * function module that vitest can load in Node without the browser-bound core package.
 */
export type UnitFormatter = (units?: string[]) => string;

export interface FleetColumn {
    /** A FleetRow property name for the identity columns, or an attribute name for a register. */
    key: string;
    /** Label to render. Register labels come from the server, already in the model's language. */
    label: string;
    /** i18n key, for the identity columns only; register labels are not translated here. */
    labelKey?: string;
    units?: string;
    numeric?: boolean;
    sortable?: boolean;
    /** True when the value lives in FleetRow.values rather than on the row itself. */
    attribute?: boolean;
    /** The key the Columns menu and the hide= parameter use for it; absent for always-shown columns. */
    hideKey?: HideKey;
}

/**
 * Columns the user can hide. "identity" is not a column of its own: it is the serial and devEUI
 * line under the meter name. "reading" is the primary register of each row's own type.
 */
export const DEVICE_DETAILS = ["type", "deliveryPoint", "identity", "location", "battery", "lastSeen", "alarms"] as const;
// "reading" lives in this list although it is a measurement, not a device detail: one hide= parameter covers both.
export const HIDE_KEYS = [...DEVICE_DETAILS, "reading"] as const;
export type HideKey = typeof HIDE_KEYS[number];

/** Hidden until the user asks for them. */
export const DEFAULT_HIDDEN: HideKey[] = ["type", "deliveryPoint", "location"];

const HIDE_KEY_SET = new Set<string>(HIDE_KEYS);

/** A comma list from the URL or browser storage; null means "not given", unknown keys are dropped. */
export function parseHidden(value: string | null | undefined): HideKey[] | undefined {
    if (value === null || value === undefined) {
        return undefined;
    }
    const keys = value.split(",").map((key) => key.trim()).filter((key) => HIDE_KEY_SET.has(key));
    return HIDE_KEYS.filter((key) => keys.includes(key));
}

/** The hide= value, or undefined when it equals the defaults and the URL can stay short. */
export function serializeHidden(hidden: readonly string[]): string | undefined {
    const normalized = parseHidden(hidden.join(",")) ?? [];
    const isDefault = normalized.length === DEFAULT_HIDDEN.length && DEFAULT_HIDDEN.every((key) => normalized.includes(key));
    return isDefault ? undefined : normalized.join(",");
}

/** The URL wins over what this browser stored; with neither, the defaults. */
export function resolveHidden(fromUrl: readonly string[] | undefined, stored: string | null | undefined): HideKey[] {
    return parseHidden(fromUrl?.join(",")) ?? parseHidden(stored) ?? [...DEFAULT_HIDDEN];
}

/** The meter name is always shown. A column's key is also its server sort field. */
const METER: FleetColumn = { key: "name", label: "Meter", labelKey: "fleet.column.meter", sortable: true };

const LEADING: FleetColumn[] = [
    { key: "type", label: "Type", labelKey: "fleet.column.type", sortable: true, hideKey: "type" },
    { key: "deliveryPointId", label: "Delivery point", labelKey: "fleet.column.deliveryPoint", hideKey: "deliveryPoint" },
    // FleetRow.parentName, never FleetRow.path: path holds asset ids.
    { key: "parentName", label: "Location", labelKey: "fleet.column.location", hideKey: "location" },
];

/** Each row's own primary register; the server sorts it as "reading". */
const READING: FleetColumn = {
    key: "reading", label: "Reading", labelKey: "fleet.column.reading", numeric: true, sortable: true, hideKey: "reading",
};

const TRAILING: FleetColumn[] = [
    { key: "lastSeen", label: "Last seen", labelKey: "fleet.column.lastSeen", sortable: true, hideKey: "lastSeen" },
    { key: "batteryLevel", label: "Battery", labelKey: "fleet.column.battery", numeric: true, hideKey: "battery" },
    { key: "alarms", label: "Alarms", labelKey: "fleet.column.alarms", hideKey: "alarms" },
];

type Descriptor = NonNullable<FleetQueryResult["columns"]>[number];

function toColumn(descriptor: Descriptor,
                  formatUnits: UnitFormatter): FleetColumn {
    return {
        key: descriptor.name!,
        label: descriptor.label ?? descriptor.name!,
        units: descriptor.units?.length ? formatUnits(descriptor.units) : undefined,
        numeric: descriptor.numeric,
        sortable: true,
        attribute: true,
    };
}

/** Deduplicated by name, because two asset types may offer a register under the same name. */
function uniqueByName(descriptors: Descriptor[]): Descriptor[] {
    const seen = new Set<string>();
    return descriptors.filter((descriptor) => {
        if (!descriptor.name || seen.has(descriptor.name)) {
            return false;
        }
        seen.add(descriptor.name);
        return true;
    });
}

/**
 * Registers the user selected on top of the primary ones. The server always selects each type's
 * primary register; those show in the Reading column, not as columns of their own.
 */
function extraRegisters(result: FleetQueryResult): Descriptor[] {
    return uniqueByName((result.columns ?? []).filter((descriptor) => descriptor.selected && !descriptor.primary));
}

/** Every column the result has, hidden or not: the meter, details, Reading, extra registers, details. */
export function allColumns(result: FleetQueryResult, formatUnits: UnitFormatter): FleetColumn[] {
    const registers = extraRegisters(result).map((descriptor) => toColumn(descriptor, formatUnits));
    return [METER, ...LEADING, READING, ...registers, ...TRAILING];
}

/** The columns to render: everything not hidden. "identity" hides no column, only the meter's second line. */
export function visibleColumns(result: FleetQueryResult, hidden: readonly string[], formatUnits: UnitFormatter): FleetColumn[] {
    return allColumns(result, formatUnits).filter((column) => !column.hideKey || !hidden.includes(column.hideKey));
}

/** Names of the user-selected registers, as the server's columns parameter takes them. */
export function selectedRegisters(result: FleetQueryResult): string[] {
    return extraRegisters(result).map((descriptor) => descriptor.name!);
}

/** Everything the result offers but has not selected: the register part of the Columns menu. */
export function offeredColumns(result: FleetQueryResult, formatUnits: UnitFormatter): FleetColumn[] {
    const selected = new Set(selectedRegisters(result));
    const unselected = (result.columns ?? []).filter((descriptor) => !descriptor.selected && !selected.has(descriptor.name));
    return uniqueByName(unselected).map((descriptor) => toColumn(descriptor, formatUnits));
}

/**
 * The Reading cell: the row's own primary register with that type's units. A descriptor of the
 * row's type is preferred; any descriptor of the same name is the fallback.
 */
export function readingValue(row: FleetRow, result: FleetQueryResult, formatUnits: UnitFormatter): string {
    const name = row.primaryName;
    if (!name || !row.values || !(name in row.values)) {
        return EM_DASH;
    }
    const named = (result.columns ?? []).filter((descriptor) => descriptor.name === name);
    const descriptor = named.find((d) => d.assetType === row.type) ?? named[0];
    const units = descriptor?.units?.length ? formatUnits(descriptor.units) : undefined;
    return formatValue(row.values[name], units);
}

/** The muted second line under the meter name: serial and devEUI, whichever exist. */
export function identityLine(row: FleetRow): string | undefined {
    const parts = [row.meterId, row.devEui]
        .filter((part) => part !== undefined && part !== null && String(part).trim() !== "")
        .map(String);
    return parts.length ? parts.join(" · ") : undefined;
}

/** Renders one register cell. The other columns are rendered by the results component itself. */
export function cellValue(row: FleetRow, column: FleetColumn): string {
    const values = row.values;
    if (!values || !(column.key in values)) {
        return EM_DASH;
    }
    return formatValue(values[column.key], column.units);
}

/**
 * Zero is a real reading; only undefined, null and NaN are missing. Printing "undefined" in a
 * meter table is the kind of thing that makes a customer distrust every other number on screen.
 */
export function formatValue(value: unknown, units: string | undefined): string {
    if (value === undefined || value === null) {
        return EM_DASH;
    }
    if (typeof value === "number" && Number.isNaN(value)) {
        return EM_DASH;
    }
    const text = String(value);
    return units ? `${text} ${units}` : text;
}
