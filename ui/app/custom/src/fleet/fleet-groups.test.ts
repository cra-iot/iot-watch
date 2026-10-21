import { describe, expect, it } from "vitest";
import { groupInfo, relativeAge, rowStatus, segmentGroups } from "./fleet-groups";

const NOW = 1_000_000_000_000;
const row = (id: string, over: Record<string, unknown> = {}) =>
    ({ id, name: id, type: "GasMeterAsset", parentId: "p1", lastSeen: NOW - 1000, ...over });
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
    const facets = { parents: [{ key: "p1", label: "North", count: 4 }, { key: "p2", label: "South", count: 6 }] };

    it("returns one group without grouping", () => {
        const groups = segmentGroups(result([row("a"), row("b")]), "NONE", NOW);
        expect(groups).toHaveLength(1);
        expect(groups[0].rows).toHaveLength(2);
    });

    it("splits contiguous rows by group key", () => {
        const rows = [row("a"), row("b"), row("c", { parentId: "p2" })];
        const groups = segmentGroups(result(rows, { facets }), "PARENT", NOW);
        expect(groups.map((g) => [g.key, g.rows.length, g.continued])).toEqual([["p1", 2, false], ["p2", 1, false]]);
    });

    it("marks the first group continued when earlier rows are on the previous page", () => {
        const groups = segmentGroups(result([row("c", { parentId: "p1" })], { facets, offset: 3 }), "PARENT", NOW);
        expect(groups[0].continued).toBe(true);
    });

    it("does not mark continued when a group starts exactly at the page boundary", () => {
        const groups = segmentGroups(result([row("c", { parentId: "p2" })], { facets, offset: 4 }), "PARENT", NOW);
        expect(groups[0].continued).toBe(false);
    });

    it("accounts for assets without a parent, which sort first", () => {
        const noParent = result([row("c", { parentId: "p1" })], { facets, offset: 4, total: 12 });
        // 2 unparented rows + 4 in p1 sort before p2; offset 4 is inside p1 (starts at 2).
        expect(segmentGroups(noParent, "PARENT", NOW)[0].continued).toBe(true);
    });
});

describe("groupInfo", () => {
    const facets = { parents: [{ key: "p1", label: "North", count: 2, problems: 1 }, { key: "p2", count: 3 }] };

    it("takes label, count and problems from facets", () => {
        expect(groupInfo(result([], { facets }), "PARENT", "p1")).toEqual({ label: "North", count: 2, problems: 1 });
    });

    it("leaves problems undefined when the wire omits it", () => {
        expect(groupInfo(result([], { facets }), "PARENT", "p2").problems).toBeUndefined();
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
