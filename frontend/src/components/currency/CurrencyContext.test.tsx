import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { CURRENCY_STORAGE_KEY } from "./currency";
import { CurrencyProvider } from "./CurrencyContext";
import { CurrencySelector } from "./CurrencySelector";

function renderSelector() {
  return render(
    <CurrencyProvider>
      <CurrencySelector />
    </CurrencyProvider>
  );
}

describe("currency preference", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("defaults to SGD when there is no saved preference", () => {
    renderSelector();

    expect(
      screen.getByRole("combobox", { name: "Display currency" })
    ).toHaveValue("SGD");
  });

  it("switches immediately and restores the saved selection", async () => {
    const user = userEvent.setup();
    const first = renderSelector();
    const selector = screen.getByRole("combobox", {
      name: "Display currency",
    });

    await user.selectOptions(selector, "GBP");

    expect(selector).toHaveValue("GBP");
    expect(window.localStorage.getItem(CURRENCY_STORAGE_KEY)).toBe("GBP");

    first.unmount();
    renderSelector();
    expect(
      screen.getByRole("combobox", { name: "Display currency" })
    ).toHaveValue("GBP");
  });

  it("ignores an unsupported saved value", () => {
    window.localStorage.setItem(CURRENCY_STORAGE_KEY, "EUR");

    renderSelector();

    expect(
      screen.getByRole("combobox", { name: "Display currency" })
    ).toHaveValue("SGD");
  });
});
