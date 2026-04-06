<script lang="ts">
	import * as m from '$paraglide/messages';

	let { status }: { status: string } = $props();

	/**
	 * Maps API status enum values to translated display labels + styling.
	 * Wrapped in $derived so the labels update when the language changes.
	 * Covers document statuses, user statuses, version statuses, and
	 * workflow step statuses — any string that passes through StatusBadge.
	 */
	const c = $derived.by(() => {
		const config: Record<string, { bg: string; text: string; label: string }> = {
			// Document statuses
			DRAFT: { bg: 'bg-muted', text: 'text-muted-foreground', label: m.status_draft() },
			IN_REVIEW: { bg: 'bg-warning/20', text: 'text-warning', label: m.status_in_review() },
			PUBLISHED: { bg: 'bg-primary/10', text: 'text-primary', label: m.status_published() },
			APPROVED: { bg: 'bg-success/20', text: 'text-success', label: m.status_approved() },
			REJECTED: { bg: 'bg-destructive/10', text: 'text-destructive', label: m.status_rejected() },
			ARCHIVED: { bg: 'bg-muted', text: 'text-muted-foreground', label: m.status_archived() },
			SUPERSEDED: { bg: 'bg-muted', text: 'text-muted-foreground', label: m.status_superseded() },
			LEGAL_HOLD: { bg: 'bg-destructive/10', text: 'text-destructive', label: m.status_legal_hold() },
			LOCKED: { bg: 'bg-warning/20', text: 'text-warning', label: m.status_locked() },
			// User / general statuses
			ACTIVE: { bg: 'bg-success/20', text: 'text-success', label: m.status_active() },
			INACTIVE: { bg: 'bg-muted', text: 'text-muted-foreground', label: m.status_inactive() },
			// Workflow step statuses
			WAITING: { bg: 'bg-muted', text: 'text-muted-foreground', label: m.status_waiting() },
			IN_PROGRESS: { bg: 'bg-warning/20', text: 'text-warning', label: m.status_in_progress() },
			SKIPPED: { bg: 'bg-muted', text: 'text-muted-foreground', label: m.status_skipped() },
		};
		return config[status] ?? { bg: 'bg-muted', text: 'text-muted-foreground', label: status };
	});
</script>

<span
	class="inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium {c.bg} {c.text}"
	aria-label="{m.label_status()}: {c.label}"
>
	{c.label}
</span>
