<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { handleCallback } from '$lib/auth';

	let error = $state('');
	let logoutUrl = $state('');

	onMount(async () => {
		// Guard against double execution — use a window-level flag
		// that survives Vite HMR and Svelte reactivity re-triggers
		if ((window as any).__kosha_callback_processing) return;
		(window as any).__kosha_callback_processing = true;

		const keycloakUrl = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180';
		logoutUrl = `${keycloakUrl}/realms/kosha/protocol/openid-connect/logout?post_logout_redirect_uri=${encodeURIComponent(window.location.origin)}&client_id=kosha-web`;

		const params = new URLSearchParams(window.location.search);
		if (!params.has('code') && !params.has('state')) {
			(window as any).__kosha_callback_processing = false;
			goto('/');
			return;
		}

		try {
			await handleCallback();
			(window as any).__kosha_callback_processing = false;
			const returnTo = sessionStorage.getItem('kosha-return-to') || '/dashboard';
			sessionStorage.removeItem('kosha-return-to');
			goto(returnTo);
		} catch (e: any) {
			(window as any).__kosha_callback_processing = false;
			error = e.message ?? 'Authentication failed';
			for (const key of Object.keys(localStorage)) {
				if (key.startsWith('oidc.')) localStorage.removeItem(key);
			}
		}
	});
</script>

<div class="flex min-h-screen items-center justify-center">
	{#if error}
		<div class="text-center">
			<p class="text-destructive font-medium">{error}</p>
			<p class="mt-2 text-sm text-muted-foreground">OIDC session has been cleared.</p>
			<a
				href={logoutUrl}
				class="mt-4 inline-block rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring"
			>
				Try again
			</a>
		</div>
	{:else}
		<p class="text-muted-foreground">Signing in...</p>
	{/if}
</div>
