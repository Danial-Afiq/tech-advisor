import { pageButtons } from "./catalogueAdmin";

/** daisyUI `join` of page buttons: Previous, 1 … 4 5 6 … 30, Next. */
export function Pagination({
  page,
  total,
  onPage,
}: {
  page: number;
  total: number;
  onPage: (page: number) => void;
}) {
  const button =
    "btn join-item btn-sm h-auto min-h-0 border-white/[0.09] bg-[#111d31] px-[11px] py-[7px] text-[12px] text-[#c6d2e2] shadow-none hover:bg-[#16243b]";
  return (
    <nav aria-label="Catalogue pages" className="join flex-wrap">
      <button
        type="button"
        className={button}
        disabled={page <= 1}
        onClick={() => onPage(page - 1)}
      >
        ‹ Previous
      </button>
      {pageButtons(page, total).map((p, i) =>
        p === "…" ? (
          <span key={`gap-${i}`} className={`${button} btn-disabled`} aria-hidden="true">
            …
          </span>
        ) : (
          <button
            key={p}
            type="button"
            aria-label={`Page ${p}`}
            aria-current={p === page ? "page" : undefined}
            className={`${button} ${p === page ? "border-[#7c5cff]/60! bg-[#7c5cff]/25! text-[#eef5ff]!" : ""}`}
            onClick={() => onPage(p)}
          >
            {p}
          </button>
        )
      )}
      <button
        type="button"
        className={button}
        disabled={page >= total}
        onClick={() => onPage(page + 1)}
      >
        Next ›
      </button>
    </nav>
  );
}
