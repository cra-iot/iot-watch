import { describe, expect, it } from "vitest";
import { FleetExportReport, type FleetExportOptions, type FleetQuery } from "model";
import {
    defaultType, fallbackFileName, fileNameFrom, periodChoices, rangeProblem, serverProblem, toExportQuery, toExportRequest,
    type PeriodChoice, type PeriodKey,
} from "./fleet-export";
import type { Token } from "./fleet-tokens";

const LAST_DAY = "2026-11-11";
const LAST_MONTH = "2026-10";
const MONTHLY = FleetExportReport.MONTHLY;
const DAILY = FleetExportReport.DAILY;

function range(choices: PeriodChoice[], key: PeriodKey): string {
    const choice = choices.find((c) => c.key === key)!;
    return `${choice.from}..${choice.to}`;
}

describe("periodChoices", () => {
    it("lists the choices in order, custom last and empty", () => {
        const choices = periodChoices(MONTHLY, LAST_DAY, LAST_MONTH);
        expect(choices.map((c) => c.key)).toEqual(["lastMonth", "last3", "last12", "thisYear", "previousYear", "custom"]);
        expect(choices[5]).toEqual({ key: "custom", from: "", to: "", disabled: false });
    });

    it("uses first days of months for the monthly report", () => {
        const choices = periodChoices(MONTHLY, LAST_DAY, LAST_MONTH);
        expect(range(choices, "lastMonth")).toBe("2026-10-01..2026-10-01");
        expect(range(choices, "last3")).toBe("2026-08-01..2026-10-01");
        expect(range(choices, "last12")).toBe("2025-11-01..2026-10-01");
        expect(range(choices, "thisYear")).toBe("2026-01-01..2026-10-01");
        expect(range(choices, "previousYear")).toBe("2025-01-01..2025-12-01");
    });

    it("uses whole days for the daily report", () => {
        const choices = periodChoices(DAILY, LAST_DAY, LAST_MONTH);
        expect(range(choices, "lastMonth")).toBe("2026-10-01..2026-10-31");
        expect(range(choices, "last3")).toBe("2026-08-01..2026-10-31");
        expect(range(choices, "last12")).toBe("2025-11-01..2026-10-31");
        expect(range(choices, "thisYear")).toBe("2026-01-01..2026-11-11");
        expect(range(choices, "previousYear")).toBe("2025-01-01..2025-12-31");
    });

    it("disables this year in January, when nothing of it is complete", () => {
        expect(periodChoices(MONTHLY, "2027-01-14", "2026-12").find((c) => c.key === "thisYear")!.disabled).toBe(true);
        expect(periodChoices(DAILY, "2026-12-31", "2026-12").find((c) => c.key === "thisYear")!.disabled).toBe(true);
        expect(periodChoices(DAILY, LAST_DAY, LAST_MONTH).some((c) => c.disabled)).toBe(false);
    });
});

describe("rangeProblem", () => {
    const options: FleetExportOptions = { maxDays: 366, lastDay: LAST_DAY, lastMonth: LAST_MONTH, maxMonths: 24, types: [] };

    it("reports a missing date", () => {
        expect(rangeProblem(DAILY, "", "2026-10-31", options)).toBe("app:fleet.export.error.missing");
        expect(rangeProblem(MONTHLY, "2026-10-01", "", options)).toBe("app:fleet.export.error.missing");
    });

    it("reports reversed dates", () => {
        expect(rangeProblem(DAILY, "2026-10-31", "2026-10-01", options)).toBe("app:fleet.export.error.order");
    });

    it("reports a last period that is not complete yet", () => {
        expect(rangeProblem(DAILY, "2026-10-01", "2026-11-12", options)).toBe("app:fleet.export.error.future");
        expect(rangeProblem(MONTHLY, "2026-10-01", "2026-11-01", options)).toBe("app:fleet.export.error.future");
    });

    it("limits the daily range to maxDays inclusive", () => {
        expect(rangeProblem(DAILY, "2025-11-09", "2026-11-10", options)).toBe("app:fleet.export.error.tooLong");
        expect(rangeProblem(DAILY, "2025-11-11", "2026-11-11", options)).toBeUndefined();
    });

    it("limits the monthly range to maxMonths inclusive", () => {
        expect(rangeProblem(MONTHLY, "2024-10-01", "2026-10-01", options)).toBe("app:fleet.export.error.tooLong");
        expect(rangeProblem(MONTHLY, "2024-11-01", "2026-10-01", options)).toBeUndefined();
    });

    it("accepts a valid range", () => {
        expect(rangeProblem(DAILY, "2026-10-01", "2026-10-31", options)).toBeUndefined();
    });
});

describe("defaultType", () => {
    const options = (counts: Record<string, number>): FleetExportOptions => ({
        types: Object.entries(counts).map(([type, deviceCount]) => ({ type, deviceCount, groups: [] })),
    });

    it("picks the type with the most devices, ties by name", () => {
        expect(defaultType(options({ WaterMeterAsset: 3, GasMeterAsset: 5, ElectricMeterAsset: 5 }))).toBe("ElectricMeterAsset");
    });

    it("is undefined without types", () => {
        expect(defaultType({ types: [] })).toBeUndefined();
    });
});

describe("toExportRequest", () => {
    it("copies only the filter fields", () => {
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
        const request = toExportRequest(query, "WaterMeterAsset", MONTHLY, "2026-08-01", "2026-10-01");
        expect(request).toEqual({
            query: { text: ["a"], types: ["WaterMeterAsset"], recursive: true, status: ["ok"], realm: "r1" },
            assetType: "WaterMeterAsset",
            report: MONTHLY,
            from: "2026-08-01",
            to: "2026-10-01",
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
    it("is always a csv", () => {
        expect(fallbackFileName("2026-10-01", "2026-10-31")).toBe("fleet-2026-10-01_2026-10-31.csv");
    });
});

describe("toExportQuery", () => {
    const tokens: Token[] = [
        { kind: "type", value: "WaterMeterAsset" },
        { kind: "reading", operator: "GREATER_THAN", value: "1000" },
    ];

    it("keeps a reading filter with the result's primary register, as the search does", () => {
        const query = toExportQuery(tokens, [{ name: "currentReading", primary: true }, { name: "devEui" }], "acme");
        expect(query.attributes).toEqual([{ name: "currentReading", operator: "GREATER_THAN", value: 1000 }]);
        expect(query.realm).toBe("acme");
    });

    it("drops the reading filter, like the search, when no single primary register is known", () => {
        expect(toExportQuery(tokens, undefined).attributes ?? []).toEqual([]);
    });

    it("carries no paging, sorting or grouping", () => {
        const query = toExportQuery(tokens, undefined);
        for (const key of ["offset", "limit", "sort", "groupBy", "collapsed", "columns"]) {
            expect(query).not.toHaveProperty(key);
        }
    });
});

describe("serverProblem", () => {
    it("translates the export's known messages, with their names", () => {
        expect(serverProblem("Select a meter type")).toEqual({ key: "app:fleet.export.error.selectType" });
        expect(serverProblem("Export too large: narrow the search or the period"))
            .toEqual({ key: "app:fleet.export.error.tooLarge" });
        expect(serverProblem("No readable readings for WaterMeterAsset"))
            .toEqual({ key: "app:fleet.export.error.notReadable", options: { type: "WaterMeterAsset" } });
        expect(serverProblem("Meter type HeatMeterAsset has no readings to export"))
            .toEqual({ key: "app:fleet.export.error.notExportable", options: { type: "HeatMeterAsset" } });
        expect(serverProblem("No matched devices of type GasMeterAsset"))
            .toEqual({ key: "app:fleet.export.error.noDevices", options: { type: "GasMeterAsset" } });
        expect(serverProblem("The first period must not be after the last")).toEqual({ key: "app:fleet.export.error.order" });
        expect(serverProblem("The last month must be last month or earlier")).toEqual({ key: "app:fleet.export.error.future" });
        expect(serverProblem("The last day must be yesterday or earlier")).toEqual({ key: "app:fleet.export.error.future" });
        expect(serverProblem("The range must not exceed 366 days")).toEqual({ key: "app:fleet.export.error.tooLong" });
        expect(serverProblem("The range must not exceed 24 months")).toEqual({ key: "app:fleet.export.error.tooLong" });
    });

    it("leaves other messages to be shown as sent", () => {
        expect(serverProblem("Unknown status: sleeping")).toBeUndefined();
    });
});
