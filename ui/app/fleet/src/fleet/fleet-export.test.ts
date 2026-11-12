import { describe, expect, it } from "vitest";
import type { FleetExportOptions, FleetQuery } from "model";
import { defaultRange, fallbackFileName, fileNameFrom, initialSelection, rangeError, toExportRequest } from "./fleet-export";

const MAX = "2026-11-11";

describe("rangeError", () => {
    it("reports a missing date", () => {
        expect(rangeError("", "2026-10-31", MAX)).toBe("app:fleet.export.error.missing");
        expect(rangeError("2026-10-01", "", MAX)).toBe("app:fleet.export.error.missing");
    });

    it("reports reversed dates", () => {
        expect(rangeError("2026-10-31", "2026-10-01", MAX)).toBe("app:fleet.export.error.order");
    });

    it("reports a to date after maxTo", () => {
        expect(rangeError("2026-10-01", "2026-11-12", MAX)).toBe("app:fleet.export.error.future");
    });

    it("limits the range to 366 days inclusive", () => {
        expect(rangeError("2025-11-09", "2026-11-10", MAX)).toBe("app:fleet.export.error.tooLong");
        expect(rangeError("2025-11-11", "2026-11-11", MAX)).toBeUndefined();
    });

    it("accepts a valid range", () => {
        expect(rangeError("2026-10-01", "2026-10-31", MAX)).toBeUndefined();
    });
});

describe("initialSelection", () => {
    const options: FleetExportOptions = {
        maxTo: MAX,
        maxDays: 366,
        types: [
            {
                type: "WaterMeterAsset",
                identity: [
                    { name: "devEui", label: "DevEUI", defaultSelected: true },
                    { name: "serial", label: "Serial", defaultSelected: false },
                ],
                measurements: [{ name: "volume", label: "Volume", defaultSelected: true }],
            },
        ],
    };

    it("uses the defaults without a remembered selection", () => {
        expect(initialSelection(options)).toEqual({ WaterMeterAsset: ["devEui", "volume"] });
    });

    it("keeps remembered names and drops those no longer offered", () => {
        expect(initialSelection(options, { WaterMeterAsset: ["devEui", "gone"] })).toEqual({ WaterMeterAsset: ["devEui"] });
    });

    it("falls back to the defaults when every remembered name is gone", () => {
        expect(initialSelection(options, { WaterMeterAsset: ["gone", "alsoGone"] })).toEqual({ WaterMeterAsset: ["devEui", "volume"] });
    });

    it("keeps a remembered empty choice", () => {
        expect(initialSelection(options, { WaterMeterAsset: [] })).toEqual({ WaterMeterAsset: [] });
    });
});

describe("toExportRequest", () => {
    it("copies only the filter fields and drops empty types", () => {
        const query: FleetQuery = {
            text: ["a"],
            types: ["WaterMeterAsset"],
            recursive: true,
            status: ["ok"],
            realm: "r1",
            offset: 10,
            limit: 20,
            groupBy: { field: "x" } as any,
            sort: { field: "y" },
            columns: ["c"],
            collapsed: ["k"],
        };
        const request = toExportRequest(query, "2026-10-01", "2026-10-31", { WaterMeterAsset: ["devEui"], GasMeterAsset: [] });
        expect(request).toEqual({
            query: { text: ["a"], types: ["WaterMeterAsset"], recursive: true, status: ["ok"], realm: "r1" },
            from: "2026-10-01",
            to: "2026-10-31",
            attributes: { WaterMeterAsset: ["devEui"] },
        });
    });
});

describe("fileNameFrom", () => {
    it("reads the quoted filename", () => {
        const name = "WaterMeterAsset_2026-10-01_2026-10-31_20261112T0930Z.csv";
        expect(fileNameFrom(`attachment; filename="${name}"`, "x")).toBe(name);
    });

    it("falls back", () => {
        expect(fileNameFrom(undefined, "x")).toBe("x");
        expect(fileNameFrom("attachment", "x")).toBe("x");
    });
});

describe("fallbackFileName", () => {
    it("uses the extension of the content type", () => {
        expect(fallbackFileName("2026-10-01", "2026-10-31", "application/zip")).toBe("fleet-2026-10-01_2026-10-31.zip");
        expect(fallbackFileName("2026-10-01", "2026-10-31", "text/csv; charset=UTF-8")).toBe("fleet-2026-10-01_2026-10-31.csv");
        expect(fallbackFileName("2026-10-01", "2026-10-31", undefined)).toBe("fleet-2026-10-01_2026-10-31.csv");
    });
});

describe("defaultRange", () => {
    it("spans the month of maxTo up to maxTo", () => {
        expect(defaultRange("2026-11-11")).toEqual({ from: "2026-11-01", to: "2026-11-11" });
    });
});
