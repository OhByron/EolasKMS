<script lang="ts">
	import { page } from '$app/state';
	import { user, hasRole, hasAnyRole } from '$lib/auth';

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
				{ label: 'Dashboard', href: '/dashboard', icon: '⊞' },
				{ label: 'Documents', href: '/documents', icon: '📄' },
				{ label: 'Search', href: '/search', icon: '🔍' },
				{ label: 'Taxonomy', href: '/taxonomy', icon: '🌳' }
			]
		},
		{
			title: 'Workflow',
			visible: hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR'),
			items: [{ label: 'Inbox', href: '/inbox', icon: '📥' }]
		},
		{
			title: 'Administration',
			visible: hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN'),
			items: [
				...(hasRole('GLOBAL_ADMIN')
					? [
							{ label: 'Departments', href: '/admin/departments', icon: '🏢' },
							{ label: 'All Users', href: '/admin/users', icon: '👥' },
							{ label: 'Retention', href: '/admin/retention', icon: '🛡' },
							{ label: 'AI Config', href: '/admin/ai', icon: '🧠' },
							{ label: 'Audit Log', href: '/admin/audit', icon: '📜' },
							{ label: 'Taxonomy Mgmt', href: '/admin/taxonomy', icon: '🔗' }
						]
					: [])
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

	{#if $user && !collapsed}
		<div class="border-t border-border px-4 py-3">
			<p class="truncate text-sm font-medium text-sidebar-foreground">{$user.name}</p>
			<p class="truncate text-xs text-muted-foreground">{$user.email}</p>
		</div>
	{/if}
</nav>
