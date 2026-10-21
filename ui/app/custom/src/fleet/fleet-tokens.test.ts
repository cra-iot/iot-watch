import { describe, expect, it } from "vitest";
import { formatSearch, fromUrlParams, parseSearch, toFleetQuery, toUrlParams } from "./fleet-tokens";

describe("parseSearch", () => {
    it("reads a bare word as free text", () => {
        expect(parseSearch("kotelna")).toEqual([{ kind: "text", value: "kotelna" }]);
    });

    it("reads a token with its value", () => {
        expect(parseSearch("type:GasMeterAsset")).toEqual([
            { kind: "type", value: "GasMeterAsset" },
        ]);
    });

    it("reads a numeric token with an operator", () => {
        expect(parseSearch("battery:<20")).toEqual([
            { kind: "battery", operator: "LESS_THAN", value: "20" },
        ]);
    });

    it("keeps a quoted value containing spaces in one token", () => {
        expect(parseSearch('alarm:"low battery"')).toEqual([
            { kind: "alarm", value: "low battery" },
        ]);
    });

    it("does not break on an unbalanced double quote", () => {
        expect(() => parseSearch('alarm:"low')).not.toThrow();
    });

    it("mixes tokens and free text in one input", () => {
        expect(parseSearch("type:GasMeterAsset kotelna")).toEqual([
            { kind: "type", value: "GasMeterAsset" },
            { kind: "text", value: "kotelna" },
        ]);
    });
});

describe("formatSearch", () => {
    it("round-trips every token kind", () => {
        const input = 'type:GasMeterAsset alarm:"low battery" battery:<20 seen:>24h kotelna';
        expect(formatSearch(parseSearch(input))).toBe(input);
    });
});

describe("toFleetQuery", () => {
    it("maps tokens onto the wire contract", () => {
        const query = toFleetQuery(parseSearch("type:GasMeterAsset battery:<20 kotelna"), {});
        expect(query.types).toEqual(["GasMeterAsset"]);
        expect(query.text).toBe("kotelna");
        expect(query.attributes).toEqual([
            { name: "battery_level", operator: "LESS_THAN", value: 20 },
        ]);
    });

    it("turns seen:>24h into an ISO-8601 olderThan duration", () => {
        expect(toFleetQuery(parseSearch("seen:>24h"), {}).seen).toEqual({ olderThan: "PT24H" });
    });

    it("turns an alarm token into a boolean attribute filter", () => {
        expect(toFleetQuery(parseSearch("alarm:leakage"), {}).attributes).toEqual([
            { name: "leakage", operator: "EQUALS", value: true },
        ]);
    });

    it("joins multiple free-text words into one text value", () => {
        expect(toFleetQuery(parseSearch("blok a"), {}).text).toBe("blok a");
    });
});

describe("url round-trip", () => {
    it("restores the same tokens the search produced", () => {
        const tokens = parseSearch("type:GasMeterAsset alarm:leakage kotelna");
        expect(fromUrlParams(toUrlParams(tokens, {})).tokens).toEqual(tokens);
    });

    it("tolerates a malformed q parameter without throwing", () => {
        expect(() => fromUrlParams(new URLSearchParams("q=%E0%A4%A"))).not.toThrow();
    });
});
