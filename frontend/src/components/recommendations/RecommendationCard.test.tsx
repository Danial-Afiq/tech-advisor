import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { RecommendationCard } from "./RecommendationCard";
import type {
  DashboardRecommendation,
  EvidenceGrade,
  RecommendationVerdict,
} from "./types";

const baseRecommendation: DashboardRecommendation = {
  recommendationId: 1,
  currentDeviceId: 10,
  currentDeviceName: "My iPhone 13",
  candidateProductId: 20,
  candidateBrand: "Samsung",
  candidateModelName: "Galaxy S26",
  latestPrice: 1299,
  currency: "SGD",
  verdict: "STRONG_UPGRADE_CANDIDATE",
  confidence: "A",
  reasoning:
    "This device offers a meaningful battery and performance upgrade.",
  starred: false,
  createdAt: "2026-10-11T00:00:00Z",
};

function recommendation(
  overrides: Partial<DashboardRecommendation> = {}
): DashboardRecommendation {
  return {
    ...baseRecommendation,
    ...overrides,
  };
}

describe("RecommendationCard", () => {
  it("titles the card with the suggested product when the page groups by device", () => {
    render(
      <RecommendationCard recommendation={recommendation()} showDevice={false}>
        <p>Extra panel</p>
      </RecommendationCard>
    );

    expect(screen.getByRole("heading", { name: "Samsung Galaxy S26" })).toBeInTheDocument();
    expect(screen.queryByText("My iPhone 13")).not.toBeInTheDocument();
    expect(screen.getByText(/1,299\.00/)).toBeInTheDocument();
    expect(screen.getByText("Extra panel")).toBeInTheDocument();
    // No image yet: the same phone icon a broken image falls back to.
    expect(screen.getByText("📱")).toBeInTheDocument();
  });

  it("shows the owned device and suggested product", () => {
    render(
      <RecommendationCard
        recommendation={recommendation()}
      />
    );

    expect(
      screen.getByText("My iPhone 13")
    ).toBeInTheDocument();

    expect(
      screen.getByText("Samsung Galaxy S26")
    ).toBeInTheDocument();

    expect(
      screen.getByText(/1,299\.00/)
    ).toBeInTheDocument();

    expect(
      screen.getByText("Indicative price")
    ).toBeInTheDocument();
  });

  it("shows the main reasoning directly in the card", () => {
    render(
      <RecommendationCard
        recommendation={recommendation()}
      />
    );

    expect(
      screen.getByText(
        "This device offers a meaningful battery and performance upgrade."
      )
    ).toBeInTheDocument();
  });

  it.each<
    [
      RecommendationVerdict,
      string,
      string,
    ]
  >([
    [
      "NO_MEANINGFUL_CHANGE",
      "No meaningful change",
      "badge-success",
    ],
    [
      "WORTH_WATCHING",
      "Worth watching",
      "badge-info",
    ],
    [
      "WORTH_CONSIDERING",
      "Worth considering",
      "badge-warning",
    ],
    [
      "STRONG_UPGRADE_CANDIDATE",
      "Strong upgrade candidate",
      "badge-error",
    ],
  ])(
    "presents %s with its readable label and visual style",
    (verdict, label, badgeClass) => {
      render(
        <RecommendationCard
          recommendation={recommendation({
            verdict,
          })}
        />
      );

      expect(
        screen.getByText(label)
      ).toHaveClass(badgeClass);
    }
  );

  it.each<EvidenceGrade>([
    "A",
    "B",
    "C",
    "D",
    "E",
    "F",
  ])(
    "labels evidence grade %s separately from the verdict",
    (grade) => {
      render(
        <RecommendationCard
          recommendation={recommendation({
            confidence: grade,
          })}
        />
      );

      expect(
        screen.getByText(`Owner evidence: ${grade}`)
      ).toBeInTheDocument();
    }
  );

  it("explains when there is not enough evidence", () => {
    render(
      <RecommendationCard
        recommendation={recommendation({
          confidence: "-",
        })}
      />
    );

    expect(
      screen.getByText("Not enough evidence yet")
    ).toBeInTheDocument();

    expect(
      screen.queryByText("-")
    ).not.toBeInTheDocument();
  });

  it("distinguishes a missing evidence grade", () => {
    render(
      <RecommendationCard
        recommendation={recommendation({
          confidence: null,
        })}
      />
    );

    expect(
      screen.getByText("No evidence grade available")
    ).toBeInTheDocument();
  });

  it("handles a missing explanation without inventing one", () => {
    render(
      <RecommendationCard
        recommendation={recommendation({
          reasoning: null,
        })}
      />
    );

    expect(
      screen.getByText(
        "No explanation is available for this recommendation."
      )
    ).toBeInTheDocument();
  });

  it("shows a fallback when the price is unavailable", () => {
    render(
        <RecommendationCard
        recommendation={recommendation({
            latestPrice: null,
            currency: null,
        })}
        />
    );

    expect(
        screen.getByText("Price unavailable")
    ).toBeInTheDocument();
    });

  it("does not guess a currency for an amount without a valid ISO code", () => {
    render(
      <RecommendationCard
        recommendation={recommendation({
          latestPrice: 99,
          currency: null,
          priceConversionStatus: "UNAVAILABLE",
        })}
      />
    );

    expect(
      screen.getByText("99.00 (currency unavailable)")
    ).toBeInTheDocument();
    expect(screen.queryByText(/SGD/)).not.toBeInTheDocument();
  });

  it("shows stale and unavailable conversion fallbacks", () => {
    const { rerender } = render(
      <RecommendationCard
        recommendation={recommendation({
          priceConversionStatus: "STALE",
          exchangeRateDate: "2026-10-19",
        })}
      />
    );

    expect(
      screen.getByText(/Using stale exchange rates dated/)
    ).toBeInTheDocument();

    rerender(
      <RecommendationCard
        recommendation={recommendation({
          priceConversionStatus: "UNAVAILABLE",
          currency: "USD",
        })}
      />
    );

    expect(
      screen.getByText(
        "Currency conversion is unavailable; showing the original price."
      )
    ).toBeInTheDocument();
    expect(screen.getByText(/USD\s*1,299\.00/)).toBeInTheDocument();
  });

  it("has no star control unless the caller handles it", () => {
    render(<RecommendationCard recommendation={recommendation()} />);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("shows the star as a toggle reflecting the saved state", async () => {
    const onToggleStar = vi.fn();
    const saved = recommendation({ starred: true });
    render(<RecommendationCard recommendation={saved} onToggleStar={onToggleStar} />);

    const star = screen.getByRole("button", {
      name: "Remove Samsung Galaxy S26 for My iPhone 13",
    });
    expect(star).toHaveAttribute("aria-pressed", "true");

    await userEvent.setup().click(star);
    expect(onToggleStar).toHaveBeenCalledWith(saved);
  });

  it("disables the star while its request runs", () => {
    render(
      <RecommendationCard
        recommendation={recommendation()}
        onToggleStar={vi.fn()}
        starPending
      />
    );

    expect(
      screen.getByRole("button", { name: "Save Samsung Galaxy S26 for My iPhone 13" })
    ).toBeDisabled();
  });

  it("asks the dashboard to dismiss the selected recommendation", async () => {
    const user = userEvent.setup();
    const onDismiss = vi.fn();
    const selectedRecommendation = recommendation();

    render(
      <RecommendationCard
        recommendation={selectedRecommendation}
        onDismiss={onDismiss}
      />
    );

    await user.click(
      screen.getByRole("button", {
        name: "Not interested",
      })
    );

    expect(onDismiss).toHaveBeenCalledOnce();
    expect(onDismiss).toHaveBeenCalledWith(
      selectedRecommendation
    );
  });
});
