<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import * as m from '$paraglide/messages';

	interface LicenceSummary {
		tier: string;
		organisation: string | null;
		licenceId: string | null;
		expiresAt: string | null;
		expired: boolean;
		maxUsers: number | null;
		features: Record<string, unknown>;
	}

	let licence = $state<LicenceSummary | null>(null);
	let loading = $state(true);
	let error = $state('');

	let keyInput = $state('');
	let applying = $state(false);
	let applyError = $state('');
	let applySuccess = $state('');

	const tierLabels: Record<string, string> = {
		community: 'Community',
		professional: 'Professional',
		enterprise: 'Enterprise',
	};

	const tierColors: Record<string, string> = {
		community: 'bg-muted text-muted-foreground',
		professional: 'bg-primary/10 text-primary',
		enterprise: 'bg-success/20 text-success',
	};

	onMount(() => loadLicence());

	async function loadLicence() {
		loading = true;
		error = '';
		try {
			const res = await api.get<LicenceSummary>('/api/v1/admin/licence');
			licence = res.data;
		} catch (e: any) {
			error = e.message ?? 'Failed to load licence';
		} finally {
			loading = false;
		}
	}

	async function applyKey() {
		if (!keyInput.trim()) {
			applyError = 'Paste a licence key first.';
			return;
		}
		applying = true;
		applyError = '';
		applySuccess = '';
		try {
			const res = await api.patch<LicenceSummary>('/api/v1/admin/licence', {
				key: keyInput.trim(),
			});
			licence = res.data;
			keyInput = '';
			applySuccess = 'Licence applied.';
			setTimeout(() => { if (applySuccess === 'Licence applied.') applySuccess = ''; }, 3000);
		} catch (e: any) {
			applyError = e.message ?? 'Invalid licence key.';
		} finally {
			applying = false;
		}
	}
</script>

<svelte:head>
	<title>Licence - {m.nav_sidebar_administration()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title="Licence" description="View and manage your Eòlas licence." />

{#if loading}
	<p class="mt-6 text-muted-foreground">{m.app_loading()}</p>
{:else if error}
	<div class="mt-6 rounded-md border border-destructive bg-destructive/10 p-4 text-sm text-destructive">
		{error}
	</div>
{:else if licence}
	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<div class="space-y-6 lg:col-span-2">
			<!-- Current licence -->
			<section class="rounded-lg border border-border bg-card p-6">
				<div class="flex items-center gap-3">
					<h2 class="text-lg font-semibold">Current Licence</h2>
					<span class="rounded-md px-2 py-0.5 text-xs font-medium {tierColors[licence.tier] ?? tierColors.community}">
						{tierLabels[licence.tier] ?? licence.tier}
					</span>
					{#if licence.expired}
						<span class="rounded-md bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive">
							Expired
						</span>
					{/if}
				</div>

				<dl class="mt-4 grid gap-3 text-sm sm:grid-cols-2">
					{#if licence.organisation}
						<div>
							<dt class="text-xs font-medium text-muted-foreground">Organisation</dt>
							<dd class="mt-1 font-medium">{licence.organisation}</dd>
						</div>
					{/if}
					{#if licence.licenceId}
						<div>
							<dt class="text-xs font-medium text-muted-foreground">Licence ID</dt>
							<dd class="mt-1 font-mono text-xs">{licence.licenceId}</dd>
						</div>
					{/if}
					<div>
						<dt class="text-xs font-medium text-muted-foreground">Expires</dt>
						<dd class="mt-1 font-medium">{licence.expiresAt ?? 'Never'}</dd>
					</div>
					<div>
						<dt class="text-xs font-medium text-muted-foreground">Max Users</dt>
						<dd class="mt-1 font-medium">{licence.maxUsers ?? 'Unlimited'}</dd>
					</div>
				</dl>

				{#if Object.keys(licence.features).length > 0}
					<div class="mt-4 border-t border-border pt-4">
						<h3 class="text-xs font-medium text-muted-foreground">Features</h3>
						<div class="mt-2 flex flex-wrap gap-2">
							{#each Object.entries(licence.features) as [key, value]}
								<span class="rounded-md border border-border px-2 py-0.5 text-xs">
									{key}: {String(value)}
								</span>
							{/each}
						</div>
					</div>
				{/if}
			</section>

			<!-- Apply new key -->
			<section class="rounded-lg border border-border bg-card p-6">
				<h2 class="text-sm font-semibold">Apply a licence key</h2>
				<p class="mt-1 text-xs text-muted-foreground">
					Paste a licence key below to activate or upgrade your licence.
					The key is validated before being applied. Your current data and
					configuration are not affected.
				</p>
				<textarea
					bind:value={keyInput}
					rows="3"
					disabled={applying}
					placeholder="Paste your licence key here..."
					class="mt-3 w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-xs focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				></textarea>

				{#if applyError}
					<p class="mt-2 text-sm text-destructive">{applyError}</p>
				{/if}
				{#if applySuccess}
					<p class="mt-2 text-sm text-success">{applySuccess}</p>
				{/if}

				<div class="mt-3 flex justify-end">
					<button
						type="button"
						onclick={applyKey}
						disabled={applying || !keyInput.trim()}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{applying ? 'Validating...' : 'Apply key'}
					</button>
				</div>
			</section>
		</div>

		<!-- Sidebar -->
		<div class="space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">Get a licence</h2>
				<p class="text-xs text-muted-foreground">
					Eòlas Community is free and includes all features. Professional
					and Enterprise licences add priority support, SLAs, and
					onboarding assistance.
				</p>
				<div class="mt-3 space-y-2 text-xs">
					<div class="rounded-md border border-border p-2">
						<p class="font-medium">Community</p>
						<p class="text-muted-foreground">Free. All features. Self-supported.</p>
					</div>
					<div class="rounded-md border border-primary/30 bg-primary/5 p-2">
						<p class="font-medium text-primary">Professional</p>
						<p class="text-muted-foreground">€149/month. Priority email support (48hr).</p>
					</div>
					<div class="rounded-md border border-success/30 bg-success/5 p-2">
						<p class="font-medium text-success">Enterprise</p>
						<p class="text-muted-foreground">€499/month. 4hr SLA. Phone support. Onboarding.</p>
					</div>
				</div>
				<a
					href="https://eolaskms.com/pricing"
					target="_blank"
					rel="noopener"
					class="mt-3 block text-center text-xs font-medium text-primary underline hover:opacity-80"
				>
					Request a licence key
				</a>
			</section>
		</div>
	</div>
{/if}
