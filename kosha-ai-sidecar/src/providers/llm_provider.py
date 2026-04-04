"""LLM provider abstraction — returns a LangChain LLM based on config."""

import logging

from langchain_core.language_models import BaseChatModel

from config import settings

logger = logging.getLogger(__name__)


def get_llm() -> BaseChatModel:
    """Return a LangChain chat model based on the configured provider."""
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

        logger.info("Using OpenAI provider: model=%s endpoint=%s", settings.llm_model, settings.llm_endpoint)
        return ChatOpenAI(
            model=settings.llm_model,
            api_key=settings.llm_api_key,
            base_url=settings.llm_endpoint,
            max_tokens=settings.summarization_max_tokens,
            temperature=0.3,
        )

    # Default: Ollama (local)
    from langchain_community.chat_models import ChatOllama

    logger.info("Using Ollama provider: model=%s endpoint=%s", settings.llm_model, settings.llm_endpoint)
    return ChatOllama(
        model=settings.llm_model,
        base_url=settings.llm_endpoint,
        temperature=0.3,
    )
