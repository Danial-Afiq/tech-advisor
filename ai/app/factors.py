"""Closed vocabularies shared by the prompt, the validator and the schema.

The factor list must stay identical to the key vocabulary permitted in
`device_preferences.priorities`. If the two drift, the grade gets scoped to
factors the user never expressed a view on.
"""

FACTORS: tuple[str, ...] = (
    "battery",
    "camera",
    "performance",
    "display",
    "build_quality",
    "thermals",
    "software_support",
    "connectivity",
    "audio",
    "value",
    "longevity",
    "portability",
)

STANCES: tuple[str, ...] = ("POSITIVE", "NEGATIVE", "MIXED")

GRADES: tuple[str, ...] = ("A", "B", "C", "D", "E", "F")

#: Written to `recommendations.confidence` when we cannot say. Not a low grade.
GRADE_INSUFFICIENT = "-"

VERDICTS: tuple[str, ...] = (
    "NO_MEANINGFUL_CHANGE",
    "WORTH_WATCHING",
    "WORTH_CONSIDERING",
    "STRONG_UPGRADE_CANDIDATE",
)

CONDITIONS: tuple[str, ...] = ("EXCELLENT", "GOOD", "FAIR", "POOR")

UPGRADE_URGENCIES: tuple[str, ...] = (
    "NOT_URGENT",
    "SOMEWHAT_URGENT",
    "URGENT",
    "CRITICAL",
)

BRAND_FLEXIBILITIES: tuple[str, ...] = (
    "EXTREMELY_FLEXIBLE",
    "FLEXIBLE",
    "SOMEWHAT_FLEXIBLE",
    "NOT_FLEXIBLE",
)
