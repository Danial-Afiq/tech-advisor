import { useState, type ReactNode } from "react";
import {
  CURRENCY_STORAGE_KEY,
  CurrencyContextValue,
  storedCurrency,
  type Currency,
} from "./currency";

export function CurrencyProvider({ children }: { children: ReactNode }) {
  const [currency, setCurrencyState] = useState<Currency>(storedCurrency);

  const setCurrency = (next: Currency) => {
    setCurrencyState(next);
    try {
      window.localStorage.setItem(CURRENCY_STORAGE_KEY, next);
    } catch {
      // The in-memory selection still works when browser storage is unavailable.
    }
  };

  return (
    <CurrencyContextValue.Provider value={{ currency, setCurrency }}>
      {children}
    </CurrencyContextValue.Provider>
  );
}
