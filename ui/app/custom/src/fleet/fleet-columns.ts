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
}

/** Columns every result has, whatever the types in it. */
const LEADING: FleetColumn[] = [
    { key: "name", label: "Name", labelKey: "fleet.column.name", sortable: true },
    { key: "type", label: "Type", labelKey: "fleet.column.type", sortable: true },
    { key: "deliveryPointId", label: "Delivery point", labelKey: "fleet.column.deliveryPoint" },
    { key: "meterId", label: "Serial", labelKey: "fleet.column.serial" },
    // FleetRow.parentName, never FleetRow.path: path holds asset ids.
    { key: "parentName", label: "Location", labelKey: "fleet.column.location" },
];

const TRAILING: FleetColumn[] = [
    { key: "lastSeen", label: "Last seen", labelKey: "fleet.column.lastSeen", sortable: true },
    { key: "batteryLevel", label: "Battery", labelKey: "fleet.column.battery", numeric: true },
    { key: "alarms", label: "Alarms", labelKey: "fleet.column.alarms" },
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

/**
 * Named descriptors with the given selection state, deduplicated by name, because two asset types
 * may offer a register under the same name and the table needs one column for it, not two.
 */
function uniqueByName(result: FleetQueryResult, selected: boolean): Descriptor[] {
    const seen = new Set<string>();
    return (result.columns ?? []).filter((descriptor) => {
        if (!descriptor.name || !!descriptor.selected !== selected || seen.has(descriptor.name)) {
            return false;
        }
        seen.add(descriptor.name);
        return true;
    });
}

/** The columns to render: the identity columns, then whichever registers the server selected. */
export function columnsFrom(result: FleetQueryResult, formatUnits: UnitFormatter): FleetColumn[] {
    const registers = uniqueByName(result, true).map((descriptor) => toColumn(descriptor, formatUnits));
    return [...LEADING, ...registers, ...TRAILING];
}

/** Everything the result offers but has not selected: the contents of the column picker. */
export function offeredColumns(result: FleetQueryResult, formatUnits: UnitFormatter): FleetColumn[] {
    return uniqueByName(result, false).map((descriptor) => toColumn(descriptor, formatUnits));
}

/** Renders one register cell. Identity columns are rendered by the results component itself. */
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
