import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
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

        it(`${lang} has every export text`, () => {
            const fleet = JSON.parse(readFileSync(new URL(`../../locales/${lang}/app.json`, import.meta.url), "utf-8")).fleet;
            const keys = ["button", "title", "help", "from", "to", "utc", "identity", "measurements", "all", "none", "cancel", "export",
                "error.missing", "error.order", "error.future", "error.tooLong", "error.busy", "error.failed"];
            for (const key of keys) {
                const value = key.split(".").reduce((node: any, part) => node?.[part], fleet?.export);
                expect(value, `fleet.export.${key}`).toBeTruthy();
            }
        });
    }
});
