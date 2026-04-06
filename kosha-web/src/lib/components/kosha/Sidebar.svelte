<script lang="ts">
	import { page } from '$app/state';
	import { user, hasRole, hasAnyRole } from '$lib/auth';
	import * as m from '$paraglide/messages';

	interface NavItem {
		label: string;
		href: string;
		icon: string;
		badge?: number;
	}

	interface NavGroup {
		title: string;
		items: NavItem[];
		visible: boolean;
	}

	let collapsed = $state(false);

	const groups: NavGroup[] = $derived([
		{
			title: 'Main',
			visible: true,
			items: [
				{ label: m.nav_sidebar_dashboard(), href: '/dashboard', icon: '⊞' },
				{ label: m.nav_sidebar_documents(), href: '/documents', icon: '📄' },
				{ label: m.nav_sidebar_search(), href: '/search', icon: '🔍' },
				{ label: m.nav_sidebar_taxonomy(), href: '/taxonomy', icon: '🌳' }
			]
		},
		{
			title: m.nav_sidebar_inbox(),
			visible: hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR'),
			items: [{ label: m.nav_sidebar_inbox(), href: '/inbox', icon: '📥' }]
		},
		{
			title: m.nav_sidebar_administration(),
			visible: hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN'),
			items: [
				...(hasRole('GLOBAL_ADMIN')
					? [
							{ label: m.nav_sidebar_departments(), href: '/admin/departments', icon: '🏢' },
							{ label: m.nav_sidebar_all_users(), href: '/admin/users', icon: '👥' },
							{ label: m.nav_sidebar_retention(), href: '/admin/retention', icon: '🛡' },
							{ label: m.nav_sidebar_ai_config(), href: '/admin/ai', icon: '🧠' },
							{ label: m.nav_sidebar_audit_log(), href: '/admin/audit', icon: '📜' },
							{ label: m.nav_sidebar_taxonomy_mgmt(), href: '/admin/taxonomy', icon: '🔗' },
							{ label: m.nav_sidebar_categories(), href: '/admin/categories', icon: '🏷' },
							{ label: m.nav_sidebar_bulk_import(), href: '/admin/import', icon: '📥' },
							{ label: m.nav_sidebar_mail_gateway(), href: '/admin/mail-gateway', icon: '✉' },
							{ label: m.nav_sidebar_notifications(), href: '/admin/notification-settings', icon: '🔔' },
							{ label: 'Licence', href: '/admin/licence', icon: '🔑' }
						]
					: [])
			]
		},
		{
			title: m.nav_sidebar_reports(),
			visible: hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN'),
			items: [
				{ label: m.nav_sidebar_document_aging(), href: '/reports/aging', icon: '⏳' },
				{ label: m.nav_sidebar_critical_items(), href: '/reports/critical-items', icon: '⚠' },
				{ label: m.nav_sidebar_legal_holds(), href: '/reports/legal-holds', icon: '⚖' }
			]
		}
	]);

	function isActive(href: string): boolean {
		const path: string = page.url?.pathname ?? '';
		const h: string = href;
		if (h === '/dashboard') return path === '/dashboard';
		return path.startsWith(h);
	}

	function toggleCollapse() {
		collapsed = !collapsed;
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem('kosha-sidebar-collapsed', String(collapsed));
		}
	}
</script>

<nav
	id="sidebar-nav"
	aria-label="Main navigation"
	class="flex h-full flex-col border-r border-border bg-sidebar transition-all duration-200"
	class:w-64={!collapsed}
	class:w-16={collapsed}
>
	<div class="flex items-center justify-between border-b border-border px-4 py-3">
		{#if !collapsed}
			<span class="text-sm font-semibold text-sidebar-foreground">Navigation</span>
		{/if}
		<button
			onclick={toggleCollapse}
			class="rounded p-1 text-sidebar-foreground hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
		>
			{collapsed ? '→' : '←'}
		</button>
	</div>

	<div class="flex-1 overflow-y-auto py-2">
		{#each groups.filter((g) => g.visible && g.items.length > 0) as group}
			<div class="mb-2">
				{#if !collapsed}
					<h2 class="px-4 py-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
						{group.title}
					</h2>
				{/if}
				<ul role="list">
					{#each group.items as item}
						<li>
							<a
								href={item.href}
								class="flex items-center gap-3 px-4 py-2 text-sm transition-colors focus:outline-2 focus:outline-offset-[-2px] focus:outline-ring"
								class:bg-sidebar-active={isActive(item.href)}
								class:text-sidebar-active-foreground={isActive(item.href)}
								class:text-sidebar-foreground={!isActive(item.href)}
								class:hover:bg-muted={!isActive(item.href)}
								class:justify-center={collapsed}
								aria-current={isActive(item.href) ? 'page' : undefined}
								title={collapsed ? item.label : undefined}
							>
								<span aria-hidden="true">{item.icon}</span>
								{#if !collapsed}
									<span>{item.label}</span>
									{#if item.badge}
										<span
											class="ml-auto rounded-full bg-destructive px-2 py-0.5 text-xs text-destructive-foreground"
											aria-label="{item.badge} pending"
										>
											{item.badge}
										</span>
									{/if}
								{/if}
							</a>
						</li>
					{/each}
				</ul>
			</div>
		{/each}
	</div>

	<div class="px-4 py-3">
		{#if $user && !collapsed}
			<p class="truncate text-sm font-medium text-sidebar-foreground">{$user.name}</p>
			<p class="truncate text-xs text-muted-foreground">{$user.email}</p>
		{/if}
		<a
			href="/about"
			class="mt-2 block text-xs text-muted-foreground hover:text-sidebar-foreground focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			class:text-center={collapsed}
			class:mt-0={collapsed || !$user}
		>
			{#if collapsed}
				<span title="About Eòlas">ℹ</span>
			{:else}
				About {m.nav_app_title()}
			{/if}
		</a>
	</div>
</nav>
