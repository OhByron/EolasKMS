<script lang="ts">
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
</script>

<svelte:head><title>Administration - Eòlas</title></svelte:head>

<PageHeader title="Administration" description="System configuration and management" />

<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
	{#each [
		{ href: '/admin/departments', title: 'Departments', desc: 'Manage departments and teams' },
		{ href: '/admin/users', title: 'Users', desc: 'User management across departments' },
		{ href: '/admin/retention', title: 'Retention Policies', desc: 'Document lifecycle and compliance' },
		{ href: '/admin/ai', title: 'AI Configuration', desc: 'Model settings and prompt tuning' },
		{ href: '/admin/audit', title: 'Audit Log', desc: 'System-wide activity log' },
		{ href: '/admin/taxonomy', title: 'Taxonomy Management', desc: 'Edit taxonomy tree and import seeds' },
		{ href: '/admin/categories', title: 'Document Categories', desc: 'Edit categories and flag those that suggest legal review' },
		{ href: '/admin/mail-gateway', title: 'Mail Gateway', desc: 'Configure outbound email provider and credentials' },
		{ href: '/admin/notification-settings', title: 'Notifications', desc: 'Retention scan cadence and legal review time limit' },
		{ href: '/reports/aging', title: 'Document Aging', desc: 'Age distribution and retention status' },
		{ href: '/reports/critical-items', title: 'Critical Items', desc: 'Overdue retention reviews by severity' },
		{ href: '/reports/legal-holds', title: 'Legal Holds', desc: 'Documents under legal hold' }
	] as card}
		<a
			href={card.href}
			class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<h2 class="text-lg font-semibold">{card.title}</h2>
			<p class="mt-1 text-sm text-muted-foreground">{card.desc}</p>
		</a>
	{/each}
</div>
