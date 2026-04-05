<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { user, initAuth, login } from '$lib/auth';

	onMount(async () => {
		await initAuth();
		if ($user) {
			goto('/dashboard');
		}
	});
</script>

<svelte:head>
	<title>Eòlas</title>
</svelte:head>

<div class="flex min-h-screen flex-col items-center justify-center bg-background">
	<div class="mx-auto max-w-md text-center">
		<img src="/favicon.png" alt="Eòlas" class="mx-auto h-32 w-32">
		<h1 class="mt-6 text-5xl font-bold text-primary">Eòlas</h1>
		<p class="mt-2 text-lg text-muted-foreground italic">Knowledge</p>
		<p class="mt-4 text-sm text-muted-foreground">Enterprise Knowledge Management</p>

		{#if !$user}
			<button
				onclick={login}
				class="mt-8 rounded-lg bg-primary px-6 py-3 font-semibold text-primary-foreground transition hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			>
				Sign in to get started
			</button>
		{/if}
	</div>
</div>
