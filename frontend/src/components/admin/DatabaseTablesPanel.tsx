import { useEffect, useState } from "react";
import { listDatabaseTables } from "../../api/database";
import type { DatabaseTable } from "../../api/database";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { Callout } from "../ui/Callout";
import { Eyebrow } from "../ui/Eyebrow";
import { LoadingBlock } from "../ui/LoadingBlock";

/**
 * Side panel with one card per database table: its row count and columns,
 * marking primary keys (PK) and foreign keys (→ target table). Loaded from
 * `GET /api/admin/database/tables` each time the panel opens.
 */
export function DatabaseTablesPanel({ onClose }: { onClose: () => void }) {
  const [tables, setTables] = useState<DatabaseTable[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    listDatabaseTables()
      .then((result) => {
        if (!cancelled) {
          setTables(result);
          setError(null);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Could not load the tables.");
      });
    return () => {
      cancelled = true;
    };
  }, [reload]);

  return (
    <aside
      aria-label="Database tables"
      className="flex max-h-[calc(100vh-140px)] flex-col gap-3 overflow-y-auto pr-1 max-[1180px]:max-h-none"
    >
      <div className="flex items-center justify-between gap-2">
        <div>
          <Eyebrow>Database</Eyebrow>
          <h2 className="m-0 text-[17px] font-bold text-[#eef5ff]">
            All tables{tables ? ` (${tables.length})` : ""}
          </h2>
        </div>
        <div className="flex gap-1">
          <Button size="sm" variant="ghost" onClick={() => setReload((n) => n + 1)}>
            Refresh
          </Button>
          <Button size="sm" variant="ghost" aria-label="Hide tables" onClick={onClose}>
            ×
          </Button>
        </div>
      </div>

      {error && <Callout>{error}</Callout>}
      {!tables && !error && <LoadingBlock label="Loading tables…" />}

      {tables?.map((table) => (
        <Card key={table.name} aria-label={`Table ${table.name}`}>
          <div className="card-body gap-2 p-[14px]">
            <div className="flex items-baseline justify-between gap-2">
              <strong className="font-mono text-[13px] text-[#eef5ff]">{table.name}</strong>
              <span className="text-[11px] text-[#8fa0b8]">
                {table.rowCount.toLocaleString()} row{table.rowCount === 1 ? "" : "s"}
              </span>
            </div>
            <ul className="m-0 list-none p-0">
              {table.columns.map((column) => (
                <li
                  key={column.name}
                  className="flex items-center justify-between gap-2 border-t border-white/[0.05] py-[5px] text-[11px]"
                >
                  <span className="flex min-w-0 items-center gap-[6px]">
                    {column.primaryKey && (
                      <span className="rounded-[5px] bg-[#f5b942]/15 px-[5px] text-[9px] font-bold text-[#f5d27a]">
                        PK
                      </span>
                    )}
                    <span className="truncate font-mono text-[#c6d1df]">{column.name}</span>
                    {column.references && (
                      <span className="shrink-0 text-[#b9a6ff]">→ {column.references}</span>
                    )}
                  </span>
                  <span className="shrink-0 text-[#6f819a]">
                    {column.type}
                    {column.nullable ? "" : " · not null"}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </Card>
      ))}
    </aside>
  );
}
