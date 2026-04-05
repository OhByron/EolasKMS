<script lang="ts">
	import { page } from '$app/state';

	const labelMap: Record<string, string> = {
		dashboard: 'Dashboard',
		documents: 'Documents',
		upload: 'Upload',
		search: 'Search',
		taxonomy: 'Taxonomy',
		inbox: 'Inbox',
		profile: 'Profile',
		admin: 'Administration',
		departments: 'Departments',
		users: 'Users',
		retention: 'Retention Policies',
		ai: 'AI Configuration',
		audit: 'Audit Log',
		'mail-gateway': 'Mail Gateway',
		'notification-settings': 'Notifications',
		categories: 'Document Categories',
		reports: 'Reports',
		aging: 'Document Aging',
		'critical-items': 'Critical Items',
		'legal-holds': 'Legal Holds',
		review: 'Review',
		new: 'Create'
	};

	interface Crumb {
		label: string;
		href: string;
	}

	const crumbs: Crumb[] = $derived.by(() => {
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
