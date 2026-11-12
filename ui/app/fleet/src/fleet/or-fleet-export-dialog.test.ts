// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { i18next } from "@openremote/or-translate";

const api = vi.hoisted(() => ({ exportOptions: vi.fn(), export: vi.fn() }));
vi.mock("rest", () => ({ default: { api: { FleetResource: api } } }));

import "./or-fleet-export-dialog";
import type { OrFleetExportDialog } from "./or-fleet-export-dialog";
import { toExportRequest } from "./fleet-export";

const OPTIONS = {
    maxTo: "2026-11-11",
    maxDays: 366,
    types: [{
        type: "WaterMeterAsset", deviceCount: 3,
        identity: [{ name: "devEui", label: "DevEUI", defaultSelected: true }, { name: "notes", label: "Notes", defaultSelected: false }],
        measurements: [{ name: "currentReading", label: "Reading", defaultSelected: true }, { name: "flow_rate", label: "Flow", defaultSelected: false }],
    }],
};
const QUERY = { realm: "demo", text: ["x"] } as never;

async function settle(element: OrFleetExportDialog) {
    for (let i = 0; i < 4; i++) { await Promise.resolve(); await element.updateComplete; }
}
async function mount(): Promise<OrFleetExportDialog> {
    const element = document.createElement("or-fleet-export-dialog") as OrFleetExportDialog;
    element.query = QUERY;
    document.body.appendChild(element);
    element.open = true;
    await settle(element);
    return element;
}
const $ = (e: OrFleetExportDialog, s: string) => e.shadowRoot!.querySelector<HTMLElement>(s);
const $$ = (e: OrFleetExportDialog, s: string) => Array.from(e.shadowRoot!.querySelectorAll<HTMLElement>(s));
const checked = (e: OrFleetExportDialog, name: string) => (e.shadowRoot!.querySelector(`input[data-name="${name}"]`) as HTMLInputElement).checked;
// or-translate renders into its own shadow root, so the problem is read from its value.
const problem = (e: OrFleetExportDialog) => ($(e, ".error or-translate") as unknown as { value?: string } | null)?.value;
const exportButton = (e: OrFleetExportDialog) => $(e, "button.export") as HTMLButtonElement;

beforeAll(async () => {
    await i18next.init({ lng: "en", resources: {} });
    HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
    HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
});
beforeEach(() => {
    api.exportOptions.mockReset().mockResolvedValue({ data: OPTIONS });
    api.export.mockReset();
    // This jsdom setup has no usable localStorage; a map-backed one stands in for it.
    const store = new Map<string, string>();
    Object.defineProperty(window, "localStorage", {
        configurable: true,
        value: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v); }, clear: () => store.clear() },
    });
});
afterEach(() => { document.body.innerHTML = ""; });

describe("or-fleet-export-dialog", () => {
    it("renders one section per type with the default attributes checked", async () => {
        const element = await mount();
        expect(api.exportOptions).toHaveBeenCalledWith(QUERY);
        expect($$(element, "section.type")).toHaveLength(1);
        expect(checked(element, "devEui")).toBe(true);
        expect(checked(element, "currentReading")).toBe(true);
        expect(checked(element, "notes")).toBe(false);
        expect(checked(element, "flow_rate")).toBe(false);
        expect(exportButton(element).disabled).toBe(false);
    });

    it("unchecks everything of a type on none and disables Export", async () => {
        const element = await mount();
        $(element, "button.none")!.click();
        await settle(element);
        expect(checked(element, "devEui")).toBe(false);
        expect(checked(element, "currentReading")).toBe(false);
        expect(exportButton(element).disabled).toBe(true);
    });

    it("shows the future error and disables Export for a to after maxTo", async () => {
        const element = await mount();
        const to = $(element, "input.to") as HTMLInputElement;
        to.value = "2026-12-01";
        to.dispatchEvent(new Event("input"));
        await settle(element);
        expect(problem(element)).toBe("app:fleet.export.error.future");
        expect(exportButton(element).disabled).toBe(true);
    });

    it("exports the request built from the query, the range and the selection", async () => {
        const element = await mount();
        api.export.mockResolvedValue({ data: new Blob(["a"]), headers: { "content-disposition": "attachment; filename=\"x.csv\"" } });
        URL.createObjectURL = vi.fn(() => "blob:x");
        URL.revokeObjectURL = vi.fn();
        // jsdom does not implement navigation, which a download link click would trigger.
        vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
        const closes: Event[] = [];
        element.addEventListener("or-fleet-export-close", (e) => closes.push(e));
        const from = $(element, "input.from") as HTMLInputElement;
        const to = $(element, "input.to") as HTMLInputElement;
        exportButton(element).click();
        await settle(element);
        expect(api.export).toHaveBeenCalledWith(
            toExportRequest(QUERY, from.value, to.value, { WaterMeterAsset: ["devEui", "currentReading"] }),
            { responseType: "blob" });
        expect(JSON.parse(window.localStorage.getItem("fleet.export.selection")!)).toEqual({ WaterMeterAsset: ["devEui", "currentReading"] });
        expect(closes).toHaveLength(1);
    });

    it("shows the busy message for a 429", async () => {
        const element = await mount();
        api.export.mockRejectedValue({ response: { status: 429, data: new Blob(["busy"]) } });
        exportButton(element).click();
        await settle(element);
        expect(problem(element)).toBe("app:fleet.export.error.busy");
    });

    it("keeps the remembered choices of types that are not in this search", async () => {
        window.localStorage.setItem("fleet.export.selection", JSON.stringify({ GasMeterAsset: ["devEui"] }));
        const element = await mount();
        api.export.mockResolvedValue({ data: new Blob(["a"]), headers: {} });
        URL.createObjectURL = vi.fn(() => "blob:x");
        URL.revokeObjectURL = vi.fn();
        vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
        exportButton(element).click();
        await settle(element);
        expect(JSON.parse(window.localStorage.getItem("fleet.export.selection")!)).toEqual({
            GasMeterAsset: ["devEui"], WaterMeterAsset: ["devEui", "currentReading"],
        });
    });

    it("disables Cancel and ignores Esc while an export runs", async () => {
        const element = await mount();
        let release: (v: unknown) => void = () => {};
        api.export.mockReturnValue(new Promise((resolve) => { release = resolve; }));
        const closes: Event[] = [];
        element.addEventListener("or-fleet-export-close", (e) => closes.push(e));
        exportButton(element).click();
        await settle(element);
        expect(($(element, "button.cancel") as HTMLButtonElement).disabled).toBe(true);
        $(element, "dialog")!.dispatchEvent(new Event("cancel", { cancelable: true }));
        expect(closes).toHaveLength(0);
        release({ data: new Blob(["a"]), headers: {} });
    });

    it("names a zip download .zip when the server sends no file name", async () => {
        const element = await mount();
        api.export.mockResolvedValue({ data: new Blob(["a"]), headers: { "content-type": "application/zip" } });
        URL.createObjectURL = vi.fn(() => "blob:x");
        URL.revokeObjectURL = vi.fn();
        const downloads: string[] = [];
        vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { downloads.push(this.download); });
        exportButton(element).click();
        await settle(element);
        expect(downloads).toHaveLength(1);
        expect(downloads[0]).toMatch(/^fleet-.*\.zip$/);
    });

    it("shows a status while an export runs", async () => {
        const element = await mount();
        expect($(element, "[role=status] or-translate")).toBeNull();
        let release: (v: unknown) => void = () => {};
        api.export.mockReturnValue(new Promise((resolve) => { release = resolve; }));
        exportButton(element).click();
        await settle(element);
        const status = $(element, "[role=status] or-translate") as unknown as { value?: string } | null;
        expect(status?.value).toBe("app:fleet.export.exporting");
        release({ data: new Blob(["a"]), headers: {} });
    });

    it("shows a note and keeps Export disabled when no type can be exported", async () => {
        api.exportOptions.mockResolvedValue({ data: { ...OPTIONS, types: [] } });
        const element = await mount();
        expect($$(element, "section.type")).toHaveLength(0);
        expect(($(element, ".note or-translate") as unknown as { value?: string } | null)?.value).toBe("app:fleet.export.noTypes");
        expect(exportButton(element).disabled).toBe(true);
    });
});
