import { useEffect, useMemo, useState } from "react";
import {
  createSmartphone,
  deleteSmartphone,
  listSmartphones,
  updateSmartphone,
} from "../api/catalogue";
import type { SmartphoneCatalogueItem } from "../api/catalogue";
import { getSession } from "../api/session";
import { CatalogueEntryModal } from "../components/admin/CatalogueEntryModal";
import { DatabaseTablesPanel } from "../components/admin/DatabaseTablesPanel";
import { Pagination } from "../components/admin/Pagination";
import {
  ALL,
  CATALOGUE_STATUSES,
  DEFAULT_SORT,
  EMPTY_FILTERS,
  PAGE_SIZES,
  SORT_OPTIONS,
  ANY_PRICE_RANGE,
  CUSTOM_PRICE_RANGE,
  PRICE_RANGES,
  applyFilters,
  boundsForRange,
  catalogueItemPrice,
  filterChoices,
  formToRequest,
  missingSpecCount,
  pageCount,
  pageOf,
  priceRangeValue,
  sortItems,
} from "../components/admin/catalogueAdmin";
import type {
  CatalogueFilters,
  CatalogueFormValues,
  PriceAvailability,
  SpecCompleteness,
} from "../components/admin/catalogueAdmin";
import { AdminShell } from "../components/layout/AdminShell";
import { Button } from "../components/ui/Button";
import { Callout } from "../components/ui/Callout";
import { Card } from "../components/ui/Card";
import { EmptyState } from "../components/ui/EmptyState";
import { Field, FormError, Input, Select } from "../components/ui/Form";
import { LoadingBlock } from "../components/ui/LoadingBlock";
import { Modal, ModalActions } from "../components/ui/Modal";
import { PageHeader } from "../components/ui/PageHeader";
import { ToastStack } from "../components/ui/ToastStack";
import { useToasts } from "../components/ui/useToasts";

type Editing = { item?: SmartphoneCatalogueItem } | null;

const cell = "border-b border-white/[0.06] px-2 py-[10px] align-middle";
const head = "border-b border-white/[0.09] px-2 py-3 text-left font-semibold";

const value = (v: number | string | null, unit = "") =>
  v === null || v === "" ? <span className="text-[#5d6e86]">—</span> : `${v}${unit}`;

export default function AdminCatalogue() {
  const account = getSession();
  const { toasts, pushToast } = useToasts();

  const [items, setItems] = useState<SmartphoneCatalogueItem[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [filters, setFilters] = useState<CatalogueFilters>(EMPTY_FILTERS);
  const [sort, setSort] = useState(DEFAULT_SORT);
  const [pageSize, setPageSize] = useState<number>(PAGE_SIZES[1]);
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Editing>(null);
  const [deleting, setDeleting] = useState<SmartphoneCatalogueItem | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deletePending, setDeletePending] = useState(false);
  const [showTables, setShowTables] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listSmartphones()
      .then((result) => {
        if (!cancelled) setItems(result);
      })
      .catch((e: unknown) => {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : "Could not load the catalogue.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const choices = useMemo(() => filterChoices(items ?? []), [items]);
  const visible = useMemo(
    () => sortItems(applyFilters(items ?? [], filters), sort),
    [items, filters, sort]
  );
  const totalPages = pageCount(visible.length, pageSize);
  const { page: currentPage, rows } = pageOf(visible, page, pageSize);
  const first = visible.length === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const last = Math.min(currentPage * pageSize, visible.length);
  const filtered = JSON.stringify(filters) !== JSON.stringify(EMPTY_FILTERS);

  /** Any change to what is listed starts again from page 1. */
  const updateFilters = (patch: Partial<CatalogueFilters>) => {
    setFilters((current) => ({ ...current, ...patch }));
    setPage(1);
  };

  const handleSave = async (values: CatalogueFormValues) => {
    const body = formToRequest(values);
    const existing = editing?.item;
    const saved = existing
      ? await updateSmartphone(existing.id, body)
      : await createSmartphone(body);
    setItems((current) =>
      existing
        ? (current ?? []).map((i) => (i.id === saved.id ? saved : i))
        : [...(current ?? []), saved]
    );
    setEditing(null);
    pushToast(
      existing ? "Entry updated" : "Entry added",
      `${saved.brand} ${saved.modelName} was saved to the catalogue.`
    );
  };

  const handleDelete = async () => {
    if (!deleting) return;
    setDeletePending(true);
    setDeleteError(null);
    try {
      await deleteSmartphone(deleting.id);
      setItems((current) => (current ?? []).filter((i) => i.id !== deleting.id));
      pushToast("Entry deleted", `${deleting.brand} ${deleting.modelName} was removed.`, "🗑");
      setDeleting(null);
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : "Could not delete this entry.");
    } finally {
      setDeletePending(false);
    }
  };

  return (
    <AdminShell
      active="catalogue"
      title="Smartphone catalogue"
      email={account?.email ?? "administrator"}
    >
      <div
        className={`mx-auto grid gap-5 ${showTables ? "max-w-[1500px] grid-cols-[minmax(0,1fr)_360px] max-[1180px]:grid-cols-1" : "max-w-[1180px] grid-cols-1"}`}
      >
        <div className="min-w-0">
          <PageHeader
            eyebrow="Tech Advisor · Admin"
            title="Smartphone catalogue"
            description="Search, sort and filter every catalogue entry, then add, edit or delete it."
            action={
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  variant="ghost"
                  aria-pressed={showTables}
                  onClick={() => setShowTables((open) => !open)}
                >
                  {showTables ? "Hide visualisation of all tables" : "Show visualisation of all tables"}
                </Button>
                <Button variant="primary" onClick={() => setEditing({})}>
                  + Add smartphone
                </Button>
              </div>
            }
          />

          <Card>
            <div className="card-body gap-0 p-[18px]">
              <div className="grid grid-cols-[minmax(0,2fr)_repeat(5,minmax(0,1fr))] gap-x-3 max-[980px]:grid-cols-2 max-[620px]:grid-cols-1">
                <Field label="Search">
                  <Input
                    type="search"
                    placeholder="Brand, model, chipset, OS or ID"
                    value={filters.query}
                    onChange={(e) => updateFilters({ query: e.target.value })}
                  />
                </Field>
                <Field label="Brand">
                  <Select
                    value={filters.brand}
                    options={[{ value: ALL, label: "All brands" }, ...choices.brands]}
                    onChange={(e) => updateFilters({ brand: e.target.value })}
                  />
                </Field>
                <Field label="Status">
                  <Select
                    value={filters.status}
                    options={[{ value: ALL, label: "Any status" }, ...CATALOGUE_STATUSES]}
                    onChange={(e) => updateFilters({ status: e.target.value })}
                  />
                </Field>
                <Field label="Operating system">
                  <Select
                    value={filters.osFamily}
                    options={[{ value: ALL, label: "Any OS" }, ...choices.osFamilies]}
                    onChange={(e) => updateFilters({ osFamily: e.target.value })}
                  />
                </Field>
                <Field label="Specifications">
                  <Select
                    value={filters.specs}
                    options={[
                      { value: "", label: "Any" },
                      { value: "complete", label: "Complete" },
                      { value: "missing", label: "Missing some" },
                    ]}
                    onChange={(e) => updateFilters({ specs: e.target.value as SpecCompleteness })}
                  />
                </Field>
                <Field label="Price">
                  <Select
                    value={filters.price}
                    options={[
                      { value: "", label: "Any" },
                      { value: "priced", label: "Has a price" },
                      { value: "unpriced", label: "No price" },
                    ]}
                    onChange={(e) => updateFilters({ price: e.target.value as PriceAvailability })}
                  />
                </Field>
              </div>
              <div className="flex flex-wrap items-end justify-between gap-3">
                <div className="flex flex-wrap items-end gap-3">
                  <Field label="Price range">
                    <Select
                      value={priceRangeValue(filters)}
                      options={[
                        { value: ANY_PRICE_RANGE, label: "Any price" },
                        ...PRICE_RANGES,
                        { value: CUSTOM_PRICE_RANGE, label: "Custom" },
                      ]}
                      onChange={(e) => updateFilters(boundsForRange(e.target.value, filters))}
                    />
                  </Field>
                  <Field label="Min price">
                    <Input
                      type="number"
                      inputMode="decimal"
                      min={0}
                      step="any"
                      placeholder="No min"
                      className="w-[110px]"
                      value={filters.minPrice}
                      onChange={(e) => updateFilters({ minPrice: e.target.value })}
                    />
                  </Field>
                  <Field label="Max price">
                    <Input
                      type="number"
                      inputMode="decimal"
                      min={0}
                      step="any"
                      placeholder="No max"
                      className="w-[110px]"
                      value={filters.maxPrice}
                      onChange={(e) => updateFilters({ maxPrice: e.target.value })}
                    />
                  </Field>
                  <Field label="Sort by">
                    <Select
                      value={sort}
                      options={SORT_OPTIONS}
                      onChange={(e) => {
                        setSort(e.target.value);
                        setPage(1);
                      }}
                    />
                  </Field>
                  <Field label="Per page">
                    <Select
                      value={String(pageSize)}
                      options={PAGE_SIZES.map(String)}
                      onChange={(e) => {
                        setPageSize(Number(e.target.value));
                        setPage(1);
                      }}
                    />
                  </Field>
                </div>
                {filtered && (
                  <div className="mb-[14px]">
                    <Button size="sm" variant="ghost" onClick={() => updateFilters(EMPTY_FILTERS)}>
                      Clear filters
                    </Button>
                  </div>
                )}
              </div>

              {loadError && <Callout>{loadError}</Callout>}
              {!items && !loadError && <LoadingBlock label="Loading catalogue…" />}

              {items && visible.length === 0 && (
                <EmptyState
                  icon="▣"
                  title={items.length === 0 ? "The catalogue is empty" : "No entries match"}
                  description={
                    items.length === 0
                      ? "Add a smartphone, or run MobileAPI ingestion to populate it."
                      : "Try a different search or clear the filters."
                  }
                />
              )}

              {items && visible.length > 0 && (
                <>
                  <p className="m-0 mb-2 text-[12px] text-[#8fa0b8]" aria-live="polite">
                    Showing {first}–{last} of {visible.length}
                    {visible.length !== items.length && ` (filtered from ${items.length})`}
                  </p>
                  <div className="overflow-x-auto">
                    <table className="w-full text-[13px]">
                      <thead className="text-[#8fa0b8]">
                        <tr>
                          <th className={head}>ID</th>
                          <th className={head}>Brand</th>
                          <th className={head}>Model</th>
                          <th className={head}>Release</th>
                          <th className={head}>Status</th>
                          <th className={head}>Price</th>
                          <th className={head}>Chipset</th>
                          <th className={head}>RAM</th>
                          <th className={head}>Storage</th>
                          <th className={head}>Battery</th>
                          <th className={head}>OS</th>
                          <th className={`${head} text-right`}>Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {rows.map((item) => {
                          const missing = missingSpecCount(item);
                          return (
                            <tr key={item.id} className="hover:bg-white/[0.02]">
                              <td className={`${cell} text-[#8fa0b8]`}>{item.id}</td>
                              <td className={cell}>{item.brand}</td>
                              <td className={cell}>
                                <span className="font-semibold text-[#eef5ff]">{item.modelName}</span>
                                {missing > 0 && (
                                  <small className="block text-[11px] text-[#f2d79b]">
                                    {missing} spec{missing === 1 ? "" : "s"} missing
                                  </small>
                                )}
                              </td>
                              <td className={cell}>{value(item.releaseDate)}</td>
                              <td className={cell}>
                                <span
                                  className={`badge badge-sm border-0 text-[10px] font-bold ${item.status === "VERIFIED" ? "bg-[#2ed39a]/15 text-[#67e5b5]" : "bg-[#f5b942]/15 text-[#f5d27a]"}`}
                                >
                                  {item.status}
                                </span>
                              </td>
                              <td className={`${cell} whitespace-nowrap`}>
                                {value(catalogueItemPrice(item))}
                              </td>
                              <td className={cell}>{value(item.chipset)}</td>
                              <td className={cell}>{value(item.ramGb, " GB")}</td>
                              <td className={cell}>{value(item.storageGb, " GB")}</td>
                              <td className={cell}>{value(item.batteryMah, " mAh")}</td>
                              <td className={cell}>{value(item.os)}</td>
                              <td className={`${cell} text-right`}>
                                <span className="inline-flex gap-1">
                                  <Button
                                    size="sm"
                                    aria-label={`Edit ${item.brand} ${item.modelName}`}
                                    onClick={() => setEditing({ item })}
                                  >
                                    Edit
                                  </Button>
                                  <Button
                                    size="sm"
                                    variant="danger"
                                    aria-label={`Delete ${item.brand} ${item.modelName}`}
                                    onClick={() => {
                                      setDeleteError(null);
                                      setDeleting(item);
                                    }}
                                  >
                                    Delete
                                  </Button>
                                </span>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                  {totalPages > 1 && (
                    <div className="mt-4 flex justify-center">
                      <Pagination page={currentPage} total={totalPages} onPage={setPage} />
                    </div>
                  )}
                </>
              )}
            </div>
          </Card>
        </div>

        {showTables && <DatabaseTablesPanel onClose={() => setShowTables(false)} />}
      </div>

      {editing && (
        <CatalogueEntryModal
          item={editing.item}
          onClose={() => setEditing(null)}
          onSave={handleSave}
        />
      )}

      {deleting && (
        <Modal
          eyebrow="Delete catalogue entry"
          title={`Delete ${deleting.brand} ${deleting.modelName}?`}
          onClose={() => setDeleting(null)}
        >
          <p className="m-0 mb-3 text-[14px] leading-[1.55] text-[#c6d1df]">
            This permanently deletes the product and its specifications. Its prices,
            reviews and recommendations are deleted too, and devices linked to it are
            unlinked. This cannot be undone.
          </p>
          <FormError message={deleteError} />
          <ModalActions>
            <Button variant="ghost" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button variant="danger" loading={deletePending} onClick={() => void handleDelete()}>
              Delete entry
            </Button>
          </ModalActions>
        </Modal>
      )}

      <ToastStack toasts={toasts} />
    </AdminShell>
  );
}
