<script lang="ts">
	import { onMount } from 'svelte';
	import { afterNavigate, goto } from '$app/navigation';
	import { initAuth, user, hasAnyRole } from '$lib/auth';
	import Sidebar from '$lib/components/kosha/Sidebar.svelte';
	import TopBar from '$lib/components/kosha/TopBar.svelte';
	import Breadcrumbs from '$lib/components/kosha/Breadcrumbs.svelte';

	let { children } = $props();

	onMount(async () => {
		await initAuth();
		if (!$user) {
			goto('/');
			return;
		}
		if (!hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')) {
			goto('/dashboard');
		}
	});

	afterNavigate(() => {
		requestAnimationFrame(() => {
			const h1 = document.querySelector('main h1') as HTMLElement | null;
			if (h1) {
				h1.setAttribute('tabindex', '-1');
				h1.focus({ preventScroll: true });
			}
		});
	});
</script>

{#if $user}
	<div class="flex h-screen overflow-hidden bg-background">
		<Sidebar />

		<div class="flex flex-1 flex-col overflow-hidden">
			<TopBar />

			<main id="main-content" class="flex-1 overflow-y-auto p-6">
				<Breadcrumbs />
				{@render children()}
			</main>

			<footer class="border-t border-border px-6 py-2 text-center text-xs text-muted-foreground">
				&copy; 2026 Kosha KMS
			</footer>
		</div>
	</div>
{:else}
	<div class="flex min-h-screen items-center justify-center">
		<p class="text-muted-foreground">Loading...</p>
	</div>
{/if}
