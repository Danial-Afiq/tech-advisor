import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { SpecComparison } from "./SpecComparison";

const props = {
  currentName: "Current phone",
  candidateName: "Suggested phone",
  current: { ramGb: 4 },
  candidate: { ramGb: 8 },
};

describe("SpecComparison", () => {
  it("keeps the existing Compare specs interaction", async () => {
    render(<SpecComparison {...props} />);

    await userEvent.setup().click(
      screen.getByRole("button", { name: /Compare specs/ }),
    );

    expect(
      screen.getByRole("heading", { name: "Current phone vs Suggested phone" }),
    ).toBeInTheDocument();
  });
});
