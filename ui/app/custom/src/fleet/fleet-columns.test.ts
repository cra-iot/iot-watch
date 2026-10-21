import { describe, expect, it } from "vitest";
import { cellValue, columnsFrom, EM_DASH, formatValue, offeredColumns } from "./fleet-columns";

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

describe("columnsFrom", () => {
    it("renders the identity columns plus whatever the server selected", () => {
        const columns = columnsFrom(result([column("volume", { primary: true, units: ["metre", "cubed"] })]), units);
        const keys = columns.map((c) => c.key);
        expect(keys).toContain("name");
        expect(keys).toContain("lastSeen");
        expect(keys).toContain("volume");
    });

    it("ignores a column the server offered but did not select", () => {
        const columns = columnsFrom(result([
            column("volume", { primary: true }),
            column("volume_standard", { selected: false }),
        ]), units);
        expect(columns.map((c) => c.key)).not.toContain("volume_standard");
    });

    it("works for a type the client has never heard of", () => {
        const columns = columnsFrom(result([
            column("temperature", { primary: true, units: ["celsius"], assetType: "ThermometerAsset" }),
        ]), units);
        expect(columns.map((c) => c.key)).toContain("temperature");
    });

    it("formats the server's unit keys for display", () => {
        const columns = columnsFrom(result([column("volume", { primary: true, units: ["metre", "cubed"] })]), units);
        expect(columns.find((c) => c.key === "volume")?.units).toBe("m³");
    });

    it("renders without a register column when the server resolved none", () => {
        const columns = columnsFrom(result([]), units);
        expect(columns.map((c) => c.key)).toContain("name");
        expect(columns.every((c) => c.key !== undefined)).toBe(true);
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
