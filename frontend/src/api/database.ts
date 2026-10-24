import { apiFetch } from "./client";

/** One column of `GET /api/admin/database/tables` (backend `dto/DatabaseTableResponse.java`). */
export type DatabaseColumn = {
  name: string;
  type: string;
  nullable: boolean;
  primaryKey: boolean;
  /** Target table of a foreign key, else null. */
  references: string | null;
};

export type DatabaseTable = {
  name: string;
  rowCount: number;
  columns: DatabaseColumn[];
};

/** `GET /api/admin/database/tables` — ADMIN only; schema and row counts, never row contents. */
export const listDatabaseTables = () =>
  apiFetch<DatabaseTable[]>("/api/admin/database/tables");
