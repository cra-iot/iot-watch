/** Page sizes the pager offers; the first is the server default (FleetResultAssembler.DEFAULT_LIMIT). */
export const PAGE_SIZES = [50, 100, 200];

export const DEFAULT_PAGE_SIZE = PAGE_SIZES[0];

/** One pager button: a page number (0-based) or a gap standing for the pages left out. */
export type PageItem = number | "gap";

/**
 * The pages the pager links to: always the first and the last, the current one with a neighbour on
 * each side, and a gap for every run left out. A gap that would hide a single page shows that page.
 */
export function pageItems(current: number, count: number): PageItem[] {
    if (count <= 0) { return []; }
    const shown = new Set([0, count - 1, current - 1, current, current + 1].filter((page) => page >= 0 && page < count));
    const pages = [...shown].sort((a, b) => a - b);
    const items: PageItem[] = [];
    pages.forEach((page, index) => {
        const previous = pages[index - 1];
        if (previous !== undefined && page - previous === 2) {
            items.push(previous + 1);
        } else if (previous !== undefined && page - previous > 2) {
            items.push("gap");
        }
        items.push(page);
    });
    return items;
}

/** A page size from a URL or storage, or undefined when it is not one the pager offers. */
export function parsePageSize(value: string | null | undefined): number | undefined {
    const size = Number(value);
    return PAGE_SIZES.includes(size) ? size : undefined;
}
