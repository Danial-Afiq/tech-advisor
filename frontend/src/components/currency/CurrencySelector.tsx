import { Select } from "../ui/Form";
import {
  SUPPORTED_CURRENCIES,
  useCurrency,
  type Currency,
} from "./CurrencyContext";

export function CurrencySelector() {
  const { currency, setCurrency } = useCurrency();

  return (
    <label className="flex items-center gap-[8px] text-[12px] font-semibold text-[#c6d1df]">
      <span className="max-[620px]:sr-only">Currency</span>
      <Select
        aria-label="Display currency"
        className="w-[88px]"
        options={SUPPORTED_CURRENCIES}
        value={currency}
        onChange={(event) => setCurrency(event.target.value as Currency)}
      />
    </label>
  );
}
