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
	<title>Kosha KMS</title>
</svelte:head>

<div class="flex min-h-screen flex-col items-center justify-center bg-background">
	<div class="mx-auto max-w-md text-center">
		<img src="/favicon.svg" alt="Kosha" class="mx-auto h-24 w-24 rounded-2xl shadow-lg">
		<h1 class="mt-6 text-4xl font-bold text-primary">Kosha</h1>
		<p class="mt-1 text-3xl font-semibold text-accent">कोश</p>
		<p class="mt-3 text-lg text-muted-foreground">Treasury of Knowledge</p>
		<p class="mt-2 text-sm text-muted-foreground">Enterprise Document Management System</p>

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
