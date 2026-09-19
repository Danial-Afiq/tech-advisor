"""Assessment orchestration. Sections 5 to 8, and the degraded paths in 10.

Retrieve, assemble, call once, validate, map refs back to chunk IDs. Nothing
here computes a verdict, a score or a delta; those arrive finished in the
request and are passed through to the model as trusted context.
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import Settings
from app.factors import GRADE_INSUFFICIENT
from app.llm import Llm, LlmError
from app.prompt import SYSTEM_PROMPT, build_user_prompt, output_schema
from app.retrieval.embedder import Embedder
from app.retrieval.query import build_query
from app.retrieval.store import Chunk, VectorStore
from app.schemas import AssessRequest, AssessResponse, EvidenceFinding, ResponseMeta
from app.validation import ValidationFailure, parse_response, validate

log = logging.getLogger(__name__)

RETRY_INSTRUCTION = (
    "Your previous response was rejected for a structural reason:\n\n%s\n\n"
    "Return a corrected JSON object matching the schema. Change nothing else."
)


class Assessor:
    def __init__(
        self,
        settings: Settings,
        store: VectorStore,
        embedder: Embedder,
        llm: Llm,
    ) -> None:
        self.settings = settings
        self.store = store
        self.embedder = embedder
        self.llm = llm

    async def assess(self, request: AssessRequest) -> AssessResponse:
        k = request.retrieval.k or self.settings.k
        try:
            chunks = self._retrieve(request, k)
        except Exception as exc:  # noqa: BLE001
            # With pgvector, retrieval is a network call and can fail for
            # reasons that have nothing to do with this request: the database
            # is down, the pool is exhausted, the stored embeddings were
            # produced by a different model. Section 10 says the user keeps
            # the verdict and the deterministic analysis when the evidence
            # path fails, so this degrades like any other failure rather than
            # returning a 500 and losing the whole recommendation.
            log.exception("retrieval failed request_id=%s", request.request_id)
            return self._degraded(
                request,
                reason="RETRIEVAL_FAILED",
                message="%s: %s" % (type(exc).__name__, exc),
                retrieved_chunk_ids=[],
                retry_count=0,
            )

        if not chunks:
            # The maturity gate in Java should have caught this before the
            # request was made. It is also the normal state of the stand-in
            # store, which starts empty.
            return self._degraded(
                request,
                reason="NO_PASSAGES_RETRIEVED",
                message="No review passages retrieved for the candidate product",
                retrieved_chunk_ids=[],
                retry_count=0,
            )

        # Short local refs, never raw IDs: long identifiers get garbled, and a
        # garbled ID is indistinguishable from an invented one. With refs,
        # validation is a set-membership check.
        passages = [("P%d" % (index + 1), chunk) for index, chunk in enumerate(chunks)]
        ref_to_chunk = {ref: chunk.chunk_id for ref, chunk in passages}
        retrieved_chunk_ids = [chunk.chunk_id for _, chunk in passages]

        user_prompt = build_user_prompt(request, passages, self.settings)
        messages: list[dict[str, Any]] = [{"role": "user", "content": user_prompt}]

        parsed: dict[str, Any] | None = None
        model_id = self.settings.llm_model
        retry_count = 0
        last_error = ""

        for attempt in range(self.settings.max_retries + 1):
            retry_count = attempt
            try:
                result = await self.llm.complete(
                    SYSTEM_PROMPT, messages, output_schema()
                )
            except LlmError as exc:
                return self._degraded(
                    request,
                    reason="LLM_CALL_FAILED",
                    message=str(exc),
                    retrieved_chunk_ids=retrieved_chunk_ids,
                    retry_count=attempt,
                )

            model_id = result.model
            try:
                parsed = validate(parse_response(result.text), set(ref_to_chunk))
                break
            except ValidationFailure as exc:
                last_error = str(exc)
                log.warning(
                    "validation failed request_id=%s attempt=%d: %s",
                    request.request_id,
                    attempt,
                    last_error,
                )
                if attempt >= self.settings.max_retries:
                    break
                messages = messages + [
                    {"role": "assistant", "content": result.text},
                    {"role": "user", "content": RETRY_INSTRUCTION % last_error},
                ]

        if parsed is None:
            return self._degraded(
                request,
                reason="VALIDATION_FAILED",
                message="Schema validation failed after retry: %s" % last_error,
                retrieved_chunk_ids=retrieved_chunk_ids,
                retry_count=retry_count,
            )

        irrelevant_refs = list(parsed.get("irrelevant_refs", []))
        if len(irrelevant_refs) > self.settings.irrelevant_ref_limit * len(passages):
            # The model found almost nothing that describes this product. It
            # is not allowed to decide it has too little data, so code makes
            # that call here rather than publishing a letter backed by one
            # stray passage.
            return self._degraded(
                request,
                reason="INSUFFICIENT_RELEVANT_PASSAGES",
                message="%d of %d retrieved passages did not describe the candidate"
                % (len(irrelevant_refs), len(passages)),
                retrieved_chunk_ids=retrieved_chunk_ids,
                retry_count=retry_count,
            )

        findings = [
            EvidenceFinding(
                factor=finding["factor"],
                stance=finding["stance"],
                supporting_refs=list(finding["supporting_refs"]),
                supporting_chunk_ids=[
                    ref_to_chunk[ref] for ref in finding["supporting_refs"]
                ],
                note=finding["note"],
            )
            for finding in parsed["evidence_findings"]
        ]

        return AssessResponse(
            request_id=request.request_id,
            evidence_grade=parsed["evidence_grade"],
            evidence_findings=findings,
            irrelevant_refs=irrelevant_refs,
            irrelevant_chunk_ids=[ref_to_chunk[ref] for ref in irrelevant_refs],
            summary=parsed["summary"],
            meta=self._meta(retrieved_chunk_ids, retry_count, model_id),
        )

    def _retrieve(self, request: AssessRequest, k: int) -> list[Chunk]:
        query = build_query(request.user_context)
        query_embedding = self.embedder.embed(query)
        return self.store.search(
            product_id=request.candidate.product_id,
            query_embedding=query_embedding,
            k=k,
        )

    def _meta(
        self, retrieved_chunk_ids: list[int], retry_count: int, model_id: str
    ) -> ResponseMeta:
        return ResponseMeta(
            ai_model=model_id,
            prompt_version=self.settings.prompt_version,
            retrieved_chunk_ids=retrieved_chunk_ids,
            retry_count=retry_count,
            retrieval={
                "k": self.settings.k,
                "chunk_char_cap": self.settings.chunk_char_cap,
                "vector_store": self.settings.vector_store,
                "embedding_dim": self.settings.embedding_dim,
            },
        )

    def _degraded(
        self,
        request: AssessRequest,
        reason: str,
        message: str,
        retrieved_chunk_ids: list[int],
        retry_count: int,
    ) -> AssessResponse:
        """Section 10: the user keeps the verdict, the scores and the
        deterministic factor analysis. The grade becomes '-', which says we
        cannot yet tell - not that the product scored badly.

        Never fabricate a grade or a summary to fill the gap.
        """
        log.warning(
            "degraded assessment request_id=%s reason=%s: %s",
            request.request_id,
            reason,
            message,
        )
        meta = self._meta(retrieved_chunk_ids, retry_count, self.settings.llm_model)
        meta.degraded = True
        meta.degraded_reason = reason
        return AssessResponse(
            request_id=request.request_id,
            evidence_grade=GRADE_INSUFFICIENT,
            evidence_findings=[],
            irrelevant_refs=[],
            irrelevant_chunk_ids=[],
            summary=None,
            meta=meta,
            system_log={
                "component": "recommendation_ai",
                "status": "FAILURE",
                "message": message,
                "metadata": {
                    "request_id": request.request_id,
                    "reason": reason,
                    "retry_count": retry_count,
                    "candidate_product_id": request.candidate.product_id,
                },
            },
        )
