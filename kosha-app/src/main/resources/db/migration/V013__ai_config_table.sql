-- V013: Persistent AI configuration table

CREATE TABLE public.ai_config (
    id              VARCHAR(50) PRIMARY KEY DEFAULT 'default',
    llm_provider    VARCHAR(50) NOT NULL DEFAULT 'ollama',
    llm_endpoint    VARCHAR(500) NOT NULL DEFAULT 'http://localhost:11434',
    llm_model       VARCHAR(200) NOT NULL DEFAULT 'llama3:8b',
    llm_api_key     VARCHAR(1000),  -- stored encrypted in production
    summarization_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    keyword_extraction_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    classification_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    relationship_detection_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ocr_enabled                 BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed with defaults
INSERT INTO public.ai_config (id) VALUES ('default');
