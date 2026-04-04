from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    nats_url: str = "nats://localhost:4222"
    kosha_api_url: str = "http://localhost:8080"

    # LLM
    llm_provider: str = "ollama"  # ollama | openai | anthropic
    llm_endpoint: str = "http://localhost:11434"
    llm_model: str = "llama3:8b"
    llm_api_key: str = ""

    # Processing
    tesseract_lang: str = "eng"
    spacy_model: str = "en_core_web_sm"
    max_keywords: int = 20
    max_taxonomy_suggestions: int = 5
    summarization_max_tokens: int = 500
    classification_confidence_threshold: float = 0.6

    # MinIO (for fetching files directly)
    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = "minioadmin"
    minio_secret_key: str = "minioadmin"
    minio_bucket: str = "kosha-vault"
    minio_secure: bool = False

    model_config = {"env_prefix": "KOSHA_AI_"}


settings = Settings()
