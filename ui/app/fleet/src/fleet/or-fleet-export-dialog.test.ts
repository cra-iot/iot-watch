// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { i18next } from "@openremote/or-translate";

const api = vi.hoisted(() => ({ exportOptions: vi.fn(), export: vi.fn() }));
vi.mock("rest", () => ({ default: { api: { FleetResource: api } } }));
// Vaadin needs browser APIs jsdom lacks (constructable style sheets, popover, matchMedia). The tags
// stay plain elements here, so the dialog's content is reachable without the overlay.
vi.mock("@openremote/or-vaadin-components/or-vaadin-dialog", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-button", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-select", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-radio-group", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-date-picker", () => ({}));
vi.mock("@openremote/or-vaadin-components/or-vaadin-badge", () => ({}));

import "./or-fleet-export-dialog";
import type { OrFleetExportDialog } from "./or-fleet-export-dialog";
import type { OrFleetExportForm } from "./or-fleet-export-form";
import { toExportRequest } from "./fleet-export";

const OPTIONS = {
    lastDay: "2026-11-11", lastMonth: "2026-10", maxDays: 366, maxMonths: 24,
    types: [
        { type: "WaterMeterAsset", deviceCount: 3, groups: [{ key: "volume", attribute: "currentReading" }] },
        { type: "GasMeterAsset", deviceCount: 2, groups: [{ key: "volume", attribute: "volume" }] },
    ],
};
const QUERY = { realm: "demo", text: ["x"] } as never;

async function settle(element: OrFleetExportDialog) {
    for (let i = 0; i < 4; i++) {
        await Promise.resolve();
        await element.updateComplete;
        await form(element)?.updateComplete;
    }
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
const form = (e: OrFleetExportDialog) => $(e, "or-fleet-export-form") as OrFleetExportForm | null;
const dialog = (e: OrFleetExportDialog) => $(e, "or-vaadin-dialog") as HTMLElement & { opened?: boolean; noCloseOnEsc?: boolean };
// or-translate renders into its own shadow root, so the problem is read from its value.
const problem = (e: OrFleetExportDialog) => ($(e, ".error or-translate") as unknown as { value?: string } | null)?.value;
const exportButton = (e: OrFleetExportDialog) => $(e, "or-vaadin-button.export")!;
const cancelButton = (e: OrFleetExportDialog) => $(e, "or-vaadin-button.cancel")!;
const closes = (e: OrFleetExportDialog) => {
    const seen: Event[] = [];
    e.addEventListener("or-fleet-export-close", (ev) => seen.push(ev));
    return seen;
};
function stubDownload(): string[] {
    URL.createObjectURL = vi.fn(() => "blob:x");
    URL.revokeObjectURL = vi.fn();
    const downloads: string[] = [];
    // jsdom does not implement navigation, which a download link click would trigger.
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { downloads.push(this.download); });
    return downloads;
}

beforeAll(async () => { await i18next.init({ lng: "en", resources: {} }); });
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
afterEach(() => {
    document.body.innerHTML = "";
    vi.useRealTimers();
    vi.restoreAllMocks();
});

describe("or-fleet-export-dialog", () => {
    it("opens the Vaadin dialog and hands the options to the form", async () => {
        const element = await mount();
        expect(api.exportOptions).toHaveBeenCalledWith(QUERY);
        expect(dialog(element).opened).toBe(true);
        expect(form(element)!.options).toEqual(OPTIONS);
        expect(form(element)!.assetType).toBe("WaterMeterAsset");
        expect(exportButton(element).getAttribute("theme")).toBe("primary");
        expect(exportButton(element).hasAttribute("disabled")).toBe(false);
    });

    it("shows a loading indicator until the options arrive, without the form", async () => {
        let answer: (v: unknown) => void = () => {};
        api.exportOptions.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
        const element = await mount();
        const loading = $(element, ".loading");
        expect(loading).not.toBeNull();
        expect(loading!.getAttribute("role")).toBe("status");
        expect(loading!.querySelector(".progress")).not.toBeNull();
        expect((loading!.querySelector("or-translate") as unknown as { value?: string }).value).toBe("app:fleet.export.loading");
        expect(form(element)).toBeNull();
        expect(exportButton(element).hasAttribute("disabled")).toBe(true);

        answer({ data: OPTIONS });
        await settle(element);
        expect($(element, ".loading")).toBeNull();
        expect(form(element)!.options).toEqual(OPTIONS);
    });

    it("drops the loading indicator when the options fail to load", async () => {
        api.exportOptions.mockRejectedValue(new Error("down"));
        const element = await mount();
        expect($(element, ".loading")).toBeNull();
        expect(problem(element)).toBe("app:fleet.export.error.failed");
    });

    it("shows the progress bar next to the status while exporting", async () => {
        const element = await mount();
        api.export.mockReturnValue(new Promise(() => {}));
        exportButton(element).click();
        await settle(element);
        expect($(element, ".status .progress")).not.toBeNull();
    });

    it("keeps the footer buttons' own box, which the Vaadin dialog dissolves", async () => {
        // jsdom does not cascade into the overlay; the rule itself is the guard, checked in the browser.
        const element = await mount();
        const styles = (element.constructor as unknown as { styles: { cssText: string } }).styles.cssText;
        expect(styles).toMatch(/or-vaadin-button\[slot="footer"\]\s*\{\s*display:\s*inline-flex;/);
    });

    it("exports with the form's choice and remembers it", async () => {
        const element = await mount();
        const downloads = stubDownload();
        api.export.mockResolvedValue({ data: new Blob(["a"]), headers: { "content-disposition": "attachment; filename=\"x.csv\"" } });
        const closed = closes(element);
        exportButton(element).click();
        await settle(element);
        expect(api.export).toHaveBeenCalledWith(
            toExportRequest(QUERY, "WaterMeterAsset", "MONTHLY" as never, "2026-10-01", "2026-10-01"),
            { responseType: "blob" });
        expect(JSON.parse(window.localStorage.getItem("fleet.export.choice")!))
            .toEqual({ assetType: "WaterMeterAsset", report: "MONTHLY", period: "lastMonth" });
        expect(downloads).toEqual(["x.csv"]);
        expect(closed).toHaveLength(1);
    });

    it("restores the remembered choice", async () => {
        window.localStorage.setItem("fleet.export.choice", JSON.stringify({ assetType: "GasMeterAsset", report: "DAILY", period: "last3" }));
        const element = await mount();
        expect(form(element)!.assetType).toBe("GasMeterAsset");
        expect(form(element)!.report).toBe("DAILY");
        expect(form(element)!.period).toBe("last3");
    });

    it("starts a reopened dialog from the remembered choice, not the last one shown", async () => {
        const element = await mount();
        form(element)!.period = "last12";
        await settle(element);
        cancelButton(element).click();
        element.open = false;
        await settle(element);
        element.open = true;
        await settle(element);
        expect(form(element)!.period).toBe("lastMonth");

        window.localStorage.setItem("fleet.export.choice", JSON.stringify({ assetType: "GasMeterAsset", report: "DAILY", period: "last3" }));
        element.open = false;
        await settle(element);
        element.open = true;
        await settle(element);
        expect(form(element)!.assetType).toBe("GasMeterAsset");
        expect(form(element)!.report).toBe("DAILY");
        expect(form(element)!.period).toBe("last3");
    });

    it("falls back to the defaults when browser storage fails", async () => {
        Object.defineProperty(window, "localStorage", { configurable: true, get: () => { throw new Error("blocked"); } });
        const element = await mount();
        expect(form(element)!.report).toBe("MONTHLY");
        stubDownload();
        api.export.mockResolvedValue({ data: new Blob(["a"]), headers: {} });
        const closed = closes(element);
        exportButton(element).click();
        await settle(element);
        expect(closed).toHaveLength(1);
    });

    it("shows a range problem from the form and disables Export", async () => {
        const element = await mount();
        const f = form(element)!;
        f.period = "custom";
        await settle(element);
        f.customFrom = "2026-10-01";
        f.customTo = "2026-01-01";
        await settle(element);
        expect(problem(element)).toBe("app:fleet.export.error.order");
        expect(exportButton(element).hasAttribute("disabled")).toBe(true);
    });

    it("shows the busy message for a 429", async () => {
        const element = await mount();
        api.export.mockRejectedValue({ response: { status: 429, data: new Blob(["busy"]) } });
        exportButton(element).click();
        await settle(element);
        expect(problem(element)).toBe("app:fleet.export.error.busy");
    });

    it("translates a known server message and shows an unknown one as sent", async () => {
        const element = await mount();
        api.export.mockRejectedValue({ response: { status: 400, data: "Export too large: narrow the search or the period" } });
        exportButton(element).click();
        await settle(element);
        expect(problem(element)).toBe("app:fleet.export.error.tooLarge");

        api.export.mockRejectedValue({ response: { status: 403, data: "No readable readings for WaterMeterAsset" } });
        exportButton(element).click();
        await settle(element);
        const translated = $(element, ".error or-translate") as unknown as { value?: string; options?: Record<string, string> };
        expect(translated.value).toBe("app:fleet.export.error.notReadable");
        expect(translated.options).toEqual({ type: "WaterMeterAsset" });

        api.export.mockRejectedValue({ response: { status: 400, data: "Unknown status: sleeping" } });
        exportButton(element).click();
        await settle(element);
        expect($(element, ".error")!.textContent!.trim()).toBe("Unknown status: sleeping");
    });

    it("disables Cancel and ignores Esc while an export runs", async () => {
        const element = await mount();
        stubDownload();
        let release: (v: unknown) => void = () => {};
        api.export.mockReturnValue(new Promise((resolve) => { release = resolve; }));
        const closed = closes(element);
        expect(dialog(element).noCloseOnEsc).toBe(false);
        exportButton(element).click();
        await settle(element);
        expect(cancelButton(element).hasAttribute("disabled")).toBe(true);
        expect(dialog(element).noCloseOnEsc).toBe(true);
        expect(form(element)!.busy).toBe(true);
        dialog(element).dispatchEvent(new CustomEvent("opened-changed", { detail: { value: false } }));
        cancelButton(element).click();
        expect(closed).toHaveLength(0);
        release({ data: new Blob(["a"]), headers: {} });
        await settle(element);
    });

    it("closes on Cancel and on Esc when idle", async () => {
        const element = await mount();
        const closed = closes(element);
        cancelButton(element).click();
        expect(closed).toHaveLength(1);
        dialog(element).dispatchEvent(new CustomEvent("opened-changed", { detail: { value: false } }));
        expect(closed).toHaveLength(2);
    });

    it("names the download from the period and revokes the object URL later", async () => {
        vi.useFakeTimers({ toFake: ["setTimeout"] });
        const element = await mount();
        const downloads = stubDownload();
        api.export.mockResolvedValue({ data: new Blob(["a"]), headers: {} });
        exportButton(element).click();
        await settle(element);
        expect(downloads).toEqual(["fleet-2026-10-01_2026-10-01.csv"]);
        expect(URL.revokeObjectURL).not.toHaveBeenCalled();
        vi.advanceTimersByTime(10_000);
        expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:x");
    });

    it("shows a status while an export runs", async () => {
        const element = await mount();
        expect($(element, "[role=status] or-translate")).toBeNull();
        stubDownload();
        let release: (v: unknown) => void = () => {};
        api.export.mockReturnValue(new Promise((resolve) => { release = resolve; }));
        exportButton(element).click();
        await settle(element);
        const status = $(element, "[role=status] or-translate") as unknown as { value?: string } | null;
        expect(status?.value).toBe("app:fleet.export.exporting");
        release({ data: new Blob(["a"]), headers: {} });
        await settle(element);
    });

    it("keeps Export disabled when no type can be exported", async () => {
        api.exportOptions.mockResolvedValue({ data: { ...OPTIONS, types: [] } });
        const element = await mount();
        expect(form(element)!.options!.types).toEqual([]);
        expect(problem(element)).toBeUndefined();
        expect(exportButton(element).hasAttribute("disabled")).toBe(true);
    });

    it("drops an options answer that arrives after the dialog was closed", async () => {
        let answer: (v: unknown) => void = () => {};
        api.exportOptions.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
        const element = await mount();
        element.open = false;
        await settle(element);
        answer({ data: OPTIONS });
        await settle(element);
        expect(form(element)!.options).toBeUndefined();
    });
});
