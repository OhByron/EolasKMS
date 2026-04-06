<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { AuditEvent } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import * as m from '$paraglide/messages';

	let events = $state<AuditEvent[]>([]);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 50;

	// Filters
	let filterType = $state('');
	let expandedId = $state('');

	onMount(() => loadEvents());

	async function loadEvents() {
		loading = true;
		error = '';
		try {
			const res = await api.audit.events(currentPage, pageSize);
			events = res.data;
			total = res.meta?.total ?? events.length;
		} catch (e: any) {
			if (e.message?.includes('404') || e.message?.includes('403')) events = [];
			else error = e.message;
		} finally {
			loading = false;
		}
	}

	const filteredEvents = $derived(
		filterType ? events.filter((e) => e.eventType.startsWith(filterType)) : events
	);

	const eventTypes = $derived([...new Set(events.map((e) => e.eventType.split('.')[0]))]);

	function formatTime(iso: string): string {
		return new Date(iso).toLocaleString('en-US', {
			month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'
		});
	}

	function eventColor(type: string): string {
		if (type.startsWith('doc')) return 'bg-primary/10 text-primary';
		if (type.startsWith('wf')) return 'bg-accent/20 text-accent-foreground';
		if (type.startsWith('tax')) return 'bg-success/20 text-success';
		if (type.startsWith('retention')) return 'bg-warning/20 text-warning';
		return 'bg-muted text-muted-foreground';
	}
</script>

<svelte:head>
	<title>{m.page_title_audit()} - {m.admin_title()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.page_title_audit()} description={m.audit_desc()} />

<!-- Filter bar -->
<div class="mt-4 flex items-center gap-3">
	<label for="audit-filter" class="text-sm font-medium text-muted-foreground">{m.audit_filter_label()}</label>
	<select id="audit-filter" bind:value={filterType}
		class="rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:outline-2 focus:outline-ring">
		<option value="">{m.audit_all_events()}</option>
		{#each eventTypes as t}
			<option value={t}>{t}</option>
		{/each}
	</select>
	<span class="text-sm text-muted-foreground">{filteredEvents.length} event{filteredEvents.length !== 1 ? 's' : ''}</span>
</div>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">{m.audit_loading()}</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadEvents} /></div>
{:else if filteredEvents.length === 0}
	<p class="mt-6 text-muted-foreground">{m.audit_no_events()}</p>
{:else}
	<div class="mt-4 space-y-1">
		{#each filteredEvents as event}
			<div class="rounded-md border border-border bg-card">
				<button
					class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm hover:bg-muted/30 focus:outline-2 focus:outline-offset-[-2px] focus:outline-ring"
					onclick={() => (expandedId = expandedId === event.id ? '' : event.id)}
					aria-expanded={expandedId === event.id}
				>
					<span class="shrink-0 rounded-md px-2 py-0.5 text-xs font-medium {eventColor(event.eventType)}">
						{event.eventType}
					</span>
					<span class="flex-1 truncate text-muted-foreground">
						{event.aggregateType}/{event.aggregateId.slice(0, 8)}...
					</span>
					<time class="shrink-0 text-xs text-muted-foreground">{formatTime(event.occurredAt)}</time>
					<span aria-hidden="true" class="text-xs">{expandedId === event.id ? '▲' : '▼'}</span>
				</button>

				{#if expandedId === event.id}
					<div class="border-t border-border px-4 py-3">
						<dl class="grid grid-cols-2 gap-2 text-sm">
							<div><dt class="text-muted-foreground">{m.audit_event_id()}</dt><dd class="font-mono text-xs">{event.id}</dd></div>
							<div><dt class="text-muted-foreground">{m.audit_aggregate()}</dt><dd>{event.aggregateType} / <span class="font-mono text-xs">{event.aggregateId}</span></dd></div>
							{#if event.actorId}
								<div><dt class="text-muted-foreground">{m.audit_actor()}</dt><dd class="font-mono text-xs">{event.actorId}</dd></div>
							{/if}
							{#if event.departmentId}
								<div><dt class="text-muted-foreground">{m.label_department()}</dt><dd class="font-mono text-xs">{event.departmentId}</dd></div>
							{/if}
						</dl>
						<details class="mt-3">
							<summary class="cursor-pointer text-sm font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">{m.label_payload()}</summary>
							<pre class="mt-2 overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs">{JSON.stringify(event.payload, null, 2)}</pre>
						</details>
					</div>
				{/if}
			</div>
		{/each}
	</div>

	{#if total > pageSize}
		<nav aria-label="Audit log pagination" class="mt-4 flex items-center justify-between text-sm">
			<p class="text-muted-foreground">Page {currentPage + 1} of {Math.ceil(total / pageSize)}</p>
			<div class="flex gap-2">
				<button onclick={() => { currentPage--; loadEvents(); }} disabled={currentPage === 0}
					class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed">{m.btn_previous()}</button>
				<button onclick={() => { currentPage++; loadEvents(); }} disabled={(currentPage + 1) * pageSize >= total}
					class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed">{m.btn_next()}</button>
			</div>
		</nav>
	{/if}
{/if}
