<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { handleCallback } from '$lib/auth';

	onMount(async () => {
		await handleCallback();
		goto('/dashboard');
	});
</script>

<div class="flex min-h-screen items-center justify-center">
	<p class="text-muted-foreground">Signing in...</p>
</div>
