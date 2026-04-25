-- V035: Switch default LLM to gemma4:26b and add num_ctx column.
-- Rationale: Gemma 4 26B (MoE, 4B active) provides Apache-2.0 licensed,
-- locally-hosted summarisation and classification with quality competitive
-- with hosted models. The new llm_num_ctx column lets admins size Ollama's
-- context window to the deployer's hardware (default 16384 fits typical
-- documents in ~10 GB VRAM headroom).

ALTER TABLE public.ai_config
    ADD COLUMN llm_num_ctx INTEGER NOT NULL DEFAULT 16384;

-- Update the column default so fresh INSERTs without an explicit model
-- get gemma4:26b. (V013 originally seeded llama3:8b.)
ALTER TABLE public.ai_config
    ALTER COLUMN llm_model SET DEFAULT 'gemma4:26b';

-- Migrate existing deployments that are still on the previous default.
-- Customised models are intentionally left untouched.
UPDATE public.ai_config
    SET llm_model = 'gemma4:26b'
    WHERE llm_model = 'llama3:8b';
