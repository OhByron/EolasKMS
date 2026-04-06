<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type {
		LegalReviewSettings,
		NotificationSettings,
		WorkflowEscalationSettings,
	} from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import * as m from '$paraglide/messages';

	// ── Retention cadence (existing) ─────────────────────────────
	let settings = $state<NotificationSettings | null>(null);
	let loading = $state(true);
	let saving = $state(false);
	let error = $state('');
	let saveResult = $state('');
	let selectedInterval = $state(24);

	// ── Legal review time limit (new) ─────────────────────────────
	// Separate concern, separate form state, separate save path. Kept on
	// the same page because both are "global notification defaults" and
	// administrators looking to tune one are likely to want to tune the other.
	let legalSettings = $state<LegalReviewSettings | null>(null);
	let legalLoading = $state(true);
	let legalSaving = $state(false);
	let legalError = $state('');
	let legalSaveResult = $state('');
	let legalTimeLimitDays = $state(5);

	// ── Workflow escalation cadence ──────────────────────────────
	// Controls how often the workflow engine's scanner looks for overdue
	// approval steps and reassigns them to their escalation contact.
	// Global-admin-only setting; the scanner reads the DB row on every
	// tick so changes take effect within one minute without a restart.
	let escalationSettings = $state<WorkflowEscalationSettings | null>(null);
	let escalationLoading = $state(true);
	let escalationSaving = $state(false);
	let escalationError = $state('');
	let escalationSaveResult = $state('');
	let escalationInterval = $state(15);

	onMount(() => {
		load();
		loadLegal();
		loadEscalation();
	});

	async function load() {
		loading = true;
		error = '';
		try {
			const res = await api.notificationSettings.getGlobal();
			settings = res.data;
			selectedInterval = settings.defaultScanIntervalHours;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	async function loadLegal() {
		legalLoading = true;
		legalError = '';
		try {
			const res = await api.legalReview.getSettings();
			legalSettings = res.data;
			legalTimeLimitDays = legalSettings.defaultTimeLimitDays;
		} catch (e: any) {
			legalError = e.message;
		} finally {
			legalLoading = false;
		}
	}

	async function save() {
		saving = true;
		saveResult = '';
		error = '';
		try {
			const res = await api.notificationSettings.updateGlobal({
				defaultScanIntervalHours: selectedInterval,
			});
			settings = res.data;
			saveResult = 'Settings saved';
		} catch (e: any) {
			error = e.message;
		} finally {
			saving = false;
		}
	}

	async function loadEscalation() {
		escalationLoading = true;
		escalationError = '';
		try {
			const res = await api.workflowEscalation.getSettings();
			escalationSettings = res.data;
			escalationInterval = escalationSettings.scanIntervalMinutes;
		} catch (e: any) {
			escalationError = e.message;
		} finally {
			escalationLoading = false;
		}
	}

	async function saveEscalation() {
		escalationSaving = true;
		escalationSaveResult = '';
		escalationError = '';
		try {
			const res = await api.workflowEscalation.updateSettings({
				scanIntervalMinutes: escalationInterval,
			});
			escalationSettings = res.data;
			escalationInterval = escalationSettings.scanIntervalMinutes;
			escalationSaveResult = 'Escalation cadence saved';
		} catch (e: any) {
			escalationError = e.message;
		} finally {
			escalationSaving = false;
		}
	}

	async function saveLegal() {
		legalSaving = true;
		legalSaveResult = '';
		legalError = '';
		try {
			// Client-side bound guard — the backend enforces 1..90 but we
			// reject out-of-range values here to give immediate feedback.
			if (legalTimeLimitDays < 1 || legalTimeLimitDays > 90) {
				legalError = 'Time limit must be between 1 and 90 days';
				return;
			}
			const res = await api.legalReview.updateSettings({
				defaultTimeLimitDays: Math.round(legalTimeLimitDays),
			});
			legalSettings = res.data;
			legalTimeLimitDays = legalSettings.defaultTimeLimitDays;
			legalSaveResult = 'Legal review settings saved';
		} catch (e: any) {
			legalError = e.message;
		} finally {
			legalSaving = false;
		}
	}

	function formatDate(iso: string | null): string {
		if (!iso) return m.time_never();
		return new Date(iso).toLocaleString('en-US');
	}
</script>

<svelte:head>
	<title>{m.settings_page_title()} - {m.nav_sidebar_administration()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader
	title={m.settings_page_title()}
	description={m.settings_page_desc()}
/>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading settings...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={load} /></div>
{:else if settings}
	<div class="mt-6 max-w-2xl space-y-6">
		<!-- Retention scan cadence -->
		<form
			onsubmit={(e) => {
				e.preventDefault();
				save();
			}}
			class="rounded-lg border border-border bg-card p-6 space-y-5"
		>
			<div>
				<h2 class="text-sm font-semibold">{m.settings_retention_heading()}</h2>
				<p class="mt-1 text-xs text-muted-foreground">
					{m.settings_retention_desc()}
				</p>
			</div>

			<fieldset>
				<legend class="text-sm font-medium">{m.settings_retention_interval()}</legend>
				<div class="mt-2 space-y-2">
					{#each settings.validIntervals as opt}
						<label class="flex items-center gap-3 text-sm">
							<input
								type="radio"
								name="interval"
								value={opt.hours}
								checked={selectedInterval === opt.hours}
								onchange={() => (selectedInterval = opt.hours)}
								class="focus:ring-ring"
							/>
							<span>
								<span class="font-medium">{opt.label}</span>
								<span class="ml-1 text-xs text-muted-foreground">({opt.hours}h)</span>
							</span>
						</label>
					{/each}
				</div>
				<p class="mt-3 text-xs text-muted-foreground">
					{m.settings_retention_min_hint({ hours: settings.minScanIntervalHours.toString() })}
				</p>
			</fieldset>

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
						{m.btn_discard()}
					</button>
					<button
						type="submit"
						disabled={saving || selectedInterval === settings.defaultScanIntervalHours}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{saving ? m.btn_saving() : m.btn_save()}
					</button>
				</div>
			</div>
		</form>

		<!-- Legal review time limit -->
		<form
			onsubmit={(e) => {
				e.preventDefault();
				saveLegal();
			}}
			class="rounded-lg border border-border bg-card p-6 space-y-5"
		>
			<div>
				<h2 class="text-sm font-semibold">{m.settings_legal_heading()}</h2>
				<p class="mt-1 text-xs text-muted-foreground">
					{m.settings_legal_desc()}
				</p>
			</div>

			{#if legalLoading}
				<p aria-live="polite" class="text-xs text-muted-foreground">Loading…</p>
			{:else if legalError && !legalSettings}
				<div role="alert" class="rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
					{legalError}
					<button type="button" onclick={loadLegal} class="ml-2 underline focus:outline-2 focus:outline-ring">
						Retry
					</button>
				</div>
			{:else if legalSettings}
				<div>
					<label for="legal-time-limit" class="block text-sm font-medium">
						{m.settings_legal_field()}
					</label>
					<input
						id="legal-time-limit"
						type="number"
						min="1"
						max="90"
						step="1"
						bind:value={legalTimeLimitDays}
						class="mt-1 w-32 rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
					<p class="mt-1 text-xs text-muted-foreground">
						{m.settings_legal_range()}
					</p>
				</div>

				{#if legalError}
					<div role="alert" class="rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
						{legalError}
					</div>
				{/if}

				<div class="flex items-center justify-between pt-2">
					<div>
						{#if legalSaveResult}
							<p class="text-sm font-medium text-success" aria-live="polite">{legalSaveResult}</p>
						{/if}
					</div>
					<div class="flex gap-2">
						<button
							type="button"
							onclick={loadLegal}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						>
							{m.btn_discard()}
						</button>
						<button
							type="submit"
							disabled={legalSaving || legalTimeLimitDays === legalSettings.defaultTimeLimitDays}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
						>
							{legalSaving ? m.btn_saving() : m.btn_save()}
						</button>
					</div>
				</div>
			{/if}
		</form>

		<!-- Workflow escalation cadence -->
		<form
			onsubmit={(e) => {
				e.preventDefault();
				saveEscalation();
			}}
			class="rounded-lg border border-border bg-card p-6 space-y-5"
		>
			<div>
				<h2 class="text-sm font-semibold">{m.settings_escalation_heading()}</h2>
				<p class="mt-1 text-xs text-muted-foreground">
					{m.settings_escalation_desc()}
				</p>
			</div>

			{#if escalationLoading}
				<p aria-live="polite" class="text-xs text-muted-foreground">Loading…</p>
			{:else if escalationError && !escalationSettings}
				<div role="alert" class="rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
					{escalationError}
					<button
						type="button"
						onclick={loadEscalation}
						class="ml-2 underline focus:outline-2 focus:outline-ring"
					>
						Retry
					</button>
				</div>
			{:else if escalationSettings}
				<fieldset>
					<legend class="text-sm font-medium">{m.settings_escalation_interval()}</legend>
					<div class="mt-2 space-y-2">
						{#each escalationSettings.validIntervals as opt}
							<label class="flex items-center gap-3 text-sm">
								<input
									type="radio"
									name="escalation-interval"
									value={opt.minutes}
									checked={escalationInterval === opt.minutes}
									onchange={() => (escalationInterval = opt.minutes)}
									class="focus:ring-ring"
								/>
								<span>
									<span class="font-medium">{opt.label}</span>
									<span class="ml-1 text-xs text-muted-foreground">({opt.minutes} min)</span>
								</span>
							</label>
						{/each}
					</div>
					<p class="mt-3 text-xs text-muted-foreground">
						{m.settings_escalation_hint()}
					</p>
				</fieldset>

				{#if escalationError}
					<div role="alert" class="rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
						{escalationError}
					</div>
				{/if}

				<div class="flex items-center justify-between pt-2">
					<div>
						{#if escalationSaveResult}
							<p class="text-sm font-medium text-success" aria-live="polite">
								{escalationSaveResult}
							</p>
						{/if}
					</div>
					<div class="flex gap-2">
						<button
							type="button"
							onclick={loadEscalation}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						>
							{m.btn_discard()}
						</button>
						<button
							type="submit"
							disabled={escalationSaving ||
								escalationInterval === escalationSettings.scanIntervalMinutes}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
						>
							{escalationSaving ? m.btn_saving() : m.btn_save()}
						</button>
					</div>
				</div>
			{/if}
		</form>

		<aside class="rounded-lg border border-border bg-card p-5 text-sm">
			<h2 class="text-sm font-semibold">About scan cadence</h2>
			<ul class="mt-2 space-y-1 text-xs text-muted-foreground">
				<li>• The scanner runs daily at 07:00 as a tick and evaluates each department.</li>
				<li>• A department is processed when the time since its last scan exceeds its interval.</li>
				<li>• Both approaching-review warnings (90/60/30 day) and critical-overdue alerts are dispatched in one pass.</li>
				<li>• Department administrators can override the default on individual departments, but cannot drop below the minimum.</li>
			</ul>
			<dl class="mt-4 space-y-1 text-xs">
				<div class="flex justify-between">
					<dt class="text-muted-foreground">Retention settings updated</dt>
					<dd>{formatDate(settings.updatedAt)}</dd>
				</div>
				{#if legalSettings}
					<div class="flex justify-between">
						<dt class="text-muted-foreground">Legal review settings updated</dt>
						<dd>{formatDate(legalSettings.updatedAt)}</dd>
					</div>
				{/if}
				{#if escalationSettings}
					<div class="flex justify-between">
						<dt class="text-muted-foreground">Escalation settings updated</dt>
						<dd>{formatDate(escalationSettings.updatedAt)}</dd>
					</div>
					<div class="flex justify-between">
						<dt class="text-muted-foreground">Last escalation scan</dt>
						<dd>{formatDate(escalationSettings.lastScanAt)}</dd>
					</div>
				{/if}
			</dl>
		</aside>
	</div>
{/if}
