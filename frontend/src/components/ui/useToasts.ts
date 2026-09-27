import { useCallback, useState } from "react";
import type { Toast } from "./ToastStack";

/** Toast state for `ToastStack`; each toast removes itself after `durationMs`. */
export function useToasts(durationMs = 4200) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const pushToast = useCallback(
    (title: string, text: string, icon = "✓") => {
      const id = Math.random().toString(36).slice(2, 9);
      setToasts((current) => [...current, { id, title, text, icon }]);
      window.setTimeout(
        () => setToasts((current) => current.filter((t) => t.id !== id)),
        durationMs
      );
    },
    [durationMs]
  );

  return { toasts, pushToast };
}
