<script lang="ts">
	import { onMount } from 'svelte';
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
		if (hours < 1) return 'Just now';
		if (hours < 24) return `${hours}h ago`;
		const days = Math.floor(hours / 24);
		if (days === 1) return '1 day ago';
		return `${days} days ago`;
	}
</script>

<svelte:head>
	<title>Review Inbox - Eòlas</title>
</svelte:head>

<PageHeader title="Review Inbox" description="{total} task{total !== 1 ? 's' : ''}" />

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
		Pending
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
		All
	</button>
</div>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading tasks...</p>
{:else if error}
	<div class="mt-6">
		<ErrorBoundary {error} onRetry={loadTasks} />
	</div>
{:else if filteredTasks.length === 0}
	<div class="mt-8 text-center">
		<p class="text-lg text-muted-foreground">No pending review tasks</p>
		<p class="mt-1 text-sm text-muted-foreground">Documents assigned to you for review will appear here.</p>
	</div>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm" aria-label="Review tasks">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th scope="col" class="px-4 py-3 text-left font-semibold">Document</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Submitter</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Department</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Step</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Status</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Submitted</th>
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
