<script lang="ts">
	let { status }: { status: string } = $props();

	const config: Record<string, { bg: string; text: string; label: string }> = {
		DRAFT: { bg: 'bg-muted', text: 'text-muted-foreground', label: 'Draft' },
		IN_REVIEW: { bg: 'bg-warning/20', text: 'text-warning', label: 'In Review' },
		PUBLISHED: { bg: 'bg-primary/10', text: 'text-primary', label: 'Published' },
		APPROVED: { bg: 'bg-success/20', text: 'text-success', label: 'Approved' },
		REJECTED: { bg: 'bg-destructive/10', text: 'text-destructive', label: 'Rejected' },
		ARCHIVED: { bg: 'bg-muted', text: 'text-muted-foreground', label: 'Archived' },
		SUPERSEDED: { bg: 'bg-muted', text: 'text-muted-foreground', label: 'Superseded' },
		LEGAL_HOLD: { bg: 'bg-destructive/10', text: 'text-destructive', label: 'Legal Hold' },
		LOCKED: { bg: 'bg-warning/20', text: 'text-warning', label: 'Locked' }
	};

	const c = $derived(config[status] ?? { bg: 'bg-muted', text: 'text-muted-foreground', label: status });
</script>

<span
	class="inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium {c.bg} {c.text}"
	aria-label="Status: {c.label}"
>
	{c.label}
</span>
