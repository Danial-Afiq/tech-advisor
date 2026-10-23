import { useState } from "react";
import type { PhoneSpecs } from "../devices/phoneSpecs";
import { Button } from "../ui/Button";
import { Modal } from "../ui/Modal";
import { compareSpecs, specChangeSummary } from "./compareSpecs";
import type { SpecChange } from "./types";

/**
 * The biggest spec changes as one line, and a button that opens a side-by-side
 * table of the owned device's specs against the suggested product's. Arrows
 * show which way a value moved; green/red text (better/worse) comes from the
 * verdict's own comparison (`changes`), never from the UI. Differing text
 * specs have no judgement, so they are only bolded.
 */
export function SpecComparison({
  currentName,
  candidateName,
  current,
  candidate,
  loading = false,
  changes,
}: {
  currentName: string;
  candidateName: string;
  /** The owned device's specs (catalogue + the user's overrides). */
  current?: PhoneSpecs;
  /** The suggested product's catalogue specs. */
  candidate?: PhoneSpecs;
  loading?: boolean;
  /** `specChanges` from the dashboard response. */
  changes?: SpecChange[];
}) {
  const [open, setOpen] = useState(false);
  const groups = compareSpecs(current, candidate, changes);
  const summary = specChangeSummary(changes);
  const differences = groups.reduce(
    (sum, group) => sum + group.rows.filter((row) => row.differs).length,
    0,
  );
  const comparable = current !== undefined && candidate !== undefined;

  let note: string;
  if (loading) note = "loading…";
  else if (!comparable) note = "not available";
  else note = `${differences} difference${differences === 1 ? "" : "s"}`;

  return (
    <>
      {summary.length > 0 && (
        <p className="mt-3 mb-0 grow-0 text-[12px] leading-[1.5] font-semibold text-[#c6d1df]">
          {summary.join(" · ")}
        </p>
      )}

      <Button
        size="sm"
        variant="secondary"
        className="mt-3 self-start"
        disabled={loading || !comparable}
        title={
          !loading && !comparable
            ? "Specifications for one of these phones aren't on file."
            : undefined
        }
        onClick={() => setOpen(true)}
      >
        Compare specs <span className="font-normal text-[#8fa0b8]">· {note}</span>
      </Button>

      {open && (
        <Modal
          eyebrow="Compare specs"
          title={`${currentName} vs ${candidateName}`}
          width="max-w-[720px]"
          onClose={() => setOpen(false)}
        >
          <div className="max-h-[65vh] overflow-y-auto text-[12px]">
            <table className="w-full table-fixed border-collapse text-left">
              <thead>
                <tr className="text-[10px] font-bold tracking-[.12em] text-[#9fb1c9] uppercase">
                  <th scope="col" className="w-[34%] pb-1 font-bold">
                    <span className="sr-only">Specification</span>
                  </th>
                  <th scope="col" className="pb-1 pl-2 font-bold">
                    Your device
                  </th>
                  <th scope="col" className="pb-1 pl-2 font-bold">
                    Suggested
                  </th>
                </tr>
              </thead>
              {groups.map((group) => (
                <tbody key={group.title}>
                  <tr>
                    <th
                      scope="rowgroup"
                      colSpan={3}
                      className="pt-3 pb-1 text-[10px] font-bold tracking-[.12em] text-[#9fb1c9] uppercase"
                    >
                      {group.title}
                    </th>
                  </tr>
                  {group.rows.map((row) => (
                    <tr
                      key={row.label}
                      className={`border-b border-white/[0.09] last:border-b-0 ${
                        row.differs ? "bg-[#7c5cff]/10" : ""
                      }`}
                    >
                      <th
                        scope="row"
                        className="py-[6px] pl-1 align-top font-normal text-[#8fa0b8]"
                      >
                        {row.label}
                      </th>
                      <td className="py-[6px] pl-2 align-top break-words">
                        {row.current ?? "—"}
                      </td>
                      <td
                        className={`py-[6px] pl-2 align-top break-words ${
                          !row.differs
                            ? ""
                            : row.better === undefined
                              ? "font-bold text-[#eef5ff]"
                              : `font-bold ${row.better ? "text-success" : "text-error"}`
                        }`}
                      >
                        {row.candidate ?? "—"}
                        {row.differs && row.better !== undefined && row.rising !== undefined && (
                          <span className="ml-1">
                            <span aria-hidden="true">{row.rising ? "▲" : "▼"}</span>
                            <span className="sr-only">{row.better ? " better" : " worse"}</span>
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              ))}
            </table>
          </div>
        </Modal>
      )}
    </>
  );
}
