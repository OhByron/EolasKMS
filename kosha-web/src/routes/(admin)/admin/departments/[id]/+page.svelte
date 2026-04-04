<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { api } from '$lib/api';
	import type { Department, UserProfile } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let dept = $state<Department | null>(null);
	let users = $state<UserProfile[]>([]);
	let usersTotal = $state(0);
	let loading = $state(true);
	let error = $state('');

	const deptId = $derived(page.params.id);

	onMount(() => loadData());

	async function loadData() {
		loading = true;
		error = '';
		try {
			const [deptRes, usersRes] = await Promise.all([
				api.departments.get(deptId),
				api.departments.users(deptId, 0, 50)
			]);
			dept = deptRes.data;
			users = usersRes.data;
			usersTotal = usersRes.meta?.total ?? users.length;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}
</script>

<svelte:head>
	<title>{dept?.name ?? 'Department'} - Administration - Kosha</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">Loading department...</p>
{:else if error && !dept}
	<ErrorBoundary {error} onRetry={loadData} />
{:else if dept}
	<PageHeader title={dept.name} description={dept.description ?? undefined}>
		<a href="/admin/departments" class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
			← All Departments
		</a>
	</PageHeader>

	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<div class="lg:col-span-2">
			<!-- Users table -->
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="text-sm font-semibold text-muted-foreground">Team Members ({usersTotal})</h2>
				{#if users.length === 0}
					<p class="mt-3 text-sm text-muted-foreground">No users in this department yet.</p>
				{:else}
					<div class="mt-3 overflow-x-auto">
						<table class="w-full text-sm" aria-label="Department members">
							<thead>
								<tr class="border-b border-border">
									<th scope="col" class="pb-2 text-left font-semibold">Name</th>
									<th scope="col" class="pb-2 text-left font-semibold">Email</th>
									<th scope="col" class="pb-2 text-left font-semibold">Role</th>
									<th scope="col" class="pb-2 text-left font-semibold">Status</th>
								</tr>
							</thead>
							<tbody>
								{#each users as u}
									<tr class="border-b border-border">
										<td class="py-2">
											<a href="/admin/users/{u.id}" class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
												{u.displayName}
											</a>
										</td>
										<td class="py-2 text-muted-foreground">{u.email}</td>
										<td class="py-2">
											<span class="rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">{u.role}</span>
										</td>
										<td class="py-2"><StatusBadge status={u.status} /></td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			</section>
		</div>

		<div class="space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">Details</h2>
				<dl class="space-y-2 text-sm">
					<div>
						<dt class="text-muted-foreground">Status</dt>
						<dd><StatusBadge status={dept.status} /></dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Created</dt>
						<dd class="font-medium">{new Date(dept.createdAt).toLocaleDateString()}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Updated</dt>
						<dd class="font-medium">{new Date(dept.updatedAt).toLocaleDateString()}</dd>
					</div>
				</dl>
			</section>
		</div>
	</div>
{/if}
