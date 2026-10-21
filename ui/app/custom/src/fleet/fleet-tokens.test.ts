import { AssetModelUtil } from "@openremote/model";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { formatSearch, fromUrlParams, parseSearch, toFleetQuery, toUrlParams, tokenDefinitions } from "./fleet-tokens";

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

describe("explicit operators", () => {
    it("keeps an explicit EQUALS through format and re-parse", () => {
        expect(formatSearch(parseSearch("delivery:=X"))).toBe("delivery:=X");
        expect(toFleetQuery(parseSearch("delivery:=X"), {}).attributes).toEqual([
            { name: "delivery_point_id", operator: "EQUALS", value: "X" },
        ]);
    });

    it("leaves an operator-less token without a symbol", () => {
        expect(formatSearch(parseSearch("delivery:X"))).toBe("delivery:X");
    });
});

describe("toFleetQuery extras", () => {
    it("copies contract fields only and keeps primaryRegister off the wire", () => {
        const query = toFleetQuery([], { primaryRegister: "volume", limit: 10, text: "evil" }) as Record<string, unknown>;
        expect(query).toEqual({ limit: 10 });
        expect("primaryRegister" in query).toBe(false);
    });

    it("maps a reading token onto the primary register", () => {
        expect(toFleetQuery(parseSearch("reading:>5"), { primaryRegister: "volume" }).attributes).toEqual([
            { name: "volume", operator: "GREATER_THAN", value: 5 },
        ]);
    });

    it("drops a reading token without a primary register", () => {
        expect(toFleetQuery(parseSearch("reading:>5"), {}).attributes).toBeUndefined();
    });
});

describe("non-numeric values", () => {
    it("drops numeric filters with unusable values", () => {
        expect(toFleetQuery(parseSearch("battery:abc"), {}).attributes).toBeUndefined();
        expect(toFleetQuery(parseSearch("battery:"), {}).attributes).toBeUndefined();
        expect(toFleetQuery(parseSearch("battery:10..x"), {}).attributes).toBeUndefined();
        expect(toFleetQuery(parseSearch("reading:abc"), { primaryRegister: "volume" }).attributes).toBeUndefined();
    });

    it("keeps a valid range", () => {
        expect(toFleetQuery(parseSearch("battery:10..20"), {}).attributes).toEqual([
            { name: "battery_level", operator: "BETWEEN", value: 10, rangeValue: 20 },
        ]);
    });
});

describe("tokenDefinitions", () => {
    const original = AssetModelUtil._assetTypeInfos;
    beforeEach(() => {
        AssetModelUtil._assetTypeInfos = [
            {
                assetDescriptor: { name: "GasMeterAsset", descriptorType: "asset" },
                attributeDescriptors: [
                    { name: "leakage", type: "boolean", meta: { label: "Leakage" } },
                    { name: "tamper", type: "boolean" },
                    { name: "volume", type: "number" },
                ],
            },
            {
                assetDescriptor: { name: "WaterMeterAsset", descriptorType: "asset" },
                attributeDescriptors: [{ name: "leakage", type: "boolean", meta: { label: "Leakage" } }],
            },
            {
                assetDescriptor: { name: "MqttAgent", descriptorType: "agent" },
                attributeDescriptors: [{ name: "agentFlag", type: "boolean" }],
            },
        ] as any;
    });
    afterEach(() => { AssetModelUtil._assetTypeInfos = original; });

    it("builds type and alarm values from the asset model", () => {
        const defs = tokenDefinitions();
        expect(defs.find((d) => d.kind === "type")!.values!.map((v) => v.value))
            .toEqual(["GasMeterAsset", "WaterMeterAsset"]);
        expect(defs.find((d) => d.kind === "alarm")!.values).toEqual([
            { value: "leakage", label: "Leakage" },
            { value: "tamper", label: "tamper" },
        ]);
    });
});
