<script lang="ts">
	import { user, isEditorOrAbove, isGlobalAdmin } from '$lib/auth';
	import * as m from '$paraglide/messages';
</script>

<svelte:head>
	<title>{m.dashboard_title()} - {m.nav_app_title()}</title>
</svelte:head>

<h1 class="text-2xl font-bold">{m.dashboard_title()}</h1>
<p class="mt-1 text-muted-foreground">{m.dashboard_greeting({ name: $user?.name ?? '' })}</p>

<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
	<a
		href="/documents"
		class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		<h2 class="text-lg font-semibold">{m.dashboard_documents()}</h2>
		<p class="mt-1 text-sm text-muted-foreground">{m.dashboard_documents_desc()}</p>
	</a>

	<a
		href="/search"
		class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		<h2 class="text-lg font-semibold">{m.dashboard_search()}</h2>
		<p class="mt-1 text-sm text-muted-foreground">{m.dashboard_search_desc()}</p>
	</a>

	{#if isEditorOrAbove()}
		<a
			href="/inbox"
			class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<h2 class="text-lg font-semibold">{m.dashboard_review()}</h2>
			<p class="mt-1 text-sm text-muted-foreground">{m.dashboard_review_desc()}</p>
		</a>
	{/if}

	<a
		href="/taxonomy"
		class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		<h2 class="text-lg font-semibold">{m.dashboard_taxonomy()}</h2>
		<p class="mt-1 text-sm text-muted-foreground">{m.dashboard_taxonomy_desc()}</p>
	</a>

	{#if isGlobalAdmin()}
		<a
			href="/admin"
			class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<h2 class="text-lg font-semibold">{m.dashboard_admin()}</h2>
			<p class="mt-1 text-sm text-muted-foreground">{m.dashboard_admin_desc()}</p>
		</a>
	{/if}
</div>
