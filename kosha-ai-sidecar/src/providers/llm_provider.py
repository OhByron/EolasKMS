"""LLM provider abstraction — returns a chat model based on config."""

import logging
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

import httpx
from langchain_core.messages import AIMessage, BaseMessage, SystemMessage

from config import settings

logger = logging.getLogger(__name__)


class ChatLLM(Protocol):
    """Minimal interface used by EolasKMS pipelines (summarizer, classifier).

    Satisfied by both LangChain `BaseChatModel` and the local `OllamaChat` wrapper.
    """

    async def ainvoke(self, messages: Sequence[BaseMessage]):  # returns object with .content
        ...


@dataclass
class _OllamaResponse:
    content: str


def _to_ollama_role(m: BaseMessage) -> str:
    if isinstance(m, SystemMessage):
        return "system"
    if isinstance(m, AIMessage):
        return "assistant"
    return "user"  # HumanMessage and any other defaults to user


class OllamaChat:
    """Direct httpx client for Ollama's /api/chat endpoint.

    Replaces `langchain_community.chat_models.ChatOllama` because that wrapper:

    1. Does not expose Ollama's `think` flag. Thinking-mode models such as
       gemma4 emit their tokens into `message.thinking` and leave
       `message.content` empty until the thinking budget is exhausted, so
       callers reading `.content` get blank strings.
    2. Does not expose `num_ctx`. Ollama defaults to a small context window
       (4K-8K tokens) and silently truncates longer prompts, which broke
       summarization of long documents on the previous llama3:8b default.
    """

    def __init__(
        self,
        model: str,
        base_url: str,
        num_ctx: int,
        temperature: float = 0.3,
        timeout: float = 600.0,
    ):
        self.model = model
        self.base_url = base_url.rstrip("/")
        self.num_ctx = num_ctx
        self.temperature = temperature
        self.timeout = timeout

    async def ainvoke(self, messages: Sequence[BaseMessage]) -> _OllamaResponse:
        body = {
            "model": self.model,
            "stream": False,
            "think": False,
            "options": {
                "temperature": self.temperature,
                "num_ctx": self.num_ctx,
            },
            "messages": [
                {"role": _to_ollama_role(m), "content": m.content} for m in messages
            ],
        }
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            r = await client.post(f"{self.base_url}/api/chat", json=body)
            r.raise_for_status()
            data = r.json()
        content = data.get("message", {}).get("content", "") or ""
        return _OllamaResponse(content=content)


def get_llm() -> ChatLLM:
    """Return a chat model based on the configured provider."""
    provider = settings.llm_provider.lower()

    if provider == "anthropic":
        from langchain_anthropic import ChatAnthropic

        logger.info("Using Anthropic provider: model=%s", settings.llm_model)
        return ChatAnthropic(
            model=settings.llm_model,
            api_key=settings.llm_api_key,
            max_tokens=settings.summarization_max_tokens,
            temperature=0.3,
        )

    if provider == "openai":
        from langchain_community.chat_models import ChatOpenAI

        logger.info(
            "Using OpenAI provider: model=%s endpoint=%s",
            settings.llm_model, settings.llm_endpoint,
        )
        return ChatOpenAI(
            model=settings.llm_model,
            api_key=settings.llm_api_key,
            base_url=settings.llm_endpoint,
            max_tokens=settings.summarization_max_tokens,
            temperature=0.3,
        )

    # Default: Ollama (local). See OllamaChat docstring for the think/num_ctx rationale.
    logger.info(
        "Using Ollama provider: model=%s endpoint=%s num_ctx=%d",
        settings.llm_model, settings.llm_endpoint, settings.llm_num_ctx,
    )
    return OllamaChat(
        model=settings.llm_model,
        base_url=settings.llm_endpoint,
        num_ctx=settings.llm_num_ctx,
        temperature=0.3,
    )
