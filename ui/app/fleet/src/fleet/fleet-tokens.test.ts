import { AssetModelUtil } from "@openremote/model";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
    formatSearch, fromUrlParams, mergeSiteOptions, parseSearch, readingFollowUp, siteLabel, singlePrimaryRegister,
    toFleetQuery, toUrlParams, tokenDefinitions, validateToken,
} from "./fleet-tokens";

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

    it("restores group, sort and columns", () => {
        const view = { groupBy: "TYPE", sort: { field: "lastSeen", descending: true }, columns: ["name", "battery"] };
        const restored = fromUrlParams(toUrlParams([], {}, view));
        expect(restored).toEqual({ tokens: [], ...view });
    });

    it("ignores invalid group, sort and columns values", () => {
        expect(fromUrlParams(new URLSearchParams("group=BOGUS&sort=-&columns=,,"))).toEqual({ tokens: [] });
    });

    it("falls back to no grouping for a link grouped by location", () => {
        expect(fromUrlParams(new URLSearchParams("group=PARENT")).groupBy).toBeUndefined();
    });

    it("carries hidden columns as hide=, omitting the defaults", () => {
        expect(toUrlParams([], {}, { hidden: ["type", "deliveryPoint", "location"] }).has("hide")).toBe(false);
        const params = toUrlParams([], {}, { hidden: ["battery"] });
        expect(params.get("hide")).toBe("battery");
        expect(fromUrlParams(params).hidden).toEqual(["battery"]);
    });

    it("keeps an empty hide= as \"show every column\" and leaves an absent one to storage", () => {
        const params = toUrlParams([], {}, { hidden: [] });
        expect(params.get("hide")).toBe("");
        expect(fromUrlParams(params).hidden).toEqual([]);
        expect(fromUrlParams(new URLSearchParams("q=x")).hidden).toBeUndefined();
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

describe("validateToken", () => {
    const definitions = [
        { kind: "type", labelKey: "k", values: [{ value: "GasMeterAsset", label: "Gas meter" }] },
        { kind: "alarm", labelKey: "k", values: [{ value: "leak", label: "Leak" }] },
        { kind: "seen", labelKey: "k" },
        { kind: "serial", labelKey: "k" },
        { kind: "deveui", labelKey: "k" },
        { kind: "delivery", labelKey: "k" },
        { kind: "site", labelKey: "k" },
        { kind: "battery", labelKey: "k", numeric: true },
        { kind: "reading", labelKey: "k", numeric: true },
        { kind: "attr", labelKey: "k" },
    ] as any[];
    const names = ["meter_id", "battery_level"];
    const check = (token: any) => validateToken(token, definitions, names);

    it("accepts a valid token of every kind", () => {
        const valid = [
            { kind: "text", value: "kotelna" },
            { kind: "text", value: "10:30" },
            { kind: "type", value: "GasMeterAsset" },
            { kind: "alarm", value: "leak" },
            { kind: "seen", operator: "GREATER_THAN", value: "24h" },
            { kind: "serial", value: "123" },
            { kind: "deveui", value: "abc" },
            { kind: "delivery", value: "42" },
            { kind: "site", value: "someId" },
            { kind: "battery", operator: "LESS_THAN", value: "20" },
            { kind: "reading", operator: "BETWEEN", value: "1", rangeValue: "5" },
            { kind: "attr", value: "meter_id=7" },
        ];
        valid.forEach((token) => expect(check(token)).toEqual({ ok: true }));
    });

    const REFUSED = [
        "delivery:>42", "delivery:>=42", "delivery:<42", "delivery:<=42", "delivery:40..42",
        "serial:>123", "serial:1..9", "deveui:<abc", "deveui:a..f",
        "site:>someId", "site:a..b", "type:>GasMeterAsset", "type:A..B",
        "alarm:>leak", "alarm:a..b",
        "attr:>meter_id=7", "attr:>=meter_id=7", "attr:<meter_id=7", "attr:<=meter_id=7", "attr:meter_id=1..9",
    ];
    const STILL_VALID = [
        "delivery:42", "delivery:=42", "serial:=123", "deveui:abc", "site:someId", "site:=someId",
        "type:GasMeterAsset", "type:=GasMeterAsset", "alarm:leak", "alarm:=leak",
        "attr:meter_id=7", "attr:=meter_id=7",
        "battery:>20", "battery:<=20", "battery:10..20", "battery:=5", "reading:>5", "reading:1..5",
        "seen:>24h", "seen:<7d",
    ];

    it("refuses comparisons and ranges on text kinds and attr:", () => {
        REFUSED.forEach((input) => {
            const [token] = parseSearch(input);
            expect({ input, result: check(token) })
                .toEqual({ input, result: { ok: false, reason: "operatorNotAllowed", suggestions: [] } });
        });
    });

    it("keeps = and plain values on text kinds, and comparisons on numeric kinds, valid", () => {
        STILL_VALID.forEach((input) => {
            const [token] = parseSearch(input);
            expect({ input, result: check(token) }).toEqual({ input, result: { ok: true } });
        });
    });

    /** Mirror of FleetResourceImpl.validateOperators: what the server answers with a 400. */
    function serverRejects(filter: { operator?: string; value?: unknown; rangeValue?: unknown }): boolean {
        const op = filter.operator ?? "EQUALS";
        const numeric = typeof filter.value === "number";
        if ((op === "CONTAINS" || op === "STARTS_WITH") && (numeric || typeof filter.value === "boolean")) {
            return true;
        }
        if (["LESS_THAN", "LESS_EQUALS", "GREATER_THAN", "GREATER_EQUALS", "BETWEEN"].includes(op)) {
            return !numeric || (op === "BETWEEN" && typeof filter.rangeValue !== "number");
        }
        return false;
    }

    it("no token that passes validation produces a filter the server rejects", () => {
        const operators = ["", "=", ">", ">=", "<", "<="];
        const values = ["7", "abc", "1..9", "x..", "..3", "1..x"];
        const kinds = ["delivery", "serial", "deveui", "battery", "reading", "alarm", "type", "site", "seen"];
        const inputs = kinds.flatMap((kind) => operators.flatMap((op) => values.map((v) => `${kind}:${op}${v}`)))
            .concat(operators.flatMap((op) => values.map((v) => `attr:${op}meter_id=${v}`)));
        inputs.forEach((input) => {
            const tokens = parseSearch(input);
            if (!tokens.every((token) => check(token).ok)) { return; }
            const attributes = toFleetQuery(tokens, { primaryRegister: "volume" }).attributes ?? [];
            attributes.forEach((filter) => expect({ input, rejected: serverRejects(filter as any) })
                .toEqual({ input, rejected: false }));
        });
    });

    it("drops a comparison on a text kind restored from a URL, which the bar never validated", () => {
        expect(toFleetQuery(parseSearch("delivery:>42 serial:1..9 attr:>meter_id=7"), {}).attributes).toBeUndefined();
        expect(toFleetQuery(parseSearch("delivery:=42"), {}).attributes)
            .toEqual([{ name: "delivery_point_id", operator: "EQUALS", value: "42" }]);
    });

    it("rejects an empty value for a non-text kind", () => {
        ["alarm", "serial", "attr", "battery"].forEach((kind) => {
            const result = check({ kind, value: "" });
            expect(result.ok).toBe(false);
            expect((result as any).reason).toBe("emptyValue");
        });
    });

    it("rejects a value outside the listed values and suggests the close ones", () => {
        const result = check({ kind: "alarm", value: "lea" }) as any;
        expect(result.ok).toBe(false);
        expect(result.reason).toBe("unknownValue");
        expect(result.suggestions).toEqual([{ label: "Leak", input: "alarm:leak", commit: true }]);
        expect((check({ kind: "type", value: "Nope" }) as any).reason).toBe("unknownValue");
    });

    it("rejects an unknown attribute name and suggests known ones", () => {
        const result = check({ kind: "attr", value: "meter_idd=1" }) as any;
        expect(result.reason).toBe("unknownAttribute");
        expect(result.suggestions.map((s: any) => s.label)).toContain("meter_id");
    });

    it("rejects a mistyped kind prefix instead of treating it as free text", () => {
        const result = check({ kind: "text", value: "alarmm:x" }) as any;
        expect(result.ok).toBe(false);
        expect(result.reason).toBe("unknownKind");
        expect(result.suggestions.map((s: any) => s.input)).toContain("alarm:");
        expect((check({ kind: "text", value: "serail:1" }) as any).reason).toBe("unknownKind");
    });

    it("rejects a non-numeric number and a malformed duration", () => {
        expect((check({ kind: "battery", value: "abc" }) as any).reason).toBe("notNumeric");
        expect((check({ kind: "reading", operator: "BETWEEN", value: "1" }) as any).reason).toBe("notNumeric");
        expect((check({ kind: "seen", value: "soon" }) as any).reason).toBe("invalidDuration");
    });
});

describe("site tokens", () => {
    const sites = [
        { value: "1a2b3c4d5e6f7a8b9c0d1e", label: "Block A" },
        { value: "2b3c4d5e6f7a8b9c0d1e2f", label: "Boiler room" },
    ];

    it("offers the known locations by name while the token keeps the id", () => {
        const site = tokenDefinitions(sites).find((d) => d.kind === "site")!;
        expect(site.values).toEqual(sites);
        expect(toFleetQuery([{ kind: "site", value: sites[0].value }], {}).parentIds).toEqual([sites[0].value]);
    });

    it("accepts a known site id", () => {
        expect(validateToken({ kind: "site", value: sites[1].value }, tokenDefinitions(sites), [])).toEqual({ ok: true });
    });

    it("rejects a site that is not in the list and suggests matches by name", () => {
        const result = validateToken({ kind: "site", value: "boiler" }, tokenDefinitions(sites), []);
        expect(result.ok).toBe(false);
        if (result.ok === false) {
            expect(result.reason).toBe("unknownValue");
            expect(result.suggestions).toEqual([
                { label: "Boiler room", input: `site:${sites[1].value}`, commit: true },
            ]);
        }
    });

    it("takes any value while no locations are known", () => {
        expect(tokenDefinitions().find((d) => d.kind === "site")!.values).toBeUndefined();
        expect(validateToken({ kind: "site", value: "anything" }, tokenDefinitions([]), [])).toEqual({ ok: true });
    });
});

describe("reading follow-up", () => {
    const reading = parseSearch("type:GasMeterAsset reading:>5");
    const gasColumns = [
        { name: "volume", primary: true, selected: true },
        { name: "gas_temperature", primary: false, selected: false },
    ];
    const mixedColumns = [
        { name: "volume", primary: true },
        { name: "currentReading", primary: true },
    ];

    it("finds the primary register only when exactly one column is primary", () => {
        expect(singlePrimaryRegister(gasColumns)).toBe("volume");
        expect(singlePrimaryRegister(mixedColumns)).toBeUndefined();
        expect(singlePrimaryRegister(undefined)).toBeUndefined();
    });

    it("does nothing without a reading token", () => {
        expect(readingFollowUp(parseSearch("type:GasMeterAsset"), undefined, gasColumns)).toEqual({ action: "none" });
    });

    it("re-runs a shared URL's dropped reading token with the register the result reports", () => {
        expect(readingFollowUp(reading, undefined, gasColumns)).toEqual({ action: "rerun", primaryRegister: "volume" });
    });

    it("re-runs when the register sent was stale", () => {
        expect(readingFollowUp(reading, "currentReading", gasColumns)).toEqual({ action: "rerun", primaryRegister: "volume" });
    });

    it("is done once the result confirms the register that was sent", () => {
        expect(readingFollowUp(reading, "volume", gasColumns)).toEqual({ action: "none" });
    });

    it("treats an applied filter that matched nothing as an answer", () => {
        expect(readingFollowUp(reading, "volume", [])).toEqual({ action: "none" });
    });

    it("is unresolved when the result spans several types", () => {
        expect(readingFollowUp(parseSearch("reading:>5"), undefined, mixedColumns)).toEqual({ action: "unresolved" });
    });

    it("is unresolved when nothing names a register", () => {
        expect(readingFollowUp(parseSearch("reading:>5"), undefined, [])).toEqual({ action: "unresolved" });
    });
});

describe("site options", () => {
    const UNNAMED = "Unnamed location";

    it("names an unreadable parent as unnamed, never by its id", () => {
        const sites = mergeSiteOptions([], [{ key: "id-north", label: "North" }, { key: "id-hidden" }], UNNAMED);
        expect(sites).toEqual([{ value: "id-north", label: "North" }, { value: "id-hidden", label: UNNAMED }]);
        expect(sites.some((site) => site.label.startsWith("id-"))).toBe(false);
    });

    it("keeps a name learnt earlier and ignores facets without a key", () => {
        const known = [{ value: "id-north", label: "North" }];
        expect(mergeSiteOptions(known, [{ key: "id-north" }, { label: "Nowhere" }], UNNAMED))
            .toEqual([{ value: "id-north", label: "North" }]);
    });

    it("labels a site chip by name, or as unnamed when the page does not know it", () => {
        const sites = [{ value: "id-north", label: "North" }];
        expect(siteLabel(sites, "id-north", UNNAMED)).toBe("North");
        expect(siteLabel(sites, "id-unknown", UNNAMED)).toBe(UNNAMED);
        expect(siteLabel([], "id-north", UNNAMED)).toBe(UNNAMED);
    });
});
