<script lang="ts">
	import { user, isEditorOrAbove, isGlobalAdmin } from '$lib/auth';
</script>

<svelte:head>
	<title>Dashboard - Kosha</title>
</svelte:head>

<h1 class="text-2xl font-bold">Dashboard</h1>
<p class="mt-1 text-muted-foreground">Welcome back, {$user?.name}.</p>

<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
	<a
		href="/documents"
		class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		<h2 class="text-lg font-semibold">Documents</h2>
		<p class="mt-1 text-sm text-muted-foreground">Browse and manage documents</p>
	</a>

	<a
		href="/search"
		class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		<h2 class="text-lg font-semibold">Search</h2>
		<p class="mt-1 text-sm text-muted-foreground">Find documents by content or metadata</p>
	</a>

	{#if isEditorOrAbove()}
		<a
			href="/inbox"
			class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<h2 class="text-lg font-semibold">Review Inbox</h2>
			<p class="mt-1 text-sm text-muted-foreground">Documents awaiting your review</p>
		</a>
	{/if}

	<a
		href="/taxonomy"
		class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		<h2 class="text-lg font-semibold">Taxonomy</h2>
		<p class="mt-1 text-sm text-muted-foreground">Browse the knowledge taxonomy</p>
	</a>

	{#if isGlobalAdmin()}
		<a
			href="/admin"
			class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<h2 class="text-lg font-semibold">Administration</h2>
			<p class="mt-1 text-sm text-muted-foreground">System configuration and management</p>
		</a>
	{/if}
</div>
