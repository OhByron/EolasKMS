<script lang="ts">
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import * as m from '$paraglide/messages';

	// Card data uses message functions so labels update when the
	// language changes. Wrapped in $derived so Paraglide re-evaluates
	// when languageTag() changes (e.g. via the language picker).
	const cards = $derived([
		{ href: '/admin/departments', title: m.page_title_departments(), desc: m.admin_card_departments_desc() },
		{ href: '/admin/users', title: m.nav_sidebar_all_users(), desc: m.admin_card_users_desc() },
		{ href: '/admin/retention', title: m.page_title_retention(), desc: m.admin_card_retention_desc() },
		{ href: '/admin/ai', title: m.page_title_ai_config(), desc: m.admin_card_ai_desc() },
		{ href: '/admin/audit', title: m.page_title_audit(), desc: m.admin_card_audit_desc() },
		{ href: '/admin/taxonomy', title: m.page_title_taxonomy_mgmt(), desc: m.admin_card_taxonomy_desc() },
		{ href: '/admin/categories', title: m.page_title_categories(), desc: m.admin_card_categories_desc() },
		{ href: '/admin/import', title: m.import_title(), desc: m.admin_card_import_desc() },
		{ href: '/admin/mail-gateway', title: m.page_title_mail_gateway(), desc: m.admin_card_mail_desc() },
		{ href: '/admin/notification-settings', title: m.settings_page_title(), desc: m.admin_card_notifications_desc() },
		{ href: '/admin/licence', title: 'Licence', desc: 'View and manage your Eòlas licence' },
		{ href: '/reports/aging', title: m.nav_sidebar_document_aging(), desc: m.admin_card_aging_desc() },
		{ href: '/reports/critical-items', title: m.nav_sidebar_critical_items(), desc: m.admin_card_critical_desc() },
		{ href: '/reports/legal-holds', title: m.nav_sidebar_legal_holds(), desc: m.admin_card_legal_holds_desc() },
	]);
</script>

<svelte:head><title>{m.admin_title()} - {m.nav_app_title()}</title></svelte:head>

<PageHeader title={m.admin_title()} description={m.admin_desc()} />

<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
	{#each cards as card}
		<a
			href={card.href}
			class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<h2 class="text-lg font-semibold">{card.title}</h2>
			<p class="mt-1 text-sm text-muted-foreground">{card.desc}</p>
		</a>
	{/each}
</div>
