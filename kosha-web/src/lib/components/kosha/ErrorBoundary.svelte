<script lang="ts">
	let { error, onRetry }: { error: string; onRetry?: () => void } = $props();
</script>

<div role="alert" class="rounded-lg border border-destructive/50 bg-destructive/5 p-6 text-center">
	<p class="font-medium text-destructive">Something went wrong</p>
	<p class="mt-1 text-sm text-muted-foreground">{error}</p>
	{#if onRetry}
		<button
			onclick={onRetry}
			class="mt-4 rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			Try again
		</button>
	{/if}
</div>
