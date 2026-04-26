<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { AiConfig, AiStats } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import * as m from '$paraglide/messages';

	let activeTab = $state<'model' | 'prompts' | 'reprocess' | 'stats'>('model');
	let config = $state<AiConfig | null>(null);
	let stats = $state<AiStats | null>(null);
	let loading = $state(true);
	let saving = $state(false);
	let error = $state('');
	let successMsg = $state('');

	// Editable config fields
	let provider = $state('ollama');
	let endpoint = $state('http://localhost:11434');
	let model = $state('gemma4:26b');
	let apiKey = $state('');
	let numCtx = $state(16384);
	let summarization = $state(true);
	let keywords = $state(true);
	let classification = $state(true);
	let relationships = $state(true);
	let ocr = $state(false);

	const needsApiKey = $derived(provider !== 'ollama');
	const isOllama = $derived(provider === 'ollama');

	const providerDefaults: Record<string, { endpoint: string; model: string }> = {
		ollama: { endpoint: 'http://localhost:11434', model: 'gemma4:26b' },
		openai: { endpoint: 'https://api.openai.com/v1', model: 'gpt-4o' },
		anthropic: { endpoint: 'https://api.anthropic.com', model: 'claude-sonnet-4-20250514' }
	};

	function onProviderChange() {
		const defaults = providerDefaults[provider];
		if (defaults) {
			endpoint = defaults.endpoint;
			model = defaults.model;
		}
		if (provider === 'ollama') apiKey = '';
	}

	// Reprocess
	let reprocessScope = $state('all');
	let reprocessing = $state(false);
	let reprocessProgress = $state(0);

	onMount(() => loadConfig());

	async function loadConfig() {
		loading = true;
		error = '';
		try {
			const [cfgRes, statsRes] = await Promise.all([
				api.ai.getConfig(),
				api.ai.getStats()
			]);
			config = cfgRes.data;
			stats = statsRes.data;
			if (config) {
				provider = config.llmProvider;
				endpoint = config.llmEndpoint;
				model = config.llmModel;
				apiKey = config.llmApiKey ?? '';
				numCtx = config.llmNumCtx ?? 16384;
				summarization = config.summarizationEnabled;
				keywords = config.keywordExtractionEnabled;
				classification = config.classificationEnabled;
				relationships = config.relationshipDetectionEnabled;
				ocr = config.ocrEnabled;
			}
		} catch (e: any) {
			if (!e.message?.includes('404')) error = e.message;
		} finally {
			loading = false;
		}
	}

	async function saveConfig() {
		saving = true;
		error = '';
		successMsg = '';
		try {
			await api.ai.updateConfig({
				llmProvider: provider,
				llmEndpoint: endpoint,
				llmModel: model,
				llmApiKey: apiKey || undefined,
				llmNumCtx: numCtx,
				summarizationEnabled: summarization,
				keywordExtractionEnabled: keywords,
				classificationEnabled: classification,
				relationshipDetectionEnabled: relationships,
				ocrEnabled: ocr
			} as any);
			successMsg = m.ai_config_saved();
		} catch (e: any) {
			error = e.message;
		} finally {
			saving = false;
		}
	}

	async function startReprocess() {
		reprocessing = true;
		reprocessProgress = 0;
		error = '';
		successMsg = '';
		try {
			const res = await api.post<{ scope: string; queued: number; skipped: number }>(
				`/api/v1/admin/ai/reprocess?scope=${encodeURIComponent(reprocessScope)}`,
				{},
			);
			const data = res.data;
			successMsg = m.ai_reprocess_queued({
				queued: String(data?.queued ?? 0),
				skipped: String(data?.skipped ?? 0),
			});
		} catch (e: any) {
			error = e.message ?? 'Reprocess failed';
		} finally {
			reprocessing = false;
		}
	}

	const tabs = [
		{ id: 'model' as const, label: m.ai_tab_model() },
		{ id: 'prompts' as const, label: m.ai_tab_prompts() },
		{ id: 'reprocess' as const, label: m.ai_tab_reprocess() },
		{ id: 'stats' as const, label: m.ai_tab_stats() }
	];
</script>

<svelte:head>
	<title>{m.page_title_ai_config()} - {m.nav_sidebar_administration()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.page_title_ai_config()} description={m.ai_desc()} />

{#if error}
	<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
{/if}
{#if successMsg}
	<div role="status" class="mt-4 rounded-md border border-success bg-success/10 p-3 text-sm text-success">{successMsg}</div>
{/if}

<!-- Tabs -->
<div class="mt-6" role="tablist" aria-label={m.ai_config_sections_label()}>
	{#each tabs as tab}
		<button
			role="tab"
			aria-selected={activeTab === tab.id}
			onclick={() => (activeTab = tab.id)}
			class="rounded-t-md px-4 py-2 text-sm font-medium transition focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			class:bg-card={activeTab === tab.id}
			class:border={activeTab === tab.id}
			class:border-b-transparent={activeTab === tab.id}
			class:border-border={activeTab === tab.id}
			class:text-foreground={activeTab === tab.id}
			class:text-muted-foreground={activeTab !== tab.id}
		>
			{tab.label}
		</button>
	{/each}
</div>

<div class="rounded-b-lg rounded-tr-lg border border-border bg-card p-6">
	{#if loading}
		<p aria-live="polite" class="text-muted-foreground">{m.ai_loading_config()}</p>

	{:else if activeTab === 'model'}
		<form onsubmit={(e) => { e.preventDefault(); saveConfig(); }} class="max-w-lg space-y-4">
			<div>
				<label for="ai-provider" class="block text-sm font-medium">{m.ai_provider()}</label>
				<select id="ai-provider" bind:value={provider} onchange={onProviderChange}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
					<option value="ollama">{m.ai_provider_ollama()}</option>
					<option value="openai">{m.ai_provider_openai()}</option>
					<option value="anthropic">{m.ai_provider_anthropic()}</option>
				</select>
				{#if provider === 'ollama'}
					<p class="mt-1 text-xs text-muted-foreground">{m.ai_ollama_hint()}</p>
				{/if}
			</div>
			<div>
				<label for="ai-endpoint" class="block text-sm font-medium">{m.ai_endpoint()}</label>
				<input id="ai-endpoint" type="url" bind:value={endpoint}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
			</div>
			<div>
				<label for="ai-model" class="block text-sm font-medium">{m.ai_model()}</label>
				<input id="ai-model" type="text" bind:value={model}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
				{#if isOllama}
					<p class="mt-1 text-xs text-muted-foreground">{m.ai_ollama_model_hint()}</p>
				{/if}
			</div>
			{#if isOllama}
				<div>
					<label for="ai-num-ctx" class="block text-sm font-medium">{m.ai_num_ctx()}</label>
					<input id="ai-num-ctx" type="number" bind:value={numCtx} min="2048" step="2048"
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
					<p class="mt-1 text-xs text-muted-foreground">{m.ai_num_ctx_hint()}</p>
				</div>
			{/if}
			{#if needsApiKey}
				<div>
					<label for="ai-apikey" class="block text-sm font-medium">
						{m.ai_api_key()} <span class="text-destructive">*</span>
					</label>
					<input
						id="ai-apikey"
						type="password"
						bind:value={apiKey}
						placeholder={provider === 'anthropic' ? 'sk-ant-...' : 'sk-...'}
						autocomplete="off"
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-sm focus:outline-2 focus:outline-ring"
						aria-required="true"
					/>
					<p class="mt-1 text-xs text-muted-foreground">
						{#if provider === 'anthropic'}
							{@html m.ai_api_key_anthropic_hint()}
						{:else}
							{m.ai_api_key_generic_hint()}
						{/if}
					</p>
				</div>
			{/if}
			<fieldset>
				<legend class="text-sm font-medium">{m.ai_capabilities()}</legend>
				<div class="mt-2 space-y-2">
					{#each [
						{ bind: () => summarization, set: (v: boolean) => summarization = v, label: m.ai_cap_summarization() },
						{ bind: () => keywords, set: (v: boolean) => keywords = v, label: m.ai_cap_keywords() },
						{ bind: () => classification, set: (v: boolean) => classification = v, label: m.ai_cap_classification() },
						{ bind: () => relationships, set: (v: boolean) => relationships = v, label: m.ai_cap_relationships() },
						{ bind: () => ocr, set: (v: boolean) => ocr = v, label: m.ai_cap_ocr() }
					] as cap}
						<label class="flex items-center gap-2 text-sm">
							<input type="checkbox" checked={cap.bind()} onchange={(e) => cap.set((e.target as HTMLInputElement).checked)} class="rounded focus:ring-ring" />
							{cap.label}
						</label>
					{/each}
				</div>
			</fieldset>
			<div class="flex gap-3 pt-4">
				<button type="submit" disabled={saving}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
					{saving ? m.btn_saving() : m.ai_save_settings()}
				</button>
			</div>
		</form>

	{:else if activeTab === 'prompts'}
		<div class="max-w-2xl space-y-6">
			<div>
				<label for="prompt-summary" class="block text-sm font-medium">{m.ai_prompt_summary()}</label>
				<p class="text-xs text-muted-foreground">{m.ai_prompt_variables({ vars: '{{document_text}}, {{title}}, {{category}}' })}</p>
				<textarea id="prompt-summary" rows="6"
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-sm focus:outline-2 focus:outline-ring"
					placeholder={m.ai_prompt_summary_placeholder()}
				></textarea>
			</div>
			<div>
				<label for="prompt-keywords" class="block text-sm font-medium">{m.ai_prompt_keywords()}</label>
				<textarea id="prompt-keywords" rows="6"
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-sm focus:outline-2 focus:outline-ring"
					placeholder={m.ai_prompt_keywords_placeholder()}
				></textarea>
			</div>
			<div class="flex gap-3">
				<button class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">{m.ai_reset_defaults()}</button>
				<button class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring">{m.ai_save_prompts()}</button>
			</div>
		</div>

	{:else if activeTab === 'reprocess'}
		<div class="max-w-lg space-y-4">
			<p class="text-sm text-muted-foreground">{m.ai_reprocess_desc()}</p>
			<div>
				<label for="reprocess-scope" class="block text-sm font-medium">{m.ai_reprocess_scope()}</label>
				<select id="reprocess-scope" bind:value={reprocessScope}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
					<option value="all">{m.ai_reprocess_all()}</option>
					<option value="unprocessed">{m.ai_reprocess_unprocessed()}</option>
				</select>
			</div>

			{#if reprocessing}
				<div>
					<div
						class="h-3 overflow-hidden rounded-full bg-muted"
						role="progressbar"
						aria-valuenow={reprocessProgress}
						aria-valuemin={0}
						aria-valuemax={100}
						aria-label={m.ai_reprocess_progress_label()}
					>
						<div class="h-full rounded-full bg-primary transition-all duration-300" style="width: {reprocessProgress}%"></div>
					</div>
					<p class="mt-1 text-sm text-muted-foreground" aria-live="polite">{m.ai_reprocess_progress({ pct: String(reprocessProgress) })}</p>
				</div>
			{:else}
				<button onclick={startReprocess}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring">
					{m.ai_reprocess_start()}
				</button>
			{/if}
		</div>

	{:else if activeTab === 'stats'}
		<div class="max-w-lg">
			{#if stats}
				<dl class="space-y-3 text-sm">
					<div class="flex justify-between border-b border-border pb-2">
						<dt class="text-muted-foreground">{m.ai_stats_total()}</dt>
						<dd class="font-semibold">{stats.totalProcessed}</dd>
					</div>
					<div class="flex justify-between border-b border-border pb-2">
						<dt class="text-muted-foreground">{m.ai_stats_pending()}</dt>
						<dd class="font-semibold">{stats.totalPending}</dd>
					</div>
					<div class="flex justify-between border-b border-border pb-2">
						<dt class="text-muted-foreground">{m.ai_stats_confidence()}</dt>
						<dd class="font-semibold">{Math.round(stats.averageConfidence * 100)}%</dd>
					</div>
					<div class="flex justify-between">
						<dt class="text-muted-foreground">{m.ai_stats_last()}</dt>
						<dd class="font-semibold">{stats.lastProcessedAt ? new Date(stats.lastProcessedAt).toLocaleString() : m.ai_stats_never()}</dd>
					</div>
				</dl>
			{:else}
				<p class="text-muted-foreground">{m.ai_stats_empty()}</p>
			{/if}
		</div>
	{/if}
</div>
