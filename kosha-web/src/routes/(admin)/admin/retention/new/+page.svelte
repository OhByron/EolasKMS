<script lang="ts">
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';

	let name = $state('');
	let description = $state('');
	let retentionAmount = $state(7);
	let retentionUnit = $state('years');
	let reviewInterval = $state('');
	let actionOnExpiry = $state('ARCHIVE');
	let saving = $state(false);
	let error = $state('');

	async function handleSubmit(e: Event) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			await api.retention.create({
				name,
				description: description || undefined,
				retentionPeriod: `${retentionAmount} ${retentionUnit}`,
				reviewInterval: reviewInterval || undefined,
				actionOnExpiry
			});
			goto('/admin/retention');
		} catch (e: any) {
			error = e.message;
		} finally {
			saving = false;
		}
	}
</script>

<svelte:head>
	<title>Create Retention Policy - Kosha</title>
</svelte:head>

<PageHeader title="Create Retention Policy">
	<a href="/admin/retention" class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
		← Back
	</a>
</PageHeader>

{#if error}
	<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
{/if}

<form onsubmit={handleSubmit} class="mt-6 max-w-lg space-y-4">
	<div>
		<label for="pol-name" class="block text-sm font-medium">Name <span class="text-destructive">*</span></label>
		<input id="pol-name" type="text" bind:value={name} required maxlength="200"
			class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
	</div>
	<div>
		<label for="pol-desc" class="block text-sm font-medium">Description</label>
		<textarea id="pol-desc" bind:value={description} rows="3"
			class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring"></textarea>
	</div>
	<div>
		<label for="pol-amount" class="block text-sm font-medium">Retention Period <span class="text-destructive">*</span></label>
		<div class="mt-1 flex gap-2">
			<input id="pol-amount" type="number" bind:value={retentionAmount} min="1" required
				class="w-24 rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
			<select bind:value={retentionUnit}
				class="rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
				<option value="days">Days</option>
				<option value="months">Months</option>
				<option value="years">Years</option>
			</select>
		</div>
	</div>
	<div>
		<label for="pol-review" class="block text-sm font-medium">Review Interval</label>
		<select id="pol-review" bind:value={reviewInterval}
			class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
			<option value="">No scheduled review</option>
			<option value="30 days">Monthly</option>
			<option value="90 days">Quarterly</option>
			<option value="180 days">Semi-annually</option>
			<option value="365 days">Annually</option>
		</select>
	</div>
	<div>
		<label for="pol-action" class="block text-sm font-medium">Action on Expiry <span class="text-destructive">*</span></label>
		<select id="pol-action" bind:value={actionOnExpiry}
			class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
			<option value="ARCHIVE">Archive</option>
			<option value="DELETE">Delete</option>
			<option value="REVIEW">Review</option>
		</select>
	</div>
	<div class="flex justify-end gap-3 pt-4">
		<a href="/admin/retention" class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">Cancel</a>
		<button type="submit" disabled={!name.trim() || saving}
			class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
			{saving ? 'Saving...' : 'Create Policy'}
		</button>
	</div>
</form>
