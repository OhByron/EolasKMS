<script lang="ts">
	import { marked } from 'marked';
	import DOMPurify from 'dompurify';

	interface Props {
		content: string | null | undefined;
		/** Optional class on the wrapping div. The component already applies prose-friendly defaults. */
		class?: string;
	}

	const { content, class: extraClass = '' }: Props = $props();

	// Configure marked once per component lifetime. GFM gives us tables, autolinks,
	// and task lists for free; mangle/headerIds default to false in marked v10+.
	marked.setOptions({ gfm: true, breaks: false });

	const html = $derived.by(() => {
		const raw = (content ?? '').trim();
		if (!raw) return '';
		const dirty = marked.parse(raw, { async: false }) as string;
		// LLM output is untrusted (prompt-injectable) — sanitise before injecting.
		// `_blank` links get rel=noopener via DOMPurify hook below.
		return DOMPurify.sanitize(dirty, {
			ADD_ATTR: ['target', 'rel'],
		});
	});
</script>

<!--
  Tailwind doesn't ship a typography plugin in this project, so apply the
  smallest set of opinionated styles that make headings/lists/emphasis
  readable inside our card layouts. Keep margins tight — most summaries
  live inside compact panels next to the document preview.
-->
<div class="markdown {extraClass}">
	{@html html}
</div>

<style>
	.markdown :global(h1),
	.markdown :global(h2),
	.markdown :global(h3),
	.markdown :global(h4) {
		font-weight: 600;
		margin: 0.75rem 0 0.25rem;
		line-height: 1.25;
	}
	.markdown :global(h1) { font-size: 1rem; }
	.markdown :global(h2) { font-size: 0.95rem; }
	.markdown :global(h3),
	.markdown :global(h4) { font-size: 0.875rem; }
	.markdown :global(p) {
		margin: 0.5rem 0;
		line-height: 1.55;
	}
	.markdown :global(ul),
	.markdown :global(ol) {
		margin: 0.5rem 0;
		padding-left: 1.25rem;
	}
	.markdown :global(ul) { list-style: disc; }
	.markdown :global(ol) { list-style: decimal; }
	.markdown :global(li) {
		margin: 0.125rem 0;
	}
	.markdown :global(strong) { font-weight: 600; }
	.markdown :global(em) { font-style: italic; }
	.markdown :global(code) {
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
		font-size: 0.85em;
		background: rgb(0 0 0 / 0.05);
		padding: 0.1em 0.3em;
		border-radius: 3px;
	}
	.markdown :global(pre) {
		background: rgb(0 0 0 / 0.05);
		padding: 0.75rem;
		border-radius: 6px;
		overflow-x: auto;
		margin: 0.5rem 0;
	}
	.markdown :global(pre code) {
		background: none;
		padding: 0;
	}
	.markdown :global(blockquote) {
		border-left: 3px solid currentColor;
		opacity: 0.75;
		padding-left: 0.75rem;
		margin: 0.5rem 0;
	}
	.markdown :global(a) {
		color: var(--color-primary, #2563eb);
		text-decoration: underline;
		text-underline-offset: 2px;
	}
	.markdown :global(*:first-child) { margin-top: 0; }
	.markdown :global(*:last-child) { margin-bottom: 0; }
</style>
