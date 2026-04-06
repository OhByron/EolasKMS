<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import * as m from '$paraglide/messages';

	/**
	 * Public share page — renders a document preview for anonymous viewers.
	 *
	 * This page lives outside the (app) layout group so it doesn't pull in
	 * the sidebar, topbar, or authentication flow. It's intentionally
	 * minimal: the Eòlas brand, the document title, and either the preview
	 * content or a status message (expired, revoked, password required).
	 *
	 * For v1 the preview shows document metadata + a download hint. A
	 * future pass will add a proxy or signed-URL path so the anonymous
	 * viewer can see the actual rendered content without needing a JWT.
	 */

	interface ResolvedDoc {
		documentId: string;
		documentTitle: string;
		departmentName: string;
		versionId: string;
		versionNumber: string;
		fileName: string;
		contentType: string | null;
	}

	let loading = $state(true);
	let resolved = $state<ResolvedDoc | null>(null);
	let error = $state('');
	let needsPassword = $state(false);
	let passwordInput = $state('');
	let passwordError = $state('');

	const token = $derived(page.params.token ?? '');

	onMount(() => resolveLink());

	async function resolveLink(password?: string) {
		loading = true;
		error = '';
		needsPassword = false;
		passwordError = '';
		try {
			const res = await fetch(`/api/v1/share/${token}`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ password: password ?? null }),
			});

			if (res.status === 200) {
				const json = await res.json();
				resolved = json.data;
			} else if (res.status === 401) {
				const json = await res.json().catch(() => ({}));
				if (json.passwordRequired) {
					needsPassword = true;
					if (password) passwordError = m.share_password_incorrect();
				} else {
					error = 'Authentication required.';
				}
			} else if (res.status === 410) {
				const json = await res.json().catch(() => ({ error: 'This link is no longer available.' }));
				error = json.error ?? 'This link is no longer available.';
			} else if (res.status === 404) {
				error = m.share_link_invalid();
			} else {
				error = `Unexpected error (HTTP ${res.status}).`;
			}
		} catch (e: any) {
			error = e.message ?? 'Failed to load shared document.';
		} finally {
			loading = false;
		}
	}

	function submitPassword() {
		if (!passwordInput.trim()) {
			passwordError = 'Please enter the password.';
			return;
		}
		resolveLink(passwordInput.trim());
	}
</script>

<svelte:head>
	<title>{resolved ? resolved.documentTitle : m.share_subtitle()} - {m.nav_app_title()}</title>
</svelte:head>

<div class="mx-auto max-w-3xl px-6 py-12">
	<!-- Minimal header -->
	<header class="mb-8 text-center">
		<h1 class="text-2xl font-bold">{m.nav_app_title()}</h1>
		<p class="mt-1 text-sm text-gray-500">{m.share_subtitle()}</p>
	</header>

	{#if loading}
		<div class="flex justify-center py-16">
			<p class="text-gray-500">{m.app_loading()}</p>
		</div>
	{:else if needsPassword}
		<div class="mx-auto max-w-sm rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
			<h2 class="text-lg font-semibold">{m.share_password_required()}</h2>
			<p class="mt-2 text-sm text-gray-500">
				{m.share_password_hint()}
			</p>
			<form onsubmit={(e) => { e.preventDefault(); submitPassword(); }} class="mt-4">
				<input
					type="password"
					bind:value={passwordInput}
					placeholder={m.share_password_placeholder()}
					class="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-2 focus:outline-blue-500"
				/>
				{#if passwordError}
					<p class="mt-2 text-sm text-red-600">{passwordError}</p>
				{/if}
				<button
					type="submit"
					class="mt-3 w-full rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-2 focus:outline-offset-2 focus:outline-blue-500"
				>
					{m.btn_view_document()}
				</button>
			</form>
		</div>
	{:else if error}
		<div class="mx-auto max-w-sm rounded-lg border border-gray-200 bg-white p-6 text-center shadow-sm">
			<p class="text-lg font-semibold text-gray-700">{m.share_unavailable()}</p>
			<p class="mt-2 text-sm text-gray-500">{error}</p>
		</div>
	{:else if resolved}
		<div class="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
			<h2 class="text-xl font-semibold">{resolved.documentTitle}</h2>
			<div class="mt-2 flex flex-wrap gap-3 text-sm text-gray-500">
				<span>{resolved.departmentName}</span>
				<span>·</span>
				<span>Version {resolved.versionNumber}</span>
				<span>·</span>
				<span>{resolved.fileName}</span>
			</div>

			<div class="mt-6 rounded-md border border-gray-100 bg-gray-50 p-8 text-center">
				<p class="text-sm text-gray-500">
					{m.share_preview_hint()}
				</p>
				<p class="mt-2 text-xs text-gray-400">
					{resolved.contentType ?? 'Unknown type'} · v{resolved.versionNumber}
				</p>
			</div>
		</div>

		<footer class="mt-8 text-center text-xs text-gray-400">
			Provided via <strong>{m.nav_app_title()}</strong> Knowledge Management System
		</footer>
	{/if}
</div>
