<script lang="ts">
	import { page } from '$app/state';
	import * as m from '$paraglide/messages';

	interface Crumb {
		label: string;
		href: string;
	}

	const crumbs: Crumb[] = $derived.by(() => {
		const labelMap: Record<string, string> = {
			dashboard: m.nav_sidebar_dashboard(),
			documents: m.nav_sidebar_documents(),
			upload: m.page_title_upload_doc(),
			search: m.nav_sidebar_search(),
			taxonomy: m.nav_sidebar_taxonomy(),
			inbox: m.nav_sidebar_inbox(),
			profile: m.page_title_profile(),
			admin: m.nav_sidebar_administration(),
			departments: m.nav_sidebar_departments(),
			users: m.nav_sidebar_all_users(),
			retention: m.page_title_retention(),
			ai: m.page_title_ai_config(),
			audit: m.page_title_audit(),
			'mail-gateway': m.page_title_mail_gateway(),
			'notification-settings': m.nav_sidebar_notifications(),
			categories: m.page_title_categories(),
			import: m.nav_sidebar_bulk_import(),
			reports: m.nav_sidebar_reports(),
			aging: m.nav_sidebar_document_aging(),
			'critical-items': m.nav_sidebar_critical_items(),
			'legal-holds': m.nav_sidebar_legal_holds(),
			review: m.review_title(),
			new: m.btn_create(),
			about: 'About',
			versions: m.btn_version_history(),
		};

		const pathname = page.url?.pathname ?? '/';
		const segments = pathname.split('/').filter(Boolean);
		const result: Crumb[] = [];

		let path = '';
		for (const seg of segments) {
			path += `/${seg}`;
			const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-/.test(seg);
			const label = isUuid ? '...' : (labelMap[seg] ?? seg);
			result.push({ label, href: path });
		}

		return result;
	});
</script>

{#if crumbs.length > 0}
	<nav aria-label="Breadcrumb" class="mb-4">
		<ol class="flex items-center gap-1 text-sm text-muted-foreground">
			{#each crumbs as crumb, i}
				{#if i > 0}
					<li aria-hidden="true" class="mx-1">/</li>
				{/if}
				<li>
					{#if i === crumbs.length - 1}
						<span aria-current="page" class="font-medium text-foreground">{crumb.label}</span>
					{:else}
						<a
							href={crumb.href}
							class="hover:text-foreground focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						>
							{crumb.label}
						</a>
					{/if}
				</li>
			{/each}
		</ol>
	</nav>
{/if}
