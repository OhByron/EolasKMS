<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { user } from '$lib/auth';
	import * as m from '$paraglide/messages';

	/**
	 * In-browser document preview. Inspects the document version's MIME
	 * type and dispatches to the right renderer:
	 *
	 *   - PDF         → pdf.js, dynamically imported so the ~1MB bundle
	 *                   only ships when someone actually opens a PDF
	 *   - Image       → native <img>
	 *   - Text / code → streamed text content in a monospaced <pre>
	 *   - Markdown    → text mode (simple), rendered view is a later pass
	 *   - Everything else → "Download original" fallback
	 *
	 * ## Why fetch bytes through Kosha, not a MinIO presigned URL
	 *
	 * Production deployments do not expose MinIO to the browser — it
	 * lives on an internal network. Proxying through Kosha is the only
	 * portable option, and it also gives us an audit trail of who
	 * previewed what without relying on S3 access logs.
	 *
	 * ## Pass 4 scope
	 *
	 * Office docs (.docx, .xlsx, .pptx, .odt) render via the
	 * "download only" branch in the base Pass 4.1 ship. They become
	 * real previews once the LibreOffice sidecar lands (opt-in compose
	 * profile — see the roadmap). We deliberately did not conditionalise
	 * the UI on sidecar presence; when the sidecar is wired, the
	 * backend will convert Office → PDF and this component sees a PDF
	 * content-type for the preview request, so the PDF branch runs.
	 * No frontend change needed when the sidecar lands.
	 */

	interface Props {
		documentId: string;
		versionId: string;
		contentType: string | null;
		fileName: string;
		/** Null/blank for pre-Pass-4.1 uploads whose bytes were never persisted. */
		storageKey?: string | null;
	}

	const { documentId, versionId, contentType, fileName, storageKey }: Props = $props();

	// Pre-Pass-4.1 uploads discarded bytes after Tika extraction — MinIO has
	// nothing to stream. We catch this up-front so the user sees a clear message
	// rather than a confusing 404 from the download button.
	const hasBytesInStorage = $derived(!!storageKey);

	const previewUrl = $derived(
		`/api/v1/documents/${documentId}/versions/${versionId}/preview`,
	);

	// Classify up-front so the template can branch cleanly. Kept as a
	// pure function of contentType + fileName so adding a new type is
	// a one-line change.
	type Kind = 'pdf' | 'image' | 'text' | 'markdown' | 'office' | 'unsupported';
	const kind = $derived<Kind>(classify(contentType, fileName));

	/**
	 * Office MIME types the LibreOffice sidecar can convert to PDF.
	 * Kept in sync with `OfficePreviewService.OFFICE_MIME_TYPES` on
	 * the backend. Adding a new type is a one-line change here + one
	 * on the backend.
	 */
	const OFFICE_MIME_TYPES: Set<string> = new Set([
		'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
		'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
		'application/vnd.openxmlformats-officedocument.presentationml.presentation',
		'application/msword',
		'application/vnd.ms-excel',
		'application/vnd.ms-powerpoint',
		'application/vnd.oasis.opendocument.text',
		'application/vnd.oasis.opendocument.spreadsheet',
		'application/vnd.oasis.opendocument.presentation',
		'application/rtf',
		'text/rtf',
	]);

	function classify(mime: string | null, name: string): Kind {
		const lower = (mime ?? '').toLowerCase();
		if (lower === 'application/pdf' || name.toLowerCase().endsWith('.pdf')) return 'pdf';
		if (lower.startsWith('image/')) return 'image';
		if (lower === 'text/markdown' || name.toLowerCase().endsWith('.md')) return 'markdown';
		if (
			lower.startsWith('text/') ||
			lower === 'application/json' ||
			lower === 'application/xml' ||
			lower === 'application/javascript'
		) {
			return 'text';
		}
		// Office docs get the same treatment as PDFs: fetch from the
		// preview endpoint (which triggers LibreOffice conversion on the
		// backend if the sidecar is running) and render via pdf.js. If
		// the sidecar is absent the backend returns 503 and we fall
		// back to the download button — no frontend config needed.
		if (OFFICE_MIME_TYPES.has(lower)) return 'office';
		return 'unsupported';
	}

	// ── State ────────────────────────────────────────────────────

	let loading = $state(true);
	let error = $state<string | null>(null);
	let textContent = $state<string | null>(null);
	let imageBlobUrl = $state<string | null>(null);
	let pdfCanvasContainer = $state<HTMLDivElement | null>(null);

	// Track the pdf.js document so we can clean it up on destroy.
	let pdfDoc: { destroy: () => void } | null = null;

	onMount(() => {
		if (kind === 'pdf' || kind === 'office') {
			// Office docs go through the same pdf.js path because the
			// backend's preview endpoint converts them to PDF via the
			// LibreOffice sidecar and returns application/pdf. If the
			// sidecar is down, renderPdf() catches the fetch error and
			// sets `error`, which the template renders as a download
			// fallback — no special "office failed" branch needed.
			renderPdf();
		} else if (kind === 'image') {
			loadImage();
		} else if (kind === 'text' || kind === 'markdown') {
			loadText();
		} else {
			loading = false;
		}
	});

	onDestroy(() => {
		if (imageBlobUrl) URL.revokeObjectURL(imageBlobUrl);
		pdfDoc?.destroy();
	});

	async function fetchWithAuth(): Promise<Response> {
		// The preview endpoint is method-security protected and expects
		// a Bearer token like every other /api/v1 request. We can't use
		// the `api.request` helper because it parses JSON — we need the
		// raw Response body here.
		let token = '';
		const unsub = user.subscribe((u) => {
			token = u?.accessToken ?? '';
		});
		unsub();

		return fetch(previewUrl, {
			headers: token ? { Authorization: `Bearer ${token}` } : {},
		});
	}

	async function loadImage() {
		try {
			const res = await fetchWithAuth();
			if (!res.ok) throw new Error(`Preview failed: HTTP ${res.status}`);
			const blob = await res.blob();
			imageBlobUrl = URL.createObjectURL(blob);
		} catch (e: any) {
			error = e.message ?? 'Failed to load image';
		} finally {
			loading = false;
		}
	}

	async function loadText() {
		try {
			const res = await fetchWithAuth();
			if (!res.ok) throw new Error(`Preview failed: HTTP ${res.status}`);
			textContent = await res.text();
		} catch (e: any) {
			error = e.message ?? 'Failed to load text';
		} finally {
			loading = false;
		}
	}

	// A plain <a href="/api/..."> download won't carry the Bearer token,
	// so we fetch with auth, wrap in a blob URL, and trigger the
	// browser's save-as dialog by clicking a synthetic anchor. This is
	// the same pattern the initial-upload flow uses for its checksum
	// preview — works reliably across browsers.
	async function downloadOriginal() {
		try {
			const res = await fetchWithAuth();
			if (!res.ok) throw new Error(`Download failed: HTTP ${res.status}`);
			const blob = await res.blob();
			const url = URL.createObjectURL(blob);
			const a = document.createElement('a');
			a.href = url;
			a.download = fileName;
			document.body.appendChild(a);
			a.click();
			document.body.removeChild(a);
			// Defer revoke so the browser has time to pull the blob.
			setTimeout(() => URL.revokeObjectURL(url), 1000);
		} catch (e: any) {
			error = e.message ?? 'Download failed';
		}
	}

	async function renderPdf() {
		try {
			// Dynamic import keeps pdf.js out of the base bundle. The
			// legacy build is shipped here for widest browser support;
			// the modern build has smaller size but requires a recent
			// browser we don't want to hard-require yet.
			const pdfjs = await import('pdfjs-dist');

			// Worker bundle. pdf.js needs a worker URL; using the mjs
			// build means Vite can resolve it via ?url import.
			const worker = await import('pdfjs-dist/build/pdf.worker.mjs?url');
			pdfjs.GlobalWorkerOptions.workerSrc = worker.default;

			const res = await fetchWithAuth();
			if (!res.ok) throw new Error(`Preview failed: HTTP ${res.status}`);
			const arrayBuffer = await res.arrayBuffer();

			const loadingTask = pdfjs.getDocument({ data: arrayBuffer });
			pdfDoc = (await loadingTask.promise) as unknown as { destroy: () => void };
			const doc = pdfDoc as unknown as {
				numPages: number;
				getPage: (n: number) => Promise<any>;
				destroy: () => void;
			};

			// Container is always in the DOM (hidden via class toggle) so
			// bind:this is guaranteed to have resolved by the time we get
			// here after the async fetch/parse work above.
			pdfCanvasContainer!.innerHTML = '';

			// Render every page top-to-bottom. For documents with
			// dozens of pages this gets slow — acceptable for v1, the
			// pass-5 virtualised renderer can come later.
			const scale = 1.4;
			for (let pageNum = 1; pageNum <= doc.numPages; pageNum++) {
				const page = await doc.getPage(pageNum);
				const viewport = page.getViewport({ scale });
				const canvas = document.createElement('canvas');
				canvas.width = viewport.width;
				canvas.height = viewport.height;
				canvas.style.display = 'block';
				canvas.style.marginBottom = '1rem';
				canvas.style.maxWidth = '100%';
				canvas.style.boxShadow = '0 1px 3px rgba(0,0,0,0.1)';
				canvas.setAttribute('aria-label', `Page ${pageNum} of ${doc.numPages}`);
				pdfCanvasContainer!.appendChild(canvas);

				const ctx = canvas.getContext('2d');
				if (ctx) {
					await page.render({ canvasContext: ctx, viewport, canvas }).promise;
				}
			}
		} catch (e: any) {
			error = e.message ?? 'Failed to render PDF';
		} finally {
			loading = false;
		}
	}
</script>

<div class="rounded-lg border border-border bg-card">
	<!-- pdf.js canvas container is always in the DOM so bind:this resolves
		 before renderPdf() runs from onMount. Hidden when not the active
		 view; shown once loading=false and kind is pdf/office. -->
	<div
		bind:this={pdfCanvasContainer}
		class="max-h-[80vh] overflow-y-auto p-4"
		class:hidden={loading || !!error || (kind !== 'pdf' && kind !== 'office') || !hasBytesInStorage}
		aria-label="PDF preview"
	></div>

	{#if !hasBytesInStorage}
		<div class="flex h-48 flex-col items-center justify-center gap-2 p-8 text-center">
			<p class="text-sm text-muted-foreground">
				{m.preview_unavailable_pre41()}
			</p>
			<p class="text-xs text-muted-foreground">
				{m.preview_unavailable_reupload()}
			</p>
		</div>
	{:else if loading}
		<div class="flex h-64 items-center justify-center p-8" role="status" aria-live="polite">
			<p class="text-sm text-muted-foreground">{m.preview_loading()}</p>
		</div>
	{:else if error}
		<div
			class="flex h-64 flex-col items-center justify-center gap-2 p-8"
			role="alert"
		>
			<p class="text-sm text-destructive">{error}</p>
			<button
				type="button"
				onclick={downloadOriginal}
				class="text-xs text-primary underline focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			>
				{m.preview_download_instead()}
			</button>
		</div>
	{:else if kind === 'image' && imageBlobUrl}
		<div class="flex justify-center bg-muted/20 p-4">
			<img
				src={imageBlobUrl}
				alt={fileName}
				class="max-h-[80vh] max-w-full rounded-md object-contain"
			/>
		</div>
	{:else if (kind === 'text' || kind === 'markdown') && textContent !== null}
		<pre
			class="max-h-[80vh] overflow-auto whitespace-pre-wrap break-words p-4 text-xs font-mono text-foreground"
			aria-label="Text preview">{textContent}</pre>
	{:else if kind !== 'pdf' && kind !== 'office'}
		<div class="flex h-64 flex-col items-center justify-center gap-3 p-8 text-center">
			<p class="text-sm text-muted-foreground">
				{m.preview_unavailable_type()}{contentType ? ` (${contentType})` : ''}.
			</p>
			<button
				type="button"
				onclick={downloadOriginal}
				class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			>
				Download {fileName}
			</button>
		</div>
	{/if}
</div>
