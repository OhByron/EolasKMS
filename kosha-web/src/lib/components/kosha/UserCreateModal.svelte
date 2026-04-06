<script lang="ts">
	import { api } from '$lib/api';
	import type { Department, ProvisionedUserResponse } from '$lib/types/api';
	import * as m from '$paraglide/messages';

	/**
	 * Reusable user creation modal.
	 *
	 * Props:
	 * - `open`: bindable boolean controlling visibility
	 * - `lockedDepartmentId`: if set, the department field is pre-filled and disabled
	 *   (used from the department detail page where the dept is already chosen)
	 * - `lockedDepartmentName`: display name shown when the dept is locked
	 * - `onCreated`: callback fired after a successful provision, receives the response
	 */
	let {
		open = $bindable(false),
		lockedDepartmentId = null,
		lockedDepartmentName = null,
		onCreated = (_result: ProvisionedUserResponse) => {},
	}: {
		open?: boolean;
		lockedDepartmentId?: string | null;
		lockedDepartmentName?: string | null;
		onCreated?: (result: ProvisionedUserResponse) => void;
	} = $props();

	// Form state
	let displayName = $state('');
	let email = $state('');
	let departmentId = $state(lockedDepartmentId ?? '');
	let role = $state<'GLOBAL_ADMIN' | 'DEPT_ADMIN' | 'EDITOR' | 'CONTRIBUTOR'>('CONTRIBUTOR');
	let useCustomPassword = $state(false);
	let customPassword = $state('');

	let departments = $state<Department[]>([]);
	let loadingDepartments = $state(false);
	let submitting = $state(false);
	let error = $state('');
	let result = $state<ProvisionedUserResponse | null>(null);
	let passwordCopied = $state(false);

	// Load departments when opened without a locked dept
	$effect(() => {
		if (open && !lockedDepartmentId && departments.length === 0) {
			loadDepartments();
		}
		if (open) {
			// Reset state each time it opens
			if (!result) {
				displayName = '';
				email = '';
				departmentId = lockedDepartmentId ?? '';
				role = 'CONTRIBUTOR';
				useCustomPassword = false;
				customPassword = '';
				error = '';
				passwordCopied = false;
			}
		}
	});

	async function loadDepartments() {
		loadingDepartments = true;
		try {
			const res = await api.departments.list(0, 100);
			departments = res.data;
		} catch (e: any) {
			error = `Failed to load departments: ${e.message}`;
		} finally {
			loadingDepartments = false;
		}
	}

	const canSubmit = $derived(
		displayName.trim().length > 0 &&
		email.trim().length > 0 &&
		email.includes('@') &&
		(lockedDepartmentId || departmentId.length > 0) &&
		(!useCustomPassword || customPassword.length >= 8)
	);

	async function submit(e: Event) {
		e.preventDefault();
		if (!canSubmit || submitting) return;

		submitting = true;
		error = '';

		const body = {
			displayName: displayName.trim(),
			email: email.trim(),
			departmentId: (lockedDepartmentId ?? departmentId),
			role,
			temporaryPassword: useCustomPassword && customPassword ? customPassword : null,
		};

		try {
			const res = lockedDepartmentId
				? await api.departments.provisionUser(lockedDepartmentId, body)
				: await api.users.provision(body);
			result = res.data;
			onCreated(res.data);
		} catch (e: any) {
			error = e.message ?? 'Failed to create user';
		} finally {
			submitting = false;
		}
	}

	function close() {
		open = false;
		result = null;
		passwordCopied = false;
	}

	async function copyPassword() {
		if (!result) return;
		try {
			await navigator.clipboard.writeText(result.temporaryPassword);
			passwordCopied = true;
			setTimeout(() => (passwordCopied = false), 2000);
		} catch {
			/* clipboard may be denied — user can still select and copy manually */
		}
	}
</script>

{#if open}
	<!-- Backdrop -->
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-40 bg-black/50"
		onclick={close}
		role="presentation"
	></div>

	<!-- Modal -->
	<div
		class="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-card p-6 shadow-xl"
		role="dialog"
		aria-modal="true"
		aria-labelledby="user-create-title"
	>
		<h2 id="user-create-title" class="text-lg font-semibold">
			{result ? m.user_created_title() : m.user_create_title()}
		</h2>

		{#if result}
			<!-- Success state — show the temp password -->
			<div class="mt-4 space-y-4">
				<p class="text-sm text-muted-foreground">
					{m.user_create_success_desc()}
				</p>

				<div class="rounded-md border border-border bg-background p-3">
					<dl class="space-y-1 text-sm">
						<div class="flex justify-between">
							<dt class="text-muted-foreground">{m.label_name()}</dt>
							<dd class="font-medium">{result.user.displayName}</dd>
						</div>
						<div class="flex justify-between">
							<dt class="text-muted-foreground">{m.label_email()}</dt>
							<dd class="font-mono text-xs">{result.user.email}</dd>
						</div>
						<div class="flex justify-between">
							<dt class="text-muted-foreground">{m.label_role()}</dt>
							<dd class="font-medium">{result.user.role}</dd>
						</div>
					</dl>
				</div>

				<div>
					<label for="temp-pwd" class="block text-sm font-medium">{m.label_temporary_password()}</label>
					<div class="mt-1 flex gap-2">
						<input
							id="temp-pwd"
							type="text"
							readonly
							value={result.temporaryPassword}
							class="flex-1 rounded-md border border-border bg-muted px-3 py-2 font-mono text-sm focus:outline-2 focus:outline-ring"
						/>
						<button
							type="button"
							onclick={copyPassword}
							class="rounded-md border border-border px-3 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring"
						>
							{passwordCopied ? m.user_create_copied() : m.btn_copy()}
						</button>
					</div>
					<p class="mt-1 text-xs text-muted-foreground">
						{m.user_pwd_warning()}
					</p>
				</div>

				<div class="flex justify-end pt-2">
					<button
						type="button"
						onclick={close}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring"
					>
						{m.btn_done()}
					</button>
				</div>
			</div>
		{:else}
			<!-- Form state -->
			<form onsubmit={submit} class="mt-4 space-y-4">
				<div>
					<label for="uc-name" class="block text-sm font-medium">
						{m.label_display_name()} <span class="text-destructive">*</span>
					</label>
					<input
						id="uc-name"
						type="text"
						bind:value={displayName}
						required
						maxlength="200"
						autocomplete="name"
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
				</div>

				<div>
					<label for="uc-email" class="block text-sm font-medium">
						{m.label_email()} <span class="text-destructive">*</span>
					</label>
					<input
						id="uc-email"
						type="email"
						bind:value={email}
						required
						autocomplete="email"
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					/>
					<p class="mt-1 text-xs text-muted-foreground">
						{m.user_create_email_hint()}
					</p>
				</div>

				{#if lockedDepartmentId}
					<div>
						<label class="block text-sm font-medium">{m.label_department()}</label>
						<p class="mt-1 rounded-md border border-border bg-muted px-3 py-2 text-sm">
							{lockedDepartmentName ?? m.user_create_current_department()}
						</p>
					</div>
				{:else}
					<div>
						<label for="uc-dept" class="block text-sm font-medium">
							{m.label_department()} <span class="text-destructive">*</span>
						</label>
						<select
							id="uc-dept"
							bind:value={departmentId}
							required
							class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						>
							<option value="">{loadingDepartments ? m.app_loading() : m.label_select_department()}</option>
							{#each departments as d}
								<option value={d.id}>{d.name}</option>
							{/each}
						</select>
					</div>
				{/if}

				<div>
					<label for="uc-role" class="block text-sm font-medium">{m.label_role()}</label>
					<select
						id="uc-role"
						bind:value={role}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					>
						<option value="CONTRIBUTOR">{m.user_create_role_contributor()}</option>
						<option value="EDITOR">{m.user_create_role_editor()}</option>
						<option value="DEPT_ADMIN">{m.user_create_role_dept_admin()}</option>
						<option value="GLOBAL_ADMIN">{m.user_create_role_global_admin()}</option>
					</select>
				</div>

				<fieldset class="rounded-md border border-border p-3">
					<legend class="px-1 text-sm font-medium">{m.user_create_temp_password()}</legend>
					<div class="space-y-2">
						<label class="flex items-center gap-2 text-sm">
							<input
								type="radio"
								name="pwd-mode"
								checked={!useCustomPassword}
								onchange={() => (useCustomPassword = false)}
							/>
							{m.user_create_auto_generate()}
						</label>
						<label class="flex items-center gap-2 text-sm">
							<input
								type="radio"
								name="pwd-mode"
								checked={useCustomPassword}
								onchange={() => (useCustomPassword = true)}
							/>
							{m.user_create_custom_password()}
						</label>
						{#if useCustomPassword}
							<input
								type="text"
								bind:value={customPassword}
								placeholder={m.user_create_custom_placeholder()}
								minlength="8"
								class="w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
							/>
						{/if}
					</div>
					<p class="mt-2 text-xs text-muted-foreground">
						{m.user_create_password_change_hint()}
					</p>
				</fieldset>

				{#if error}
					<div
						role="alert"
						class="rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive"
					>
						{error}
					</div>
				{/if}

				<div class="flex justify-end gap-2 pt-2">
					<button
						type="button"
						onclick={close}
						disabled={submitting}
						class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50"
					>
						{m.btn_cancel()}
					</button>
					<button
						type="submit"
						disabled={!canSubmit || submitting}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
					>
						{submitting ? m.user_create_creating() : m.user_create_create_user()}
					</button>
				</div>
			</form>
		{/if}
	</div>
{/if}
