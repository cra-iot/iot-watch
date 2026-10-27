import { describe, expect, it } from "vitest";
import { GROUP_OPTIONS, groupInfo, relativeAge, rowStatus, segmentGroups } from "./fleet-groups";

const NOW = 1_000_000_000_000;
const row = (id: string, over: Record<string, unknown> = {}) =>
    ({ id, name: id, type: "GasMeterAsset", lastSeen: NOW - 1000, ...over });
const result = (rows: unknown[], over: Record<string, unknown> = {}) =>
    ({ total: 10, offset: 0, limit: 50, rows, facets: {}, columns: [], ...over }) as never;

describe("rowStatus", () => {
    it("is alarm, silent (never seen or old) or ok", () => {
        expect(rowStatus(row("a", { alarms: ["x"] }) as never, NOW)).toBe("alarm");
        expect(rowStatus(row("a", { lastSeen: undefined }) as never, NOW)).toBe("silent");
        expect(rowStatus(row("a", { lastSeen: NOW - 25 * 3600_000 }) as never, NOW)).toBe("silent");
        expect(rowStatus(row("a") as never, NOW)).toBe("ok");
    });
});

describe("segmentGroups", () => {
    const facets = { types: [{ key: "GasMeterAsset", label: "Gas meter", count: 4 }, { key: "WaterMeterAsset", label: "Water meter", count: 6 }] };

    it("returns one group without grouping", () => {
        const groups = segmentGroups(result([row("a"), row("b")]), "NONE", NOW);
        expect(groups).toHaveLength(1);
        expect(groups[0].rows).toHaveLength(2);
    });

    it("splits contiguous rows by group key", () => {
        const rows = [row("a"), row("b"), row("c", { type: "WaterMeterAsset" })];
        const groups = segmentGroups(result(rows, { facets }), "TYPE", NOW);
        expect(groups.map((g) => [g.key, g.rows.length, g.continued])).toEqual([["GasMeterAsset", 2, false], ["WaterMeterAsset", 1, false]]);
    });

    it("groups by status", () => {
        const rows = [row("a", { alarms: ["x"] }), row("b", { lastSeen: undefined }), row("c")];
        expect(segmentGroups(result(rows), "STATUS", NOW).map((g) => g.key)).toEqual(["alarm", "silent", "ok"]);
    });

    it("marks the first group continued when earlier rows are on the previous page", () => {
        const groups = segmentGroups(result([row("c")], { facets, offset: 3 }), "TYPE", NOW);
        expect(groups[0].continued).toBe(true);
    });

    it("does not mark continued when a group starts exactly at the page boundary", () => {
        const groups = segmentGroups(result([row("c", { type: "WaterMeterAsset" })], { facets, offset: 4 }), "TYPE", NOW);
        expect(groups[0].continued).toBe(false);
    });

    it("adds a header for each collapsed group, in group order, on every page", () => {
        const water = [row("x", { type: "WaterMeterAsset" })];
        const groups = segmentGroups(result(water, { facets, offset: 0 }), "TYPE", NOW, ["GasMeterAsset"]);
        expect(groups.map((g) => [g.key, g.rows.length, g.collapsed])).toEqual([["GasMeterAsset", 0, true], ["WaterMeterAsset", 1, false]]);
    });

    it("leaves collapsed rows out of the continued check", () => {
        // Gas (4) is collapsed, so water starts at offset 0 of the paged rows: offset 1 is inside it.
        const water = [row("x", { type: "WaterMeterAsset" })];
        expect(segmentGroups(result(water, { facets, offset: 1 }), "TYPE", NOW, ["GasMeterAsset"])[1].continued).toBe(true);
        expect(segmentGroups(result(water, { facets, offset: 1 }), "TYPE", NOW)[0].continued).toBe(false);
    });

    it("shows only headers when every group is collapsed, and none for a collapsed key that no longer matches", () => {
        const groups = segmentGroups(result([], { facets }), "TYPE", NOW, ["WaterMeterAsset", "GasMeterAsset", "HeatMeterAsset"]);
        expect(groups.map((g) => g.key)).toEqual(["GasMeterAsset", "WaterMeterAsset"]);
    });

    it("accounts for rows without a group key, which sort first", () => {
        const untyped = result([row("c")], { facets, offset: 4, total: 12 });
        // 2 untyped rows + 4 gas meters sort before water; offset 4 is inside the gas meters (start at 2).
        expect(segmentGroups(untyped, "TYPE", NOW)[0].continued).toBe(true);
    });
});

describe("groupInfo", () => {
    const facets = { types: [{ key: "GasMeterAsset", label: "Gas meter", count: 2, problems: 1 }, { key: "WaterMeterAsset", count: 3 }] };

    it("takes label, count and problems from facets", () => {
        expect(groupInfo(result([], { facets }), "TYPE", "GasMeterAsset")).toEqual({ label: "Gas meter", count: 2, problems: 1 });
    });

    it("leaves label and problems undefined when the wire omits them", () => {
        const info = groupInfo(result([], { facets }), "TYPE", "WaterMeterAsset");
        expect(info.problems).toBeUndefined();
        expect(info.label).toBeUndefined();
    });

    it("falls back to the gap to total for a key without a facet", () => {
        expect(groupInfo(result([], { facets }), "TYPE", "")).toEqual({ count: 5 });
    });
});

describe("GROUP_OPTIONS", () => {
    it("offers none, type and status; location grouping is not offered", () => {
        expect(GROUP_OPTIONS).toEqual(["NONE", "TYPE", "STATUS"]);
    });
});

describe("relativeAge", () => {
    it("is undefined when absent", () => {
        expect(relativeAge(undefined, NOW)).toBeUndefined();
        expect(relativeAge(null, NOW)).toBeUndefined();
        expect(relativeAge(NaN, NOW)).toBeUndefined();
    });
    it("picks the coarsest sensible unit and clamps the future", () => {
        expect(relativeAge(NOW - 5 * 60000, NOW)).toEqual({ unit: "minute", value: 5 });
        expect(relativeAge(NOW - 3 * 3600_000, NOW)).toEqual({ unit: "hour", value: 3 });
        expect(relativeAge(NOW - 72 * 3600_000, NOW)).toEqual({ unit: "day", value: 3 });
        expect(relativeAge(NOW + 60000, NOW)).toEqual({ unit: "minute", value: 0 });
    });
});
