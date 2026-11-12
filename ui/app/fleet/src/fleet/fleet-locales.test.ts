import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { i18next } from "@openremote/or-translate";
import { STATUS_KEYS } from "./fleet-groups";

/** The tests render with empty i18next resources, so a missing key would only show in the browser. */
describe("locales", () => {
    for (const lang of ["en", "cs", "nl"]) {
        it(`${lang} names and explains every status`, () => {
            const fleet = JSON.parse(readFileSync(new URL(`../../locales/${lang}/app.json`, import.meta.url), "utf-8")).fleet;
            for (const key of STATUS_KEYS) {
                expect(fleet.status?.[key], `fleet.status.${key}`).toBeTruthy();
                expect(fleet.statusHint?.[key], `fleet.statusHint.${key}`).toBeTruthy();
            }
        });

        it(`${lang} has every export text`, async () => {
            const app = JSON.parse(readFileSync(new URL(`../../locales/${lang}/app.json`, import.meta.url), "utf-8"));
            // Resolved by i18next itself: "period" is a text and "period.lastMonth" a flat key beside it.
            const t = i18next.createInstance();
            await t.init({ lng: lang, ns: ["app"], resources: { [lang]: { app } } });
            const keys = ["button", "title", "type", "report.monthly", "report.monthlyHint", "report.daily", "report.dailyHint",
                "report.custom", "report.customHint", "inPreparation", "period", "period.lastMonth", "period.last3", "period.last12",
                "period.thisYear", "period.previousYear", "period.custom", "from", "to", "czechTime", "cancel", "export", "exporting",
                "noTypes", "error.missing", "error.order", "error.future", "error.tooLong", "error.busy", "error.failed",
                "error.selectType", "error.noDevices", "error.notExportable", "error.notReadable", "error.tooLarge"];
            for (const key of keys) {
                const value = t.t(`app:fleet.export.${key}`, { returnObjects: true, type: "{{type}}" });
                expect(typeof value === "string" && value !== `fleet.export.${key}` ? value : undefined, `fleet.export.${key}`).toBeTruthy();
            }
            for (const removed of ["help", "utc", "identity", "measurements", "all", "none", "error.nothingSelected"]) {
                expect(t.exists(`app:fleet.export.${removed}`), `fleet.export.${removed}`).toBe(false);
            }
        });
    }
});
