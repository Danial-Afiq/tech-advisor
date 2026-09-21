"""Provider selection and the OpenAI-compatible adapter.

These exercise the request-building and response-handling code that the rest
of the suite skips entirely, because elsewhere the whole Llm implementation is
replaced by FakeLlm. A stub chat-completions client stands in for the network,
so there are still no live calls and no key is needed.

What they do NOT establish: that any real provider accepts these requests.
That needs one live call.
"""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any

import httpx
import openai
import pytest

from app.config import Settings
from app.llm import (
    AnthropicLlm,
    LlmError,
    OpenAICompatibleLlm,
    build_llm,
)
from app.prompt import output_schema


def bad_request(message: str) -> openai.BadRequestError:
    request = httpx.Request("POST", "https://example.invalid/v1/chat/completions")
    return openai.BadRequestError(
        message, response=httpx.Response(400, request=request), body=None
    )


def completion(
    content: str = '{"ok": true}',
    *,
    finish_reason: str = "stop",
    refusal: str | None = None,
    model: str = "some/model",
) -> Any:
    message = SimpleNamespace(content=content, refusal=refusal)
    choice = SimpleNamespace(message=message, finish_reason=finish_reason)
    return SimpleNamespace(choices=[choice], model=model)


class StubCompletions:
    """Records payloads and replays scripted results, like tests/conftest's
    FakeLlm but one layer lower - at the wire format rather than the seam."""

    def __init__(self, results: list[Any]) -> None:
        self.results = list(results)
        self.payloads: list[dict[str, Any]] = []

    async def create(self, **payload: Any) -> Any:
        self.payloads.append(payload)
        if not self.results:
            raise AssertionError("stub called more times than scripted")
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class StubClient:
    def __init__(self, results: list[Any]) -> None:
        self.completions = StubCompletions(results)
        self.chat = SimpleNamespace(completions=self.completions)


def make(settings: Settings, results: list[Any]) -> tuple[OpenAICompatibleLlm, StubClient]:
    client = StubClient(results)
    return OpenAICompatibleLlm(settings, client=client), client


@pytest.fixture
def base(settings: Settings) -> Settings:
    return settings.model_copy(update={"llm_model": "some/model"})


# --- provider selection ------------------------------------------------------


def test_anthropic_is_the_default_provider(base: Settings) -> None:
    assert isinstance(build_llm(base), AnthropicLlm)


def test_openrouter_selects_the_compatible_adapter(base: Settings) -> None:
    llm = build_llm(base.model_copy(update={"llm_provider": "openrouter"}))
    assert isinstance(llm, OpenAICompatibleLlm)
    assert llm.base_url == "https://openrouter.ai/api/v1"


def test_custom_provider_uses_the_configured_base_url(base: Settings) -> None:
    llm = build_llm(
        base.model_copy(
            update={
                "llm_provider": "custom",
                "llm_base_url": "http://localhost:11434/v1",
            }
        )
    )
    assert isinstance(llm, OpenAICompatibleLlm)
    assert llm.base_url == "http://localhost:11434/v1"


def test_custom_provider_without_a_base_url_is_rejected(base: Settings) -> None:
    with pytest.raises(LlmError, match="LLM_BASE_URL"):
        build_llm(base.model_copy(update={"llm_provider": "custom"}))


def test_unknown_provider_is_rejected(base: Settings) -> None:
    with pytest.raises(LlmError, match="LLM_PROVIDER"):
        build_llm(base.model_copy(update={"llm_provider": "hal9000"}))


def test_unknown_schema_mode_is_rejected(base: Settings) -> None:
    with pytest.raises(LlmError, match="LLM_SCHEMA_MODE"):
        OpenAICompatibleLlm(
            base.model_copy(update={"llm_schema_mode": "vibes"}),
            client=StubClient([]),
        )


# --- request shape -----------------------------------------------------------


async def test_schema_mode_sends_a_strict_json_schema(base: Settings) -> None:
    llm, client = make(base, [completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

    payload = client.completions.payloads[0]
    fmt = payload["response_format"]
    assert fmt["type"] == "json_schema"
    assert fmt["json_schema"]["strict"] is True
    assert fmt["json_schema"]["schema"] == output_schema()


async def test_system_goes_first_and_message_order_is_preserved(base: Settings) -> None:
    llm, client = make(base, [completion()])
    history = [
        {"role": "user", "content": "first"},
        {"role": "assistant", "content": "second"},
        {"role": "user", "content": "third"},
    ]
    await llm.complete("SYSTEM", history, output_schema())

    roles = [m["role"] for m in client.completions.payloads[0]["messages"]]
    assert roles == ["system", "user", "assistant", "user"]


async def test_schema_mode_does_not_pollute_the_system_prompt(base: Settings) -> None:
    """When the provider enforces the schema, there is no reason to also beg
    for JSON in the prompt - and doing so wastes cached prefix tokens."""
    llm, client = make(base, [completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

    system = client.completions.payloads[0]["messages"][0]["content"]
    assert system == "SYSTEM"


async def test_weaker_modes_spell_the_schema_out_in_the_prompt(base: Settings) -> None:
    for mode in ("json_object", "prompt"):
        llm, client = make(base.model_copy(update={"llm_schema_mode": mode}), [completion()])
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

        system = client.completions.payloads[0]["messages"][0]["content"]
        assert system.startswith("SYSTEM")
        assert "evidence_grade" in system, mode
        # json_object mode on OpenAI requires the literal word "json".
        assert "json" in system.lower(), mode


async def test_prompt_mode_sends_no_response_format(base: Settings) -> None:
    llm, client = make(base.model_copy(update={"llm_schema_mode": "prompt"}), [completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    assert "response_format" not in client.completions.payloads[0]


async def test_effort_is_opt_in(base: Settings) -> None:
    llm, client = make(base, [completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    assert "reasoning_effort" not in client.completions.payloads[0]

    on = base.model_copy(update={"llm_send_effort": True, "llm_effort": "high"})
    llm, client = make(on, [completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    assert client.completions.payloads[0]["reasoning_effort"] == "high"


# --- the tiered fallback -----------------------------------------------------


async def test_auto_steps_down_when_the_schema_is_refused(base: Settings) -> None:
    llm, client = make(
        base,
        [bad_request("model does not support response_format json_schema"), completion()],
    )
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

    first, second = client.completions.payloads
    assert first["response_format"]["type"] == "json_schema"
    assert second["response_format"] == {"type": "json_object"}


async def test_auto_walks_all_the_way_down_to_prompt(base: Settings) -> None:
    llm, client = make(
        base,
        [
            bad_request("response_format json_schema is not supported"),
            bad_request("response_format json_object is not supported"),
            completion(),
        ],
    )
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

    assert len(client.completions.payloads) == 3
    assert "response_format" not in client.completions.payloads[2]


async def test_the_working_tier_is_remembered(base: Settings) -> None:
    """The step-down costs a wasted request. Paying that on every assessment
    would double the call count for the whole demo."""
    llm, client = make(
        base,
        [
            bad_request("response_format is not supported"),
            completion(),
            completion(),
        ],
    )
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    await llm.complete("SYSTEM", [{"role": "user", "content": "again"}], output_schema())

    assert len(client.completions.payloads) == 3
    # Second assessment starts where the first one landed, not at the top.
    assert client.completions.payloads[2]["response_format"] == {"type": "json_object"}


async def test_a_pinned_mode_never_steps_down(base: Settings) -> None:
    llm, _ = make(
        base.model_copy(update={"llm_schema_mode": "json_schema"}),
        [bad_request("response_format json_schema is not supported")],
    )
    with pytest.raises(LlmError):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())


async def test_an_unrelated_400_does_not_trigger_a_downgrade(base: Settings) -> None:
    """A bad model name must surface, not be mistaken for a format problem and
    quietly retried at a weaker tier."""
    llm, client = make(base, [bad_request("model 'typo/model' not found")])
    with pytest.raises(LlmError, match="not found"):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    assert len(client.completions.payloads) == 1


# --- response handling -------------------------------------------------------


async def test_refusal_becomes_an_llm_error(base: Settings) -> None:
    llm, _ = make(base, [completion(content="", refusal="I can't help with that")])
    with pytest.raises(LlmError, match="declined"):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())


async def test_truncated_output_is_reported_rather_than_parsed(base: Settings) -> None:
    """Truncated JSON would fail validation and burn the retry on a problem no
    retry can fix. Name the real cause instead."""
    llm, _ = make(base, [completion(content='{"evidence_gr', finish_reason="length")])
    with pytest.raises(LlmError, match="LLM_MAX_TOKENS"):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())


async def test_empty_content_is_an_error(base: Settings) -> None:
    llm, _ = make(base, [completion(content="   ")])
    with pytest.raises(LlmError, match="no text content"):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())


async def test_a_gateway_error_without_choices_is_surfaced(base: Settings) -> None:
    """OpenRouter reports some upstream failures as HTTP 200 with no choices."""
    empty = SimpleNamespace(choices=[], error={"message": "upstream timeout"}, model="m")
    llm, _ = make(base, [empty])
    with pytest.raises(LlmError, match="upstream timeout"):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())


async def test_the_served_model_is_reported_not_the_requested_one(base: Settings) -> None:
    """Gateways route and substitute; meta.ai_model must record what actually
    answered, which is what Spring persists."""
    llm, _ = make(base, [completion(model="anthropic/claude-opus-5")])
    result = await llm.complete(
        "SYSTEM", [{"role": "user", "content": "hi"}], output_schema()
    )
    assert result.model == "anthropic/claude-opus-5"
    assert json.loads(result.text) == {"ok": True}


# --- the Anthropic adapter ---------------------------------------------------
#
# Same stub treatment, so the native path is not the one place in the codebase
# where the request shape is asserted only by a code review.


class StubMessages:
    def __init__(self, results: list[Any]) -> None:
        self.results = list(results)
        self.payloads: list[dict[str, Any]] = []

    async def create(self, **payload: Any) -> Any:
        self.payloads.append(payload)
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class StubAnthropic:
    def __init__(self, results: list[Any]) -> None:
        self.messages = StubMessages(results)
        self.beta = SimpleNamespace(messages=self.messages)


def message(
    *blocks: Any, stop_reason: str = "end_turn", model: str = "claude-opus-5"
) -> Any:
    return SimpleNamespace(
        content=list(blocks), stop_reason=stop_reason, stop_details=None, model=model
    )


def text_block(text: str) -> Any:
    return SimpleNamespace(type="text", text=text)


def make_anthropic(settings: Settings, results: list[Any]):
    client = StubAnthropic(results)
    return AnthropicLlm(settings, client=client), client


async def test_anthropic_request_shape(base: Settings) -> None:
    llm, client = make_anthropic(base, [message(text_block('{"ok": true}'))])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

    payload = client.messages.payloads[0]
    assert payload["model"] == base.llm_model
    assert payload["max_tokens"] == base.llm_max_tokens
    # System is a block list carrying the cache breakpoint, not a bare string.
    assert payload["system"][0]["text"] == "SYSTEM"
    assert payload["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert payload["output_config"]["effort"] == base.llm_effort
    assert payload["output_config"]["format"] == {
        "type": "json_schema",
        "schema": output_schema(),
    }
    # No sampling parameters: they are a 400 on this model.
    assert "temperature" not in payload and "top_p" not in payload


async def test_anthropic_fallbacks_are_toggleable(base: Settings) -> None:
    on = base.model_copy(update={"llm_fallbacks_enabled": True})
    llm, client = make_anthropic(on, [message(text_block("{}"))])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    payload = client.messages.payloads[0]
    assert payload["fallbacks"] == "default"
    assert payload["betas"] == ["server-side-fallback-2026-07-01"]

    off = base.model_copy(update={"llm_fallbacks_enabled": False})
    llm, client = make_anthropic(off, [message(text_block("{}"))])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    assert "fallbacks" not in client.messages.payloads[0]
    assert "betas" not in client.messages.payloads[0]


async def test_anthropic_refusal_becomes_an_llm_error(base: Settings) -> None:
    llm, _ = make_anthropic(base, [message(stop_reason="refusal")])
    with pytest.raises(LlmError, match="declined"):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())


async def test_anthropic_ignores_non_text_blocks(base: Settings) -> None:
    """Thinking blocks arrive alongside the answer and must not land in the
    JSON handed to the parser."""
    thinking = SimpleNamespace(type="thinking", thinking="deliberating")
    llm, _ = make_anthropic(base, [message(thinking, text_block('{"ok": true}'))])
    result = await llm.complete(
        "SYSTEM", [{"role": "user", "content": "hi"}], output_schema()
    )
    assert json.loads(result.text) == {"ok": True}


async def test_anthropic_empty_content_is_an_error(base: Settings) -> None:
    llm, _ = make_anthropic(base, [message(text_block("  "))])
    with pytest.raises(LlmError, match="no text content"):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())


# --- the token-ceiling parameter ---------------------------------------------
#
# OpenAI reasoning models reject `max_tokens` and demand
# `max_completion_tokens`; most other vendors only know `max_tokens`.

TOKEN_REJECTION = (
    "Unsupported parameter: 'max_tokens' is not supported with this model. "
    "Use 'max_completion_tokens' instead."
)


async def test_max_tokens_is_the_default_spelling(base: Settings) -> None:
    llm, client = make(base, [completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    payload = client.completions.payloads[0]
    assert payload["max_tokens"] == base.llm_max_tokens
    assert "max_completion_tokens" not in payload


async def test_the_other_spelling_is_adopted_on_rejection(base: Settings) -> None:
    llm, client = make(base, [bad_request(TOKEN_REJECTION), completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

    first, second = client.completions.payloads
    assert "max_tokens" in first
    assert second["max_completion_tokens"] == base.llm_max_tokens
    assert "max_tokens" not in second


async def test_a_token_rejection_does_not_downgrade_the_schema(base: Settings) -> None:
    """The regression this guards: the message contains 'Unsupported
    parameter', which the format-rejection check also matches. Handled in the
    wrong order, a token-name complaint silently costs strict schema
    enforcement for the rest of the process."""
    llm, client = make(base, [bad_request(TOKEN_REJECTION), completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())

    second = client.completions.payloads[1]
    assert second["response_format"]["type"] == "json_schema"


async def test_the_working_token_parameter_is_remembered(base: Settings) -> None:
    llm, client = make(base, [bad_request(TOKEN_REJECTION), completion(), completion()])
    await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    await llm.complete("SYSTEM", [{"role": "user", "content": "again"}], output_schema())

    assert len(client.completions.payloads) == 3
    assert "max_completion_tokens" in client.completions.payloads[2]


async def test_the_token_swap_is_attempted_only_once(base: Settings) -> None:
    """Otherwise a model that rejects both spellings loops forever."""
    llm, client = make(base, [bad_request(TOKEN_REJECTION), bad_request(TOKEN_REJECTION)])
    with pytest.raises(LlmError):
        await llm.complete("SYSTEM", [{"role": "user", "content": "hi"}], output_schema())
    assert len(client.completions.payloads) == 2
