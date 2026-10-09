import { useState } from "react";
import type { KeyboardEvent, ReactNode } from "react";
import type { SmartphoneCatalogueItem } from "../../api/catalogue";
import { FieldHint, Input } from "../ui/Form";
import { catalogueName, catalogueSummary } from "./catalogue";

/**
 * Text input with a dropdown of matching catalogue devices (ARIA combobox).
 * Typing stays free text, so a device that isn't in the catalogue can still
 * be entered; picking a suggestion calls `onPick`. Pair with
 * `<Field htmlFor={id}>` so the suggestions stay out of the label.
 */
export function CatalogueSearchInput({
  id,
  value,
  onChange,
  suggestions,
  onPick,
  hint,
  placeholder,
  required,
}: {
  id: string;
  value: string;
  onChange: (value: string) => void;
  /** Already filtered to what matches `value`. */
  suggestions: SmartphoneCatalogueItem[];
  onPick: (item: SmartphoneCatalogueItem) => void;
  /** Muted note under the input, e.g. "No catalogue match". */
  hint?: ReactNode;
  placeholder?: string;
  required?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const listId = `${id}-suggestions`;
  const hintId = `${id}-hint`;
  const showList = open && suggestions.length > 0;
  const activeIndex = Math.min(active, suggestions.length - 1);

  const pick = (item: SmartphoneCatalogueItem) => {
    onPick(item);
    setOpen(false);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!showList) {
        setOpen(true);
        setActive(0);
        return;
      }
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((activeIndex + step + suggestions.length) % suggestions.length);
    } else if (event.key === "Enter" && showList) {
      event.preventDefault(); // pick, don't submit the form
      pick(suggestions[activeIndex]);
    } else if (event.key === "Escape" && showList) {
      event.preventDefault();
      setOpen(false);
    }
  };

  return (
    <div className="flex flex-col gap-[6px]">
      <div className="relative">
        <Input
          id={id}
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listId}
          aria-activedescendant={showList ? `${listId}-${activeIndex}` : undefined}
          aria-describedby={hint ? hintId : undefined}
          autoComplete="off"
          required={required}
          value={value}
          placeholder={placeholder}
          onChange={(e) => {
            onChange(e.target.value);
            setOpen(true);
            setActive(0);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onKeyDown={onKeyDown}
        />
        {showList && (
          <ul
            id={listId}
            role="listbox"
            aria-label="Matching catalogue devices"
            className="absolute left-0 right-0 top-full z-20 m-0 mt-[6px] max-h-[280px] list-none overflow-y-auto rounded-[14px] border border-white/[0.09] bg-[#0d1727] p-[6px] text-left shadow-[0_24px_70px_rgba(0,0,0,.33)]"
          >
            {suggestions.map((item, index) => {
              const summary = catalogueSummary(item);
              return (
                <li
                  key={item.id}
                  id={`${listId}-${index}`}
                  role="option"
                  aria-selected={index === activeIndex}
                  className={`cursor-pointer rounded-[10px] px-[10px] py-[8px] ${index === activeIndex ? "bg-[#16243b]" : ""}`}
                  // Keep focus in the input so blur doesn't close the list first.
                  onMouseDown={(e) => e.preventDefault()}
                  onMouseEnter={() => setActive(index)}
                  onClick={() => pick(item)}
                >
                  <div className="text-[13px] font-semibold text-[#eef5ff]">
                    {catalogueName(item)}
                  </div>
                  {summary && (
                    <div className="text-[12px] text-[#8fa0b8]">{summary}</div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>
      {hint && <FieldHint id={hintId}>{hint}</FieldHint>}
    </div>
  );
}
