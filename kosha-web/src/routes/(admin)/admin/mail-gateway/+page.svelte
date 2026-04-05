<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { MailGatewayConfig, ProviderPreset, GatewayTestResult } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let config = $state<MailGatewayConfig | null>(null);
	let presets = $state<ProviderPreset[]>([]);
	let loading = $state(true);
	let saving = $state(false);
	let testing = $state(false);
	let error = $state('');
	let testResult = $state<GatewayTestResult | null>(null);
	let saveResult = $state('');

	// Form state (separate from `config` so we can edit without immediately
	// mutating the loaded value)
	let provider = $state('mailpit');
	let host = $state('');
	let port = $state(587);
	let encryption = $state<'starttls' | 'tls' | 'none'>('starttls');
	let skipTlsVerify = $state(false);
	let username = $state('');
	let password = $state(''); // empty string on load (masked); user fills to change
	let passwordTouched = $state(false);
	let fromEmail = $state('');
	let fromName = $state('');
	let replyToEmail = $state('');
	let region = $state<string | null>(null);
	let testRecipient = $state('');

	const currentPreset = $derived<ProviderPreset | undefined>(
		presets.find((p) => p.key === provider)
	);

	onMount(() => load());

	async function load() {
		loading = true;
		error = '';
		try {
			const [configRes, presetsRes] = await Promise.all([
				api.mailGateway.get(),
				api.mailGateway.presets(),
			]);
			config = configRes.data;
			presets = presetsRes.data;

			// Populate form from loaded config
			provider = config.provider;
			host = config.host;
			port = config.port;
			encryption = config.encryption;
			skipTlsVerify = config.skipTlsVerify;
			username = config.username ?? '';
			password = ''; // never show existing password
			passwordTouched = false;
			fromEmail = config.fromEmail;
			fromName = config.fromName;
			replyToEmail = config.replyToEmail ?? '';
			region = config.region;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	function applyPreset(key: string) {
		const preset = presets.find((p) => p.key === key);
		if (!preset) return;
		provider = preset.key;
		if (preset.defaultHost) host = preset.defaultHost;
		port = preset.defaultPort;
		encryption = preset.defaultEncryption;
		if (preset.fixedUsername !== null) username = preset.fixedUsername;
		if (preset.regions.length > 0) {
			region = preset.regions[0].key;
			host = preset.regions[0].host;
		} else {
			region = null;
		}
		testResult = null;
		saveResult = '';
	}

	function onRegionChange(key: string) {
		region = key;
		const r = currentPreset?.regions.find((x) => x.key === key);
		if (r) host = r.host;
	}

	function buildRequest() {
		return {
			provider,
			transport: 'smtp',
			host,
			port,
			encryption,
			skipTlsVerify,
			username: username || null,
			// Only send password if user actually typed one
			password: passwordTouched ? password : null,
			fromEmail,
			fromName,
			replyToEmail: replyToEmail || null,
			region,
		};
	}

	async function save() {
		saving = true;
		saveResult = '';
		error = '';
		try {
			const res = await api.mailGateway.update(buildRequest());
			config = res.data;
			// Reset password state — backend now has the new value
			password = '';
			passwordTouched = false;
			saveResult = 'Configuration saved';
		} catch (e: any) {
			error = e.message;
		} finally {
			saving = false;
		}
	}

	async function runTestConnection() {
		testing = true;
		testResult = null;
		try {
			const res = await api.mailGateway.testConnection(buildRequest());
			testResult = res.data;
		} catch (e: any) {
			testResult = { success: false, message: 'Request failed', detail: e.message };
		} finally {
			testing = false;
		}
	}

	async function runTestSend() {
		if (!testRecipient) {
			testResult = { success: false, message: 'Enter a recipient address first', detail: null };
			return;
		}
		testing = true;
		testResult = null;
		try {
			const res = await api.mailGateway.testSend({
				...buildRequest(),
				testRecipient,
			});
			testResult = res.data;
		} catch (e: any) {
			testResult = { success: false, message: 'Request failed', detail: e.message };
		} finally {
			testing = false;
		}
	}

	function formatDate(iso: string | null): string {
		if (!iso) return 'never';
		return new Date(iso).toLocaleString('en-US');
	}
</script>

<svelte:head>
	<title>Mail Gateway - Administration - Eòlas</title>
</svelte:head>

<PageHeader title="Mail Gateway" description="Configure the outbound email provider used for all Eòlas notifications." />

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading configuration...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={load} /></div>
{:else if config}
	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<!-- Main form -->
		<form
			onsubmit={(e) => {
				e.preventDefault();
				save();
			}}
			class="lg:col-span-2 space-y-5 rounded-lg border border-border bg-card p-6"
		>
			<div>
				<label for="provider" class="block text-sm font-medium">Provider</label>
				<select
					id="provider"
					bind:value={provider}
					onchange={(e) => applyPreset((e.target as HTMLSelectElement).value)}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					{#each presets as p}
						<option value={p.key}>{p.label}{p.devOnly ? ' (dev)' : ''}</option>
					{/each}
				</select>
				{#if currentPreset}
					<p class="mt-1 text-xs text-muted-foreground">{currentPreset.description}</p>
				{/if}
			</div>

			{#if currentPreset?.warning}
				<div class="rounded-md border border-warning bg-warning/10 p-3 text-sm" role="note">
					<strong class="font-medium">Note:</strong>
					{currentPreset.warning}
				</div>
			{/if}

			{#if currentPreset?.showRegion && currentPreset.regions.length > 0}
				<div>
					<label for="region" class="block text-sm font-medium">Region</label>
					<select
						id="region"
						value={region}
						onchange={(e) => onRegionChange((e.target as HTMLSelectElement).value)}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					>
						{#each currentPreset.regions as r}
							<option value={r.key}>{r.label}</option>
						{/each}
					</select>
				</div>
			{/if}

			<div class="grid gap-4 sm:grid-cols-[1fr_120px]">
				<div>
					<label for="host" class="block text-sm font-medium">SMTP host</label>
					<input
						id="host"
						type="text"
						bind:value={host}
						required
						placeholder={currentPreset?.hostHint ?? ''}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
				</div>
				<div>
					<label for="port" class="block text-sm font-medium">Port</label>
					<input
						id="port"
						type="number"
						bind:value={port}
						min="1"
						max="65535"
						required
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
				</div>
			</div>

			<fieldset>
				<legend class="text-sm font-medium">Encryption</legend>
				<div class="mt-2 flex gap-4 text-sm">
					<label class="flex items-center gap-2">
						<input type="radio" bind:group={encryption} value="starttls" /> STARTTLS
					</label>
					<label class="flex items-center gap-2">
						<input type="radio" bind:group={encryption} value="tls" /> Implicit TLS
					</label>
					<label class="flex items-center gap-2">
						<input type="radio" bind:group={encryption} value="none" /> None
					</label>
				</div>
			</fieldset>

			{#if currentPreset?.showSkipTlsVerify}
				<label class="flex items-center gap-2 text-sm">
					<input type="checkbox" bind:checked={skipTlsVerify} />
					Skip TLS certificate verification (self-signed / on-prem)
				</label>
			{/if}

			{#if currentPreset?.showUsername !== false}
				<div>
					<label for="username" class="block text-sm font-medium">
						Username
						{#if currentPreset?.fixedUsername}
							<span class="text-xs font-normal text-muted-foreground">(auto-set to "{currentPreset.fixedUsername}")</span>
						{/if}
					</label>
					<input
						id="username"
						type="text"
						bind:value={username}
						disabled={currentPreset?.fixedUsername !== null}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-60"
					/>
				</div>
			{/if}

			{#if currentPreset?.showPassword !== false}
				<div>
					<label for="password" class="block text-sm font-medium">
						Password / API key
						{#if config.hasPassword}
							<span class="text-xs font-normal text-muted-foreground">— leave blank to keep existing</span>
						{/if}
					</label>
					<input
						id="password"
						type="password"
						autocomplete="new-password"
						bind:value={password}
						oninput={() => (passwordTouched = true)}
						placeholder={config.hasPassword ? '••••••••' : ''}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
				</div>
			{/if}

			<hr class="border-border" />

			<div class="grid gap-4 sm:grid-cols-2">
				<div>
					<label for="fromEmail" class="block text-sm font-medium">From email</label>
					<input
						id="fromEmail"
						type="email"
						bind:value={fromEmail}
						required
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
				</div>
				<div>
					<label for="fromName" class="block text-sm font-medium">From name</label>
					<input
						id="fromName"
						type="text"
						bind:value={fromName}
						required
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
				</div>
			</div>

			<div>
				<label for="replyTo" class="block text-sm font-medium">Reply-to (optional)</label>
				<input
					id="replyTo"
					type="email"
					bind:value={replyToEmail}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				/>
			</div>

			<div class="flex items-center justify-between pt-2">
				<div>
					{#if saveResult}
						<p class="text-sm font-medium text-success" aria-live="polite">{saveResult}</p>
					{/if}
				</div>
				<div class="flex gap-2">
					<button
						type="button"
						onclick={load}
						class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					>
						Discard
					</button>
					<button
						type="submit"
						disabled={saving}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{saving ? 'Saving...' : 'Save Configuration'}
					</button>
				</div>
			</div>
		</form>

		<!-- Test panel -->
		<aside class="space-y-4">
			<div class="rounded-lg border border-border bg-card p-5">
				<h2 class="text-sm font-semibold">Test the configuration</h2>
				<p class="mt-1 text-xs text-muted-foreground">
					Test without saving. The current form values are used — not the stored config.
				</p>

				<div class="mt-4 space-y-2">
					<button
						type="button"
						onclick={runTestConnection}
						disabled={testing}
						class="w-full rounded-md border border-border bg-background px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{testing ? 'Testing...' : 'Test Connection'}
					</button>

					<div>
						<label for="testRecipient" class="block text-xs font-medium text-muted-foreground">
							Test recipient
						</label>
						<input
							id="testRecipient"
							type="email"
							bind:value={testRecipient}
							placeholder="you@example.com"
							class="mt-1 w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						/>
					</div>

					<button
						type="button"
						onclick={runTestSend}
						disabled={testing}
						class="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{testing ? 'Sending...' : 'Send Test Email'}
					</button>
				</div>

				{#if testResult}
					<div
						class="mt-4 rounded-md border p-3 text-sm"
						class:border-success={testResult.success}
						class:bg-success-10={testResult.success}
						class:text-success={testResult.success}
						class:border-destructive={!testResult.success}
						class:bg-destructive-10={!testResult.success}
						class:text-destructive={!testResult.success}
						role="status"
						aria-live="polite"
					>
						<p class="font-medium">{testResult.message}</p>
						{#if testResult.detail}
							<p class="mt-1 text-xs break-words">{testResult.detail}</p>
						{/if}
					</div>
				{/if}
			</div>

			<div class="rounded-lg border border-border bg-card p-5">
				<h2 class="text-sm font-semibold">Last test result</h2>
				<dl class="mt-2 space-y-1 text-xs">
					<div class="flex justify-between">
						<dt class="text-muted-foreground">When</dt>
						<dd>{formatDate(config.lastTestedAt)}</dd>
					</div>
					<div class="flex justify-between">
						<dt class="text-muted-foreground">Outcome</dt>
						<dd>
							{#if config.lastTestSuccess === true}
								<span class="text-success">success</span>
							{:else if config.lastTestSuccess === false}
								<span class="text-destructive">failure</span>
							{:else}
								—
							{/if}
						</dd>
					</div>
				</dl>
				{#if config.lastTestError}
					<p class="mt-2 break-words text-xs text-destructive">{config.lastTestError}</p>
				{/if}
			</div>
		</aside>
	</div>
{/if}
