import { describe, expect, it } from "vitest";
import { pageItems, parsePageSize } from "./fleet-pager";

describe("pageItems", () => {
    it("lists every page when there are few", () => {
        expect(pageItems(0, 1)).toEqual([0]);
        expect(pageItems(2, 5)).toEqual([0, 1, 2, 3, 4]);
    });

    it("keeps the first, last and current pages with neighbours and gaps between", () => {
        expect(pageItems(9, 20)).toEqual([0, "gap", 8, 9, 10, "gap", 19]);
    });

    it("shows a single left-out page instead of a gap", () => {
        expect(pageItems(3, 20)).toEqual([0, 1, 2, 3, 4, "gap", 19]);
    });

    it("handles the ends", () => {
        expect(pageItems(0, 20)).toEqual([0, 1, "gap", 19]);
        expect(pageItems(19, 20)).toEqual([0, "gap", 18, 19]);
    });

    it("has nothing for no pages", () => {
        expect(pageItems(0, 0)).toEqual([]);
    });
});

describe("parsePageSize", () => {
    it("accepts only offered sizes", () => {
        expect(parsePageSize("100")).toBe(100);
        expect(parsePageSize("7")).toBeUndefined();
        expect(parsePageSize("")).toBeUndefined();
        expect(parsePageSize(null)).toBeUndefined();
    });
});
