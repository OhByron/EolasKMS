<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { user, initAuth, login } from '$lib/auth';
	import * as m from '$paraglide/messages';

	let loading = $state(true);
	let hasKey = $state(false);
	let licenceTier = $state('');
	let licenceOrg = $state('');
	let keyInput = $state('');
	let applying = $state(false);
	let error = $state('');

	const API_BASE = '';

	onMount(async () => {
		await initAuth();
		if ($user) {
			goto('/dashboard');
			return;
		}

		// Check if a licence key has been applied
		try {
			const res = await fetch(`${API_BASE}/api/v1/public/licence`);
			if (res.ok) {
				const body = await res.json();
				hasKey = body.data?.hasKey ?? false;
				licenceTier = body.data?.tier ?? '';
				licenceOrg = body.data?.organisation ?? '';
			}
		} catch (e) {
			// Backend not reachable — show licence entry as fallback
			console.error('Failed to check licence status:', e);
		}

		loading = false;
	});

	async function applyKey() {
		const trimmed = keyInput.trim();
		if (!trimmed) {
			error = 'Please paste your licence key.';
			return;
		}
		applying = true;
		error = '';

		try {
			const res = await fetch(`${API_BASE}/api/v1/public/licence`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ key: trimmed }),
			});

			const body = await res.json();

			if (!res.ok) {
				error = body.message ?? body.error ?? 'Invalid licence key. Please check and try again.';
				return;
			}

			hasKey = true;
			licenceTier = body.data?.tier ?? '';
			licenceOrg = body.data?.organisation ?? '';
		} catch (e) {
			error = 'Could not reach the server. Please try again.';
		} finally {
			applying = false;
		}
	}
</script>

<svelte:head>
	<title>{m.nav_app_title()}</title>
</svelte:head>

<div class="flex min-h-screen flex-col items-center justify-center bg-background">
	<div class="mx-auto max-w-md text-center">
		<img src="/favicon.png" alt={m.nav_app_title()} class="mx-auto h-32 w-32">
		<h1 class="mt-6 text-5xl font-bold text-primary">{m.nav_app_title()}</h1>
		<p class="mt-2 text-lg text-muted-foreground italic">{m.landing_tagline()}</p>

		{#if loading}
			<p class="mt-8 text-sm text-muted-foreground">{m.app_loading()}</p>
		{:else if !hasKey}
			<div class="mt-8 rounded-lg border border-border bg-card p-6 text-left shadow-sm">
				<h2 class="text-lg font-semibold text-foreground">{m.licence_gate_title()}</h2>
				<p class="mt-1 text-sm text-muted-foreground">{m.licence_gate_desc()}</p>

				<textarea
					bind:value={keyInput}
					rows="4"
					class="mt-4 w-full rounded-md border border-input bg-background px-3 py-2 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-2 focus:outline-ring"
					placeholder={m.licence_gate_placeholder()}
					onfocus={(e) => e.currentTarget.select()}
				></textarea>

				{#if error}
					<p class="mt-2 text-sm text-destructive">{error}</p>
				{/if}

				<button
					onclick={applyKey}
					disabled={applying}
					class="mt-4 w-full rounded-lg bg-primary px-4 py-2.5 font-semibold text-primary-foreground transition hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				>
					{applying ? m.licence_gate_applying() : m.licence_gate_apply()}
				</button>

				<div class="mt-4 border-t border-border pt-4 text-center">
					<p class="text-sm text-muted-foreground">
						{m.licence_gate_no_key()}
						<a href="https://www.eolaskms.com/request-key" target="_blank" rel="noopener"
							class="font-medium text-primary underline hover:opacity-80">
							{m.licence_gate_request_link()}
						</a>
					</p>
					<p class="mt-1 text-xs text-muted-foreground">{m.licence_gate_free_hint()}</p>
				</div>
			</div>
		{:else}
			<p class="mt-4 text-sm text-muted-foreground">{m.landing_subtitle()}</p>

			{#if licenceOrg}
				<p class="mt-2 text-xs text-muted-foreground">
					{m.licence_gate_licensed_to({ org: licenceOrg })}
					{#if licenceTier && licenceTier !== 'community'}
						<span class="ml-1 rounded bg-primary/10 px-1.5 py-0.5 text-xs font-medium text-primary">{licenceTier}</span>
					{/if}
				</p>
			{/if}

			{#if !$user}
				<button
					onclick={login}
					class="mt-8 rounded-lg bg-primary px-6 py-3 font-semibold text-primary-foreground transition hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					{m.btn_sign_in()}
				</button>
			{/if}
		{/if}
	</div>
</div>
