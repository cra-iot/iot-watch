import { describe, expect, it } from "vitest";
import {
    allColumns, cellValue, DEFAULT_HIDDEN, EM_DASH, formatValue, identityLine, offeredColumns, parseHidden, readingValue,
    resolveHidden, selectedRegisters, serializeHidden, visibleColumns,
} from "./fleet-columns";

const column = (name: string, over: Record<string, unknown> = {}) => ({
    name,
    label: name,
    units: undefined,
    numeric: true,
    primary: false,
    selected: true,
    assetType: "GasMeterAsset",
    ...over,
});

const result = (columns: unknown[], rows: unknown[] = []) =>
    ({ total: rows.length, offset: 0, limit: 50, rows, facets: {}, columns }) as never;

/** Stands in for Util.resolveUnits so the tests need no i18next. */
const units = (keys?: string[]) =>
    keys?.map((key) => ({ metre: "m", cubed: "³", celsius: "°C" } as Record<string, string>)[key] ?? key).join("") ?? "";

describe("visibleColumns", () => {
    const keys = (columns: { key: string }[]) => columns.map((c) => c.key);

    it("shows meter, reading, last seen, battery and alarms by default", () => {
        const columns = visibleColumns(result([column("volume", { primary: true })]), DEFAULT_HIDDEN, units);
        expect(keys(columns)).toEqual(["name", "reading", "lastSeen", "batteryLevel", "alarms"]);
    });

    it("shows every detail column when nothing is hidden, the meter always first", () => {
        const columns = visibleColumns(result([]), [], units);
        expect(keys(columns)).toEqual(["name", "type", "deliveryPointId", "parentName", "reading", "lastSeen", "batteryLevel", "alarms"]);
    });

    it("never hides the meter; identity hides no column, only the meter's second line", () => {
        const all = ["type", "deliveryPoint", "identity", "location", "battery", "lastSeen", "alarms", "reading"];
        expect(keys(visibleColumns(result([]), all, units))).toEqual(["name"]);
        expect(keys(visibleColumns(result([]), ["identity"], units))).toHaveLength(8);
    });

    it("renders a primary register in Reading, not as a column of its own", () => {
        const columns = visibleColumns(result([
            column("volume", { primary: true }),
            column("energy", { primary: true, assetType: "HeatMeterAsset" }),
        ]), DEFAULT_HIDDEN, units);
        expect(keys(columns)).not.toContain("volume");
        expect(keys(columns)).not.toContain("energy");
    });

    it("renders user-selected registers after Reading, once per name, with their units", () => {
        const columns = visibleColumns(result([
            column("volume", { primary: true }),
            column("gas_temperature", { units: ["celsius"] }),
            column("gas_temperature", { units: ["celsius"], assetType: "OtherMeterAsset" }),
            column("volume_standard", { selected: false }),
        ]), DEFAULT_HIDDEN, units);
        expect(keys(columns)).toEqual(["name", "reading", "gas_temperature", "lastSeen", "batteryLevel", "alarms"]);
        expect(columns.find((c) => c.key === "gas_temperature")?.units).toBe("°C");
    });

    it("hides Reading on request but keeps selected registers", () => {
        const columns = visibleColumns(result([column("gas_temperature")]), ["reading"], units);
        expect(keys(columns)).toContain("gas_temperature");
        expect(keys(columns)).not.toContain("reading");
    });

    it("works for a type the client has never heard of", () => {
        const columns = visibleColumns(result([
            column("temperature", { primary: true, units: ["celsius"], assetType: "ThermometerAsset" }),
            column("humidity", { assetType: "ThermometerAsset" }),
        ]), DEFAULT_HIDDEN, units);
        expect(keys(columns)).toContain("humidity");
    });
});

describe("allColumns", () => {
    it("makes Reading sortable under the server's reading field", () => {
        const reading = allColumns(result([]), units).find((c) => c.key === "reading");
        expect(reading?.sortable).toBe(true);
        expect(reading?.numeric).toBe(true);
    });
});

describe("selectedRegisters", () => {
    it("lists the user-selected registers, never the primary ones the server always selects", () => {
        expect(selectedRegisters(result([
            column("volume", { primary: true }),
            column("gas_temperature"),
            column("pressure", { selected: false }),
        ]))).toEqual(["gas_temperature"]);
    });
});

describe("offeredColumns", () => {
    it("lists the unselected columns so the picker has something to offer", () => {
        const columns = offeredColumns(result([
            column("volume", { primary: true }),
            column("gas_temperature", { selected: false, label: "Teplota plynu" }),
        ]), units);
        expect(columns.map((c) => c.key)).toEqual(["gas_temperature"]);
    });

    it("offers one column when two asset types offer the same unselected register", () => {
        const columns = offeredColumns(result([
            column("gas_temperature", { selected: false }),
            column("gas_temperature", { selected: false, assetType: "OtherMeterAsset" }),
        ]), units);
        expect(columns.map((c) => c.key)).toEqual(["gas_temperature"]);
    });

    it("does not offer a register another type already has selected", () => {
        const columns = offeredColumns(result([
            column("gas_temperature"),
            column("gas_temperature", { selected: false, assetType: "OtherMeterAsset" }),
        ]), units);
        expect(columns).toEqual([]);
    });
});

describe("hidden columns", () => {
    it("parses a comma list, dropping unknown keys and keeping a stable order", () => {
        expect(parseHidden("battery, type,bogus,type")).toEqual(["type", "battery"]);
    });

    it("tells an absent value from an empty one", () => {
        expect(parseHidden(null)).toBeUndefined();
        expect(parseHidden(undefined)).toBeUndefined();
        expect(parseHidden("")).toEqual([]);
    });

    it("serializes nothing for the defaults, in any order", () => {
        expect(serializeHidden(["location", "type", "deliveryPoint"])).toBeUndefined();
    });

    it("serializes an empty list when everything is shown, and a non-default list as is", () => {
        expect(serializeHidden([])).toBe("");
        expect(serializeHidden(["reading", "type"])).toBe("type,reading");
    });

    it("lets the URL win over browser storage, and falls back to the defaults", () => {
        expect(resolveHidden(["battery"], "type")).toEqual(["battery"]);
        expect(resolveHidden([], "type")).toEqual([]);
        expect(resolveHidden(undefined, "type,alarms")).toEqual(["type", "alarms"]);
        expect(resolveHidden(undefined, null)).toEqual(DEFAULT_HIDDEN);
    });
});

describe("readingValue", () => {
    const columns = [
        column("volume", { primary: true, units: ["metre", "cubed"], assetType: "WaterMeterAsset" }),
        column("volume", { primary: true, units: ["litre"], assetType: "GasMeterAsset" }),
        column("energy", { primary: true, units: ["kilo", "watt", "hour"], assetType: "HeatMeterAsset" }),
    ];

    it("renders the row's primary register with the units of its own type", () => {
        const row = { type: "GasMeterAsset", primaryName: "volume", values: { volume: 12 } } as never;
        expect(readingValue(row, result(columns), units)).toBe("12 litre");
        const water = { type: "WaterMeterAsset", primaryName: "volume", values: { volume: 201 } } as never;
        expect(readingValue(water, result(columns), units)).toBe("201 m³");
    });

    it("falls back to any descriptor of that name when the row's type has none", () => {
        const row = { type: "UnknownAsset", primaryName: "energy", values: { energy: 7 } } as never;
        expect(readingValue(row, result(columns), units)).toBe("7 kilowatthour");
    });

    it("renders an em dash without a primary register, value or value map", () => {
        expect(readingValue({ values: { volume: 1 } } as never, result(columns), units)).toBe(EM_DASH);
        expect(readingValue({ primaryName: "volume", values: {} } as never, result(columns), units)).toBe(EM_DASH);
        expect(readingValue({ primaryName: "volume", values: { volume: null } } as never, result(columns), units)).toBe(EM_DASH);
        expect(readingValue({ primaryName: "volume" } as never, result(columns), units)).toBe(EM_DASH);
    });

    it("treats zero as a reading", () => {
        const row = { type: "WaterMeterAsset", primaryName: "volume", values: { volume: 0 } } as never;
        expect(readingValue(row, result(columns), units)).toBe("0 m³");
    });
});

describe("identityLine", () => {
    it("joins serial and devEUI, omitting the missing part", () => {
        expect(identityLine({ meterId: "S-1", devEui: "00AA" } as never)).toBe("S-1 · 00AA");
        expect(identityLine({ devEui: "00AA" } as never)).toBe("00AA");
        expect(identityLine({ meterId: "S-1", devEui: "" } as never)).toBe("S-1");
    });

    it("is undefined when both are missing", () => {
        expect(identityLine({} as never)).toBeUndefined();
        expect(identityLine({ meterId: null, devEui: " " } as never)).toBeUndefined();
    });
});

describe("cellValue", () => {
    it("reads the value out of the row's value map and appends the server's units", () => {
        const row = { values: { volume: 8214 } } as never;
        expect(cellValue(row, { key: "volume", label: "Objem", units: "m³", numeric: true })).toBe("8214 m³");
    });

    it("renders an em dash when the asset carries the attribute but has no value", () => {
        const row = { values: { volume: null } } as never;
        expect(cellValue(row, { key: "volume", label: "Objem", units: "m³", numeric: true })).toBe(EM_DASH);
    });

    it("renders an em dash when the asset does not carry the attribute at all", () => {
        const row = { values: {} } as never;
        expect(cellValue(row, { key: "volume", label: "Objem", units: "m³", numeric: true })).toBe(EM_DASH);
    });

    it("does not fall over when the row has no value map", () => {
        expect(cellValue({} as never, { key: "volume", label: "Objem", numeric: true })).toBe(EM_DASH);
    });
});

describe("formatValue", () => {
    it("renders an em dash rather than undefined, null or NaN", () => {
        expect(formatValue(undefined, "m³")).toBe(EM_DASH);
        expect(formatValue(null, "m³")).toBe(EM_DASH);
        expect(formatValue(Number.NaN, "m³")).toBe(EM_DASH);
    });

    it("treats zero as a reading, not as missing", () => {
        expect(formatValue(0, "m³")).toBe("0 m³");
    });

    it("appends the units the server resolved", () => {
        expect(formatValue(142.318, "m³")).toBe("142.318 m³");
    });

    it("renders a bare value when the column has no units", () => {
        expect(formatValue(42, undefined)).toBe("42");
    });
});
