// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { i18next } from "@openremote/or-translate";

// Vaadin needs browser APIs jsdom lacks (constructable style sheets, popover, matchMedia). The tags
// stay plain elements here: the tests read the bound properties and fire the events Vaadin fires.
vi.mock("@openremote/or-vaadin-components/or-vaadin-select", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-radio-group", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-date-picker", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-badge", () => ({}));

import "./or-fleet-export-form";
import type { OrFleetExportForm } from "./or-fleet-export-form";

const WATER = { type: "WaterMeterAsset", deviceCount: 3, groups: [{ key: "volume", attribute: "currentReading" }] };
const GAS = { type: "GasMeterAsset", deviceCount: 5, groups: [{ key: "volume", attribute: "volume" }] };
const OPTIONS = { lastDay: "2026-11-11", lastMonth: "2026-10", maxDays: 366, maxMonths: 24, types: [WATER, GAS] };

type Item = { label: string; value: string; disabled?: boolean };

async function mount(props: Partial<OrFleetExportForm>): Promise<{ form: OrFleetExportForm; changes: CustomEvent[] }> {
    const form = document.createElement("or-fleet-export-form") as OrFleetExportForm;
    const changes: CustomEvent[] = [];
    form.addEventListener("or-fleet-export-form-change", (e) => changes.push(e as CustomEvent));
    Object.assign(form, props);
    document.body.appendChild(form);
    await settle(form);
    return { form, changes };
}
async function settle(form: OrFleetExportForm) {
    for (let i = 0; i < 3; i++) { await form.updateComplete; }
}
const $ = (form: OrFleetExportForm, s: string) => form.shadowRoot!.querySelector<HTMLElement>(s);
const $$ = (form: OrFleetExportForm, s: string) => Array.from(form.shadowRoot!.querySelectorAll<HTMLElement>(s));
const items = (form: OrFleetExportForm, s: string) => ($(form, s) as unknown as { items: Item[] }).items;
const valueOf = (form: OrFleetExportForm, s: string) => ($(form, s) as unknown as { value: string }).value;
const last = (changes: CustomEvent[]) => changes[changes.length - 1].detail;
function change(element: HTMLElement, value: string) {
    element.dispatchEvent(new CustomEvent("value-changed", { detail: { value } }));
}

beforeAll(async () => { await i18next.init({ lng: "en", resources: {} }); });
afterEach(() => { document.body.innerHTML = ""; });

describe("or-fleet-export-form", () => {
    it("hides the type select for one type", async () => {
        const { form, changes } = await mount({ options: { ...OPTIONS, types: [WATER] } });
        expect($(form, "or-vaadin-select.type")).toBeNull();
        expect(last(changes).assetType).toBe("WaterMeterAsset");
    });

    it("preselects the type with most devices", async () => {
        const { form, changes } = await mount({ options: OPTIONS });
        expect(valueOf(form, "or-vaadin-select.type")).toBe("GasMeterAsset");
        expect(items(form, "or-vaadin-select.type").map((i) => i.label)).toEqual(["WaterMeterAsset (3)", "GasMeterAsset (5)"]);
        expect(last(changes)).toMatchObject({ assetType: "GasMeterAsset", report: "MONTHLY", from: "2026-10-01", to: "2026-10-01" });
        expect(last(changes).problem).toBeUndefined();

        change($(form, "or-vaadin-select.type")!, "WaterMeterAsset");
        await settle(form);
        expect(last(changes).assetType).toBe("WaterMeterAsset");
    });

    it("custom report is disabled and badged", async () => {
        const { form } = await mount({ options: OPTIONS });
        const buttons = $$(form, "or-vaadin-radio-group.report vaadin-radio-button");
        expect(buttons.map((b) => b.getAttribute("value"))).toEqual(["MONTHLY", "DAILY", "custom"]);
        const custom = $(form, "vaadin-radio-button[value=custom]")!;
        expect(custom.hasAttribute("disabled")).toBe(true);
        expect(custom.querySelector("or-vaadin-badge")).not.toBeNull();
        expect(buttons[0].hasAttribute("disabled")).toBe(false);
    });

    it("monthly last 12 months emits the resolved range", async () => {
        const { form, changes } = await mount({ options: OPTIONS });
        form.period = "last12";
        await settle(form);
        expect(last(changes)).toMatchObject({ report: "MONTHLY", period: "last12", from: "2025-11-01", to: "2026-10-01" });
        const labels = items(form, "or-vaadin-select.period").map((i) => i.label);
        expect(labels[0]).toMatch(/lastMonth \(Oct 2026\)$/);
        expect(labels[2]).toMatch(/last12 \(Nov 2025 – Oct 2026\)$/);
    });

    it("switching to daily keeps the period key and recomputes days", async () => {
        const { form, changes } = await mount({ options: OPTIONS, period: "last12" });
        change($(form, "or-vaadin-radio-group.report")!, "DAILY");
        await settle(form);
        expect(form.period).toBe("last12");
        expect(last(changes)).toMatchObject({ report: "DAILY", period: "last12", from: "2025-11-01", to: "2026-10-31" });
        expect(items(form, "or-vaadin-select.period")[2].label).toContain("(11/1/2025 – 10/31/2026)");
    });

    it("ignores the disabled custom report", async () => {
        const { form, changes } = await mount({ options: OPTIONS });
        change($(form, "or-vaadin-radio-group.report")!, "custom");
        await settle(form);
        expect(last(changes).report).toBe("MONTHLY");
    });

    it("offers months up to last month for a custom monthly range and reports problems", async () => {
        const { form, changes } = await mount({ options: OPTIONS, period: "custom" });
        const from = items(form, "or-vaadin-select.from");
        expect(from).toHaveLength(24);
        expect(from[0].value).toBe("2026-10-01");
        expect(from[23].value).toBe("2024-11-01");
        expect(last(changes)).toMatchObject({ period: "custom", from: "2026-10-01", to: "2026-10-01" });

        change($(form, "or-vaadin-select.from")!, "2026-09-01");
        change($(form, "or-vaadin-select.to")!, "2026-08-01");
        await settle(form);
        expect(last(changes)).toMatchObject({ from: "2026-09-01", to: "2026-08-01", problem: "app:fleet.export.error.order" });
    });

    it("offers date pickers up to last day for a custom daily range", async () => {
        const { form, changes } = await mount({ options: OPTIONS, report: "DAILY" as never, period: "custom" });
        const pickers = $$(form, "or-vaadin-date-picker") as unknown as { max: string; value: string }[];
        expect(pickers).toHaveLength(2);
        expect(pickers.map((p) => p.max)).toEqual(["2026-11-11", "2026-11-11"]);
        expect(last(changes)).toMatchObject({ report: "DAILY", from: "2026-10-01", to: "2026-10-31" });

        change($(form, "or-vaadin-date-picker.to")!, "2026-11-12");
        await settle(form);
        expect(last(changes).problem).toBe("app:fleet.export.error.future");
    });

    it("keeps a cleared custom date empty and reports it missing", async () => {
        const { form, changes } = await mount({ options: OPTIONS, report: "DAILY" as never, period: "custom" });
        change($(form, "or-vaadin-date-picker.from")!, "2026-09-15");
        await settle(form);
        change($(form, "or-vaadin-date-picker.to")!, "");
        await settle(form);
        expect(form.customFrom).toBe("2026-09-15");
        expect(form.customTo).toBe("");
        expect(last(changes)).toMatchObject({ from: "2026-09-15", to: "", problem: "app:fleet.export.error.missing" });
    });

    it("shows a note and nothing else when no type can be exported", async () => {
        const { form, changes } = await mount({ options: { ...OPTIONS, types: [] } });
        expect(($(form, ".note or-translate") as unknown as { value?: string } | null)?.value).toBe("app:fleet.export.noTypes");
        expect($(form, "or-vaadin-radio-group")).toBeNull();
        expect(last(changes).problem).toBe("app:fleet.export.error.selectType");
    });
});
