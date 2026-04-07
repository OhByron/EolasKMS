<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { Department } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import * as m from '$paraglide/messages';

	let departments = $state<Department[]>([]);
	let loading = $state(true);
	let error = $state('');

	// Create dialog state
	let showCreate = $state(false);
	let createName = $state('');
	let createDesc = $state('');
	let createParent = $state('');
	let creating = $state(false);

	onMount(() => loadDepartments());

	async function loadDepartments() {
		loading = true;
		error = '';
		try {
			const res = await api.departments.list(0, 100);
			departments = res.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	async function handleCreate(e: Event) {
		e.preventDefault();
		creating = true;
		try {
			await api.departments.create({
				name: createName,
				description: createDesc || undefined,
				parentDeptId: createParent || undefined
			});
			showCreate = false;
			createName = '';
			createDesc = '';
			createParent = '';
			await loadDepartments();
		} catch (e: any) {
			error = e.message;
		} finally {
			creating = false;
		}
	}
</script>

<svelte:head>
	<title>{m.page_title_departments()} - {m.nav_sidebar_administration()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.page_title_departments()} description={m.admin_desc()}>
	<button
		onclick={() => (showCreate = true)}
		class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		{m.btn_new_department()}
	</button>
</PageHeader>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">{m.app_loading()}</p>
{:else if error && departments.length === 0}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadDepartments} /></div>
{:else}
	{#if error}
		<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
	{/if}

	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm" aria-label="Departments">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_name()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_description()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_status()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_created()}</th>
				</tr>
			</thead>
			<tbody>
				{#each departments as dept}
					<tr class="border-b border-border transition hover:bg-muted/30">
						<td class="px-4 py-3">
							<a href="/admin/departments/{dept.id}" class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
								{dept.name}
							</a>
						</td>
						<td class="px-4 py-3 text-muted-foreground">{dept.description ?? '-'}</td>
						<td class="px-4 py-3"><StatusBadge status={dept.status} /></td>
						<td class="px-4 py-3 text-muted-foreground">{new Date(dept.createdAt).toLocaleDateString()}</td>
					</tr>
				{:else}
					<tr><td colspan="4" class="px-4 py-8 text-center text-muted-foreground">{m.dept_no_departments()}</td></tr>
				{/each}
			</tbody>
		</table>
	</div>
{/if}

<!-- Create dialog -->
{#if showCreate}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
		onclick={(e) => { if (e.target === e.currentTarget) showCreate = false; }}
		onkeydown={(e) => { if (e.key === 'Escape') showCreate = false; }}
	>
		<div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-lg" role="dialog" aria-modal="true" aria-label="Create department">
			<h2 class="text-lg font-semibold">{m.dept_create_title()}</h2>

			<form onsubmit={handleCreate} class="mt-4 space-y-4">
				<div>
					<label for="dept-name" class="block text-sm font-medium">{m.label_name()} <span class="text-destructive">*</span></label>
					<input id="dept-name" type="text" bind:value={createName} required maxlength="200"
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring" />
				</div>
				<div>
					<label for="dept-desc" class="block text-sm font-medium">{m.label_description()}</label>
					<textarea id="dept-desc" bind:value={createDesc} rows="3" maxlength="2000"
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"></textarea>
				</div>
				<div>
					<label for="dept-parent" class="block text-sm font-medium">{m.label_parent_department()}</label>
					<select id="dept-parent" bind:value={createParent}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
						<option value="">{m.dept_parent_none()}</option>
						{#each departments as d}
							<option value={d.id}>{d.name}</option>
						{/each}
					</select>
				</div>
				<div class="flex justify-end gap-3 pt-2">
					<button type="button" onclick={() => (showCreate = false)}
						class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
						{m.btn_cancel()}
					</button>
					<button type="submit" disabled={!createName.trim() || creating}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
						{creating ? 'Creating...' : m.dept_create_title()}
					</button>
				</div>
			</form>
		</div>
	</div>
{/if}
