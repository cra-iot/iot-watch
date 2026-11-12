// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it } from "vitest";
import { i18next } from "@openremote/or-translate";
import "./or-fleet-results";
import type { OrFleetResults } from "./or-fleet-results";

const NOW = Date.now();
const row = (id: string, over: Record<string, unknown> = {}) =>
    ({ id, name: id, type: "GasMeterAsset", lastSeen: NOW - 1000, values: {}, ...over });
const result = (rows: unknown[], over: Record<string, unknown> = {}) =>
    ({ total: rows.length, offset: 0, limit: 50, rows, facets: {}, columns: [], ...over }) as never;

async function mount(props: Partial<OrFleetResults>): Promise<OrFleetResults> {
    const element = document.createElement("or-fleet-results") as OrFleetResults;
    Object.assign(element, props);
    document.body.appendChild(element);
    await element.updateComplete;
    return element;
}

const $ = (element: OrFleetResults, selector: string) => element.shadowRoot!.querySelector<HTMLElement>(selector);
const $$ = (element: OrFleetResults, selector: string) => Array.from(element.shadowRoot!.querySelectorAll<HTMLElement>(selector));

function events(element: OrFleetResults, name: string): CustomEvent[] {
    const seen: CustomEvent[] = [];
    element.addEventListener(name, (ev) => seen.push(ev as CustomEvent));
    return seen;
}

// Without resources a translation renders its key, which the tests read.
beforeAll(async () => { await i18next.init({ lng: "en", resources: {} }); });
afterEach(() => { document.body.innerHTML = ""; });

describe("or-fleet-results", () => {
    it("shows skeleton rows while the first result loads, and nothing when idle", async () => {
        const loading = await mount({ loading: true });
        expect($$(loading, ".skeleton tr")).toHaveLength(8);
        expect($(loading, ".body")!.getAttribute("aria-busy")).toBe("true");

        const idle = await mount({});
        expect(idle.shadowRoot!.querySelector("table")).toBeNull();
    });

    it("keeps the shown result, dimmed with a progress bar, while the next one loads", async () => {
        const element = await mount({ result: result([row("a"), row("b")]), loading: true });
        expect($$(element, "tbody tr.row")).toHaveLength(2);
        expect($(element, ".body")!.classList.contains("loading")).toBe(true);
        expect($(element, ".progress")).not.toBeNull();
    });

    it("offers an actions slot at the end of the result bar, after the columns menu", async () => {
        const element = await mount({ result: result([row("a")]) });
        const bar = $$(element, ".bar")[0];
        const slot = bar.querySelector("slot[name=actions]");
        expect(slot).not.toBeNull();
        expect(bar.lastElementChild).toBe(slot);
        expect(slot!.previousElementSibling!.classList.contains("picker")).toBe(true);
    });

    it("renders one row per meter with its status class and an open link", async () => {
        const element = await mount({ result: result([row("a", { alarms: ["leakage"] }), row("b", { lastSeen: undefined }), row("c")]) });
        const rows = $$(element, "tbody tr.row");
        expect(rows.map((r) => Array.from(r.classList).filter((c) => ["alarm", "silent", "ok"].includes(c)))).toEqual([["alarm"], ["silent"], ["ok"]]);
        expect(rows[0].querySelector("a.open")!.getAttribute("href")).toContain("a");
    });

    it("shows the exact time of the last message under its age, and a dash when there was none", async () => {
        const element = await mount({ result: result([row("a", { lastSeen: NOW - 6 * 86_400_000 }), row("b", { lastSeen: undefined })]) });
        const cells = $$(element, "td.age");
        const expected = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(NOW - 6 * 86_400_000);
        expect(cells[0].querySelector(".when")!.textContent).toBe(expected);
        expect(cells[1].querySelector(".when")).toBeNull();
        expect(cells[1].textContent).toContain("—");
    });

    it("marks an old last seen as stale even when the row has an alarm", async () => {
        const old = NOW - 48 * 3_600_000;
        const element = await mount({ result: result([row("a", { lastSeen: old, alarms: ["leakage"] }), row("b", { lastSeen: old }), row("c")]) });
        expect($$(element, "td.age").map((td) => td.classList.contains("stale"))).toEqual([true, true, false]);
    });

    describe("reading", () => {
        const gas = { name: "volume", label: "Volume", primary: true, selected: true, numeric: true, units: [], assetType: "GasMeterAsset" };
        const power = { name: "energy", label: "Energy import", primary: true, selected: true, numeric: true, units: [], assetType: "ElectricMeterAsset" };
        const meter = (id: string, over: Record<string, unknown> = {}) =>
            row(id, { primaryName: "volume", values: { volume: 12 }, ...over });
        const cell = (element: OrFleetResults, index = 0) => $$(element, "tbody tr.row")[index].querySelector("td.numeric")!;

        it("names the register under each value when the types differ, and lists them on the header", async () => {
            const element = await mount({ result: result([meter("g"), meter("e", { type: "ElectricMeterAsset", primaryName: "energy", values: { energy: 5 } })], { columns: [gas, power] }) });
            expect(cell(element, 0).querySelector(".what")!.textContent!.trim()).toBe("Volume");
            expect(cell(element, 1).querySelector(".what")!.textContent!.trim()).toBe("Energy import");
            const header = $$(element, "thead th").find((th) => th.textContent!.includes("fleet.column.reading"))!;
            expect(header.getAttribute("title")).toContain("Volume");
        });

        it("puts a single register in the header instead of under every value", async () => {
            const element = await mount({ result: result([meter("g")], { columns: [gas] }) });
            expect($$(element, "thead th").some((th) => th.textContent!.includes("Volume"))).toBe(true);
            expect(cell(element).querySelector(".what")).toBeNull();
        });
    });

    it("offers to clear a search that matched nothing", async () => {
        const element = await mount({ result: result([]), filtered: true });
        const cleared = events(element, "or-fleet-clear");
        expect($(element, ".empty .title")!.textContent).toContain("fleet.emptyState.filtered");
        $(element, ".empty button")!.click();
        expect(cleared).toHaveLength(1);
    });

    it("says the realm is empty without a search, with nothing to clear", async () => {
        const element = await mount({ result: result([]), filtered: false });
        expect($(element, ".empty .title")!.textContent).toContain("fleet.emptyState.realm");
        expect($(element, ".empty button")).toBeNull();
    });

    it("shows status chips with counts before the status filter and toggles a status", async () => {
        const counts = [{ key: "alarm", count: 2 }, { key: "silent", count: 5 }, { key: "ok", count: 9 }];
        const element = await mount({
            result: result([row("a", { alarms: ["x"] })], { statusCounts: counts, facets: { status: [{ key: "alarm", count: 2 }] } }),
            status: ["alarm"],
        });
        const chips = $$(element, ".status-chip");
        expect(chips.map((c) => c.querySelector(".n")!.textContent)).toEqual(["2", "5", "9"]);
        expect(chips.map((c) => c.getAttribute("aria-pressed"))).toEqual(["true", "false", "false"]);

        const toggled = events(element, "or-fleet-status");
        chips[1].click();
        chips[0].click();
        expect(toggled.map((e) => e.detail.status)).toEqual([["alarm", "silent"], []]);
    });

    it("falls back to the status facets when the server sends no status counts", async () => {
        const element = await mount({ result: result([row("a")], { facets: { status: [{ key: "ok", count: 1 }] } }) });
        expect($$(element, ".status-chip .n").map((n) => n.textContent)).toEqual(["0", "0", "1"]);
    });

    it("refreshes on request and keeps the scroll position for the refreshed result", async () => {
        const element = await mount({ result: result([row("a")]), updatedAt: NOW });
        const refreshed = events(element, "or-fleet-refresh");
        $(element, ".updated button")!.click();
        expect(refreshed).toHaveLength(1);

        const scroll = $(element, ".scroll")!;
        scroll.scrollTop = 40;
        element.refreshed = true;
        element.result = result([row("a")]);
        await element.updateComplete;
        expect(scroll.scrollTop).toBe(40);

        // Any other result (another search, page or sort) starts at the top again.
        element.refreshed = false;
        element.result = result([row("b")]);
        await element.updateComplete;
        expect(scroll.scrollTop).toBe(0);
    });

    it("disables the refresh while a query runs", async () => {
        const element = await mount({ result: result([row("a")]), loading: true });
        expect(($(element, ".updated button") as HTMLButtonElement).disabled).toBe(true);
    });

    it("renders a sort button only on sortable columns and emits the flipped direction", async () => {
        const columns = [
            { name: "volume", label: "Volume", numeric: true, selected: true, assetType: "GasMeterAsset" },
            { name: "valve_state", label: "Valve", numeric: false, selected: true, assetType: "GasMeterAsset" },
        ];
        const element = await mount({ result: result([row("a")], { columns }), sort: { field: "volume", descending: false } });
        const headers = $$(element, "thead th");
        const volume = headers.find((th) => th.textContent!.includes("Volume"))!;
        const valve = headers.find((th) => th.textContent!.includes("Valve"))!;
        expect(volume.querySelector("button.sort")).not.toBeNull();
        expect(valve.querySelector("button.sort")).toBeNull();

        const sorted = events(element, "or-fleet-sort");
        volume.querySelector<HTMLElement>("button.sort")!.click();
        expect(sorted[0].detail.sort).toEqual({ field: "volume", descending: true });
    });

    it("renders a collapsed group as a header without rows and toggles it", async () => {
        const facets = { types: [{ key: "ElectricMeterAsset", count: 3 }, { key: "GasMeterAsset", count: 1 }] };
        const element = await mount({
            result: result([row("g")], { facets, total: 4, paged: 1 }),
            groupBy: "TYPE", collapsed: ["ElectricMeterAsset"],
        });
        const headers = $$(element, ".group-header");
        expect(headers.map((h) => h.getAttribute("aria-expanded"))).toEqual(["false", "true"]);
        expect($$(element, "tbody tr.row")).toHaveLength(1);

        const toggled = events(element, "or-fleet-collapse");
        headers[0].click();
        expect(toggled[0].detail.collapsed).toEqual([]);
    });
});
