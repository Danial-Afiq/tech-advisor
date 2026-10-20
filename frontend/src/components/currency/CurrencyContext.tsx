import { createContext, useContext, useState, type ReactNode } from "react";

export const SUPPORTED_CURRENCIES = ["SGD", "USD", "MYR", "GBP"] as const;
export type Currency = (typeof SUPPORTED_CURRENCIES)[number];

export const DEFAULT_CURRENCY: Currency = "SGD";
export const CURRENCY_STORAGE_KEY = "tech-advisor-currency";

function isCurrency(value: string | null): value is Currency {
  return SUPPORTED_CURRENCIES.includes(value as Currency);
}

function storedCurrency(): Currency {
  try {
    const value = window.localStorage.getItem(CURRENCY_STORAGE_KEY);
    return isCurrency(value) ? value : DEFAULT_CURRENCY;
  } catch {
    return DEFAULT_CURRENCY;
  }
}

const CurrencyContext = createContext<{
  currency: Currency;
  setCurrency: (currency: Currency) => void;
}>({
  currency: DEFAULT_CURRENCY,
  setCurrency: () => undefined,
});

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
    <CurrencyContext.Provider value={{ currency, setCurrency }}>
      {children}
    </CurrencyContext.Provider>
  );
}

export function useCurrency() {
  return useContext(CurrencyContext);
}
