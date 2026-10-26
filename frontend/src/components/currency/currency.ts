import { createContext, useContext } from "react";

export const SUPPORTED_CURRENCIES = ["SGD", "USD", "MYR", "GBP"] as const;
export type Currency = (typeof SUPPORTED_CURRENCIES)[number];

export const DEFAULT_CURRENCY: Currency = "SGD";
export const CURRENCY_STORAGE_KEY = "tech-advisor-currency";

export function storedCurrency(): Currency {
  try {
    const value = window.localStorage.getItem(CURRENCY_STORAGE_KEY);
    return SUPPORTED_CURRENCIES.includes(value as Currency)
      ? (value as Currency)
      : DEFAULT_CURRENCY;
  } catch {
    return DEFAULT_CURRENCY;
  }
}

export const CurrencyContextValue = createContext<{
  currency: Currency;
  setCurrency: (currency: Currency) => void;
}>({
  currency: DEFAULT_CURRENCY,
  setCurrency: () => undefined,
});

export function useCurrency() {
  return useContext(CurrencyContextValue);
}
