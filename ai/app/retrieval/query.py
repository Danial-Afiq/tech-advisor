"""Retrieval query construction. Section 5.1.

There are no factor tags on `review_chunks`, so scoping is entirely semantic.
The query is built from the user's own words and weights, which is what makes
the resulting grade user-scoped rather than product-scoped (section 14).
"""

from app.schemas import UserContext

TOP_PRIORITIES = 3


def top_priorities(priorities: dict[str, int], limit: int = TOP_PRIORITIES) -> list[str]:
    """Highest-weighted factors first. Ties break alphabetically so the same
    preferences always produce the same query."""
    ordered = sorted(priorities.items(), key=lambda kv: (-kv[1], kv[0]))
    return [factor for factor, _ in ordered][:limit]


def build_query(user_context: UserContext) -> str:
    preferences = user_context.preferences
    device = user_context.owned_device

    parts: list[str] = []
    priorities = top_priorities(preferences.priorities)
    if priorities:
        parts.append(", ".join(priority.replace("_", " ") for priority in priorities))
    if preferences.pain_points:
        parts.append("- " + preferences.pain_points.strip())
    if device.use_cases:
        parts.append("used for " + ", ".join(device.use_cases))

    return " ".join(parts).strip()
