<script lang="ts">
	import type { TaxonomyTreeNode } from '$lib/types/api';
	import TreeView from './TreeView.svelte';

	let {
		nodes,
		selectedId = $bindable(''),
		level = 1,
		onSelect
	}: {
		nodes: TaxonomyTreeNode[];
		selectedId?: string;
		level?: number;
		onSelect?: (id: string) => void;
	} = $props();

	let expandedIds = $state<Set<string>>(new Set());

	function toggleExpand(id: string) {
		const next = new Set(expandedIds);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		expandedIds = next;
	}

	function selectNode(id: string) {
		selectedId = id;
		onSelect?.(id);
	}

	function handleKeydown(e: KeyboardEvent, node: TaxonomyTreeNode) {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			selectNode(node.term.id);
		} else if (e.key === 'ArrowRight' && node.children.length > 0) {
			e.preventDefault();
			const next = new Set(expandedIds);
			next.add(node.term.id);
			expandedIds = next;
		} else if (e.key === 'ArrowLeft') {
			e.preventDefault();
			const next = new Set(expandedIds);
			next.delete(node.term.id);
			expandedIds = next;
		}
	}
</script>

<ul role={level === 1 ? 'tree' : 'group'} class="space-y-0.5">
	{#each nodes as node}
		{@const hasChildren = node.children.length > 0}
		{@const isExpanded = expandedIds.has(node.term.id)}
		{@const isSelected = selectedId === node.term.id}

		<li
			role="treeitem"
			aria-expanded={hasChildren ? isExpanded : undefined}
			aria-selected={isSelected}
			aria-level={level}
		>
			<div
				class="flex items-center gap-1 rounded-md px-2 py-1.5 text-sm cursor-pointer transition"
				class:bg-primary={isSelected}
				class:text-primary-foreground={isSelected}
				class:hover:bg-muted={!isSelected}
				style="padding-left: {(level - 1) * 1.25 + 0.5}rem"
				onclick={() => selectNode(node.term.id)}
				onkeydown={(e) => handleKeydown(e, node)}
				tabindex="0"
				role="button"
			>
				{#if hasChildren}
					<button
						class="flex h-5 w-5 shrink-0 items-center justify-center rounded text-xs hover:bg-muted-foreground/10 focus:outline-2 focus:outline-ring"
						onclick={(e: MouseEvent) => { e.stopPropagation(); toggleExpand(node.term.id); }}
						aria-label={isExpanded ? `Collapse ${node.term.label}` : `Expand ${node.term.label}`}
						tabindex="-1"
					>
						{isExpanded ? '▼' : '▶'}
					</button>
				{:else}
					<span class="w-5 shrink-0" aria-hidden="true"></span>
				{/if}

				<span class="truncate">{node.term.label}</span>

				{#if node.term.source === 'AI_GENERATED'}
					<span
						class="ml-auto shrink-0 rounded bg-accent/20 px-1 text-[10px] font-medium text-accent-foreground"
						aria-label="AI generated term"
					>
						AI
					</span>
				{/if}
			</div>

			{#if hasChildren && isExpanded}
				<TreeView
					nodes={node.children}
					bind:selectedId
					level={level + 1}
					{onSelect}
				/>
			{/if}
		</li>
	{/each}
</ul>
