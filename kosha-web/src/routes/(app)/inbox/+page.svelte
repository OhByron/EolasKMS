<script lang="ts">
	import { onMount } from 'svelte';
	import * as m from '$paraglide/messages';
	import { api } from '$lib/api';
	import type { ReviewTask } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let tasks = $state<ReviewTask[]>([]);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let filter = $state<'all' | 'pending'>('pending');

	onMount(() => loadTasks());

	async function loadTasks() {
		loading = true;
		error = '';
		try {
			const res = await api.workflows.myTasks(0, 50);
			tasks = res.data;
			total = res.meta?.total ?? tasks.length;
		} catch (e: any) {
			// API may not exist yet — show empty state gracefully
			if (e.message?.includes('404') || e.message?.includes('Not Found')) {
				tasks = [];
				total = 0;
			} else {
				error = e.message;
			}
		} finally {
			loading = false;
		}
	}

	const filteredTasks = $derived(
		filter === 'all' ? tasks : tasks.filter((t) => t.status === 'PENDING' || t.status === 'IN_PROGRESS')
	);

	function timeAgo(iso: string): string {
		const diff = Date.now() - new Date(iso).getTime();
		const hours = Math.floor(diff / 3600000);
		if (hours < 1) return m.time_just_now();
		if (hours < 24) return m.time_hours_ago({ n: hours });
		const days = Math.floor(hours / 24);
		return m.time_days_ago({ n: days });
	}
</script>

<svelte:head>
	<title>{m.inbox_title()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.inbox_title()} description="{total} task{total !== 1 ? 's' : ''}" />

<!-- Filter tabs -->
<div class="mt-4" role="tablist" aria-label="Filter tasks">
	<button
		role="tab"
		aria-selected={filter === 'pending'}
		onclick={() => (filter = 'pending')}
		class="rounded-md px-3 py-1.5 text-sm font-medium transition focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		class:bg-primary={filter === 'pending'}
		class:text-primary-foreground={filter === 'pending'}
		class:bg-muted={filter !== 'pending'}
		class:text-muted-foreground={filter !== 'pending'}
	>
		{m.inbox_tab_pending()}
	</button>
	<button
		role="tab"
		aria-selected={filter === 'all'}
		onclick={() => (filter = 'all')}
		class="ml-2 rounded-md px-3 py-1.5 text-sm font-medium transition focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		class:bg-primary={filter === 'all'}
		class:text-primary-foreground={filter === 'all'}
		class:bg-muted={filter !== 'all'}
		class:text-muted-foreground={filter !== 'all'}
	>
		{m.inbox_tab_all()}
	</button>
</div>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">{m.inbox_loading()}</p>
{:else if error}
	<div class="mt-6">
		<ErrorBoundary {error} onRetry={loadTasks} />
	</div>
{:else if filteredTasks.length === 0}
	<div class="mt-8 text-center">
		<p class="text-lg text-muted-foreground">{m.inbox_empty()}</p>
		<p class="mt-1 text-sm text-muted-foreground">{m.inbox_empty_hint()}</p>
	</div>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm" aria-label="Review tasks">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.inbox_col_document()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.inbox_col_submitter()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.inbox_col_department()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.inbox_col_step()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.inbox_col_status()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.inbox_col_submitted()}</th>
				</tr>
			</thead>
			<tbody>
				{#each filteredTasks as task}
					<tr class="border-b border-border transition hover:bg-muted/30">
						<td class="px-4 py-3">
							<a
								href="/documents/{task.documentId}/review?workflow={task.workflowInstanceId}&step={task.stepInstanceId}"
								class="font-medium text-primary hover:underline focus:outline-2 focus:outline-offset-2 focus:outline-ring"
							>
								{task.documentTitle}
							</a>
						</td>
						<td class="px-4 py-3 text-muted-foreground">{task.submittedByName}</td>
						<td class="px-4 py-3 text-muted-foreground">{task.documentDepartment}</td>
						<td class="px-4 py-3 text-muted-foreground">{task.stepName}</td>
						<td class="px-4 py-3">
							<StatusBadge status={task.status === 'PENDING' ? 'IN_REVIEW' : task.status} />
						</td>
						<td class="px-4 py-3 text-muted-foreground">{timeAgo(task.submittedAt)}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>
{/if}
