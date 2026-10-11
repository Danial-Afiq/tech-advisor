"""Service contract. Section 6 of the LLM layer specification.

Spring Boot owns every number in the request; nothing here is recomputed.
"""

from datetime import date
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, model_validator

from app.factors import (
    BRAND_FLEXIBILITIES,
    CONDITIONS,
    FACTORS,
    GRADE_INSUFFICIENT,
    GRADES,
    STANCES,
    UPGRADE_URGENCIES,
    VERDICTS,
)

Factor = Literal[FACTORS]  # type: ignore[valid-type]
Stance = Literal[STANCES]  # type: ignore[valid-type]
Grade = Literal[GRADES]  # type: ignore[valid-type]
ReturnedGrade = Literal[GRADES + (GRADE_INSUFFICIENT,)]  # type: ignore[valid-type]
Verdict = Literal[VERDICTS]  # type: ignore[valid-type]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class StrictOutput(Strict):
    model_config = ConfigDict(strict=True)


def _non_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("must contain non-whitespace text")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise ValueError("must contain valid Unicode text") from exc
    return value


# Claude's raw schema API rejects minLength. Keep the constraint in the shared
# runtime model and describe it to providers, rather than causing a format 400.
NonBlankText = Annotated[
    str, Field(description="Non-blank text."), AfterValidator(_non_blank)
]
ChunkId = Annotated[int, Field(gt=0)]
DegradedReason = Literal[
    "RETRIEVAL_FAILED",
    "NO_PASSAGES_RETRIEVED",
    "INSUFFICIENT_RELEVANT_PASSAGES",
    "LLM_CALL_FAILED",
    "VALIDATION_FAILED",
]


# --- Request -------------------------------------------------------------


class OwnedDevice(Strict):
    name: str
    device_age_months: int
    condition: Literal[CONDITIONS]  # type: ignore[valid-type]
    satisfaction_score: int = Field(ge=0, le=100)
    use_cases: list[str] = []


class Preferences(Strict):
    budget: float | None = None
    currency: str | None = None
    upgrade_urgency: Literal[UPGRADE_URGENCIES]  # type: ignore[valid-type]
    brand_flexibility: Literal[BRAND_FLEXIBILITIES]  # type: ignore[valid-type]
    priorities: dict[Factor, int] = {}
    pain_points: str | None = None
    notes: str | None = None


class UserContext(Strict):
    owned_device: OwnedDevice
    preferences: Preferences


class Candidate(Strict):
    product_id: int
    name: str
    release_date: date
    age_days: int


class SpecDelta(Strict):
    current: float | str | None = None
    candidate: float | str | None = None
    delta_pct: float | None = None


class Price(Strict):
    current: float | None = None
    currency: str | None = None
    vs_budget: float | None = None
    change_pct: float | None = None


class TriggerEvent(Strict):
    event_type: str
    title: str
    old_value: dict[str, Any] | None = None
    new_value: dict[str, Any] | None = None


class Computed(Strict):
    """Finished figures. `benchmark_uplift_pct` already has
    `benchmark_results.higher_is_better` applied on the Java side."""

    spec_deltas: dict[str, SpecDelta] = {}
    benchmark_uplift_pct: float | None = None
    price: Price | None = None
    trigger_event: TriggerEvent | None = None


class Analysis(Strict):
    """Channel A's finished judgement (AGENTS.md §7.1), computed in Java.

    `upgrade_score` is the single 0-1 aggregate the verdict tier was derived
    from, where 1.0 is a strong upgrade recommendation and 0.0 is not
    recommended. It is context for the grader, not something to recompute or
    overrule: the model grades owner evidence and may disagree with the
    verdict, which is the point of keeping the two channels separate (§7.2).
    """

    verdict: Verdict
    upgrade_score: float = Field(strict=True, ge=0, le=1)
    deciding_factors: list[Factor] = []


class RetrievalOptions(Strict):
    k: int | None = Field(default=None, strict=True, gt=0)


class AssessRequest(Strict):
    request_id: NonBlankText
    user_context: UserContext
    candidate: Candidate
    computed: Computed = Computed()
    analysis: Analysis
    retrieval: RetrievalOptions = RetrievalOptions()


# --- Response ------------------------------------------------------------


class ModelEvidenceFinding(StrictOutput):
    factor: Factor
    stance: Stance
    supporting_refs: list[str] = Field(min_length=1)
    note: NonBlankText


class ModelAssessment(StrictOutput):
    """The model's entire output, shared by provider schema and validation.

    The verdict remains in the Java-owned request. Confidence maps to
    evidence_grade (A-F); reasoning maps to summary. No defaults may fill in
    missing model fields, and '-' is reserved for the service's fallback.
    """

    evidence_grade: Grade
    evidence_findings: list[ModelEvidenceFinding] = Field(min_length=1)
    irrelevant_refs: list[str]
    summary: NonBlankText


class EvidenceFinding(ModelEvidenceFinding):
    #: `supporting_refs` resolved back to `review_chunks.id`. Spring persists
    #: these, never the refs, which are meaningless outside one request.
    supporting_chunk_ids: list[ChunkId]


class RetrievalMeta(StrictOutput):
    k: int = Field(gt=0)
    chunk_char_cap: int = Field(gt=0)
    vector_store: Literal["local", "pgvector"]
    embedding_dim: int = Field(gt=0)


class ResponseMeta(StrictOutput):
    ai_model: NonBlankText
    prompt_version: NonBlankText
    retrieved_chunk_ids: list[ChunkId]
    retry_count: int = Field(ge=0, le=1)
    #: Retrieval parameters, for `input_snapshot`. Two recommendations sharing
    #: a prompt_version must also share these or reproducibility is lost.
    retrieval: RetrievalMeta
    #: True when no usable grade was produced and '-' is being returned.
    degraded: bool = False
    degraded_reason: DegradedReason | None = None

    @model_validator(mode="after")
    def validate_degraded_reason(self) -> "ResponseMeta":
        if self.degraded != (self.degraded_reason is not None):
            raise ValueError("degraded must agree with degraded_reason")
        return self


class AssessResponse(StrictOutput):
    request_id: NonBlankText
    evidence_grade: ReturnedGrade
    evidence_findings: list[EvidenceFinding] = []
    irrelevant_refs: list[str] = []
    irrelevant_chunk_ids: list[ChunkId] = []
    summary: NonBlankText | None = None
    meta: ResponseMeta
    #: Ready-formed `system_log` row for Spring to persist. Section 10.
    system_log: dict[str, Any] | None = None

    @model_validator(mode="after")
    def validate_assessment_state(self) -> "AssessResponse":
        if self.meta.degraded:
            if (
                self.evidence_grade != GRADE_INSUFFICIENT
                or self.summary is not None
                or self.evidence_findings
                or self.irrelevant_refs
                or self.irrelevant_chunk_ids
                or self.system_log is None
            ):
                raise ValueError("degraded assessments must contain only the unavailable result and failure log")
        elif (
            self.evidence_grade == GRADE_INSUFFICIENT
            or self.summary is None
            or not self.evidence_findings
            or self.system_log is not None
        ):
            raise ValueError("successful assessments require a grade, findings and summary, with no failure log")
        return self
