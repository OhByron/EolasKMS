<script lang="ts">
	import { onMount } from 'svelte';

	onMount(() => {
		// Clear everything OIDC-related
		const keys = Object.keys(localStorage);
		for (const key of keys) {
			if (key.startsWith('oidc.') || key.includes('kosha')) {
				localStorage.removeItem(key);
			}
		}
		sessionStorage.clear();
		// Redirect to root after a tick
		window.location.href = '/';
	});
</script>

<div class="flex min-h-screen items-center justify-center">
	<p class="text-muted-foreground">Clearing session...</p>
</div>
