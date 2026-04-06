<script lang="ts">
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import * as m from '$paraglide/messages';
</script>

<svelte:head>
	<title>About - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title="About {m.nav_app_title()}" />

<div class="mt-6 max-w-3xl space-y-8">
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Eòlas</h2>
		<p class="mt-1 text-xs text-muted-foreground italic">
			/əʊləs/ - Scottish Gaelic for "knowledge"
		</p>
		<p class="mt-3 text-sm leading-relaxed text-muted-foreground">
			Eòlas is an open-source, self-hosted Knowledge Management System for
			small and medium businesses. It handles configurable workflows,
			retention policies, legal hold, electronic signatures, AI-powered
			analysis, and full-text search. The whole stack deploys with a single
			<code class="text-xs">docker compose up</code> command.
		</p>
		<p class="mt-2 text-sm leading-relaxed text-muted-foreground">
			23 languages. WCAG 2.2 AA accessible. Cloud-agnostic.
			No per-user licence fees. No vendor lock-in.
		</p>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Heritage</h2>
		<p class="mt-3 text-sm leading-relaxed text-muted-foreground">
			Eòlas is a re-engineering of <strong>QwikDocs</strong>, a document
			management system designed and built in the early 2000s. QwikDocs
			(later known as Entrepot) ran in production for over twenty years.
			Its domain patterns, workflow concepts, and retention model are the
			foundation of Eòlas.
		</p>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">The QwikDocs Team</h2>
		<p class="mt-3 text-sm text-muted-foreground">
			The people who made the original system a reality:
		</p>
		<ul class="mt-4 space-y-3">
			{#each [
				{ name: 'Byron Bignell', role: 'Lead architect, developer and analyst' },
				{ name: 'Dave MacInnis', role: 'Co-architect and developer' },
				{ name: 'Nolan Dubeau', role: 'Co-architect and developer' },
				{ name: 'Gustavo Jabbaz', role: 'Co-architect and developer' },
				{ name: 'Clayton Partridge', role: 'Co-architect and developer' },
				{ name: 'Susan Shandling', role: 'Business analysis and design' },
			] as person}
				<li class="flex items-center gap-3">
					<div
						class="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary"
						aria-hidden="true"
					>
						{person.name.charAt(0)}
					</div>
					<div>
						<p class="text-sm font-medium">{person.name}</p>
						<p class="text-xs text-muted-foreground">{person.role}</p>
					</div>
				</li>
			{/each}
		</ul>
		<p class="mt-4 text-sm text-muted-foreground">
			Without this team there would be no KMS application, and no
			foundation for Eòlas to build on.
		</p>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Built with Claude</h2>
		<p class="mt-3 text-sm leading-relaxed text-muted-foreground">
			Eòlas was designed and developed with
			<strong>Claude</strong> from
			<a href="https://anthropic.com" target="_blank" rel="noopener"
				class="text-primary underline hover:opacity-80">Anthropic</a>.
			Backend, frontend, database migrations, workflow engine, AI pipeline,
			23 languages of i18n, production hardening, and documentation were
			all built through collaborative sessions between a software
			designer/developer and Claude Code.
		</p>
		<p class="mt-2 text-sm leading-relaxed text-muted-foreground">
			Thanks to the Anthropic team for the tooling that made this possible.
			In the hands of an experienced software designer and developer,
			there are no limits to what can be accomplished.
		</p>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Technology</h2>
		<div class="mt-4 grid gap-3 text-sm sm:grid-cols-2">
			{#each [
				['Backend', 'Kotlin + Spring Boot 3.4'],
				['Frontend', 'SvelteKit 5 + Tailwind CSS'],
				['Database', 'PostgreSQL 16'],
				['Search', 'OpenSearch'],
				['Storage', 'MinIO (S3-compatible)'],
				['Auth', 'Keycloak 24'],
				['Messaging', 'NATS JetStream'],
				['AI', 'Python sidecar (spaCy, LangChain, Tesseract)'],
				['Preview', 'LibreOffice headless + pdf.js'],
				['i18n', 'Paraglide.js (23 languages)'],
				['Observability', 'Micrometer + Prometheus + Grafana'],
				['Orchestration', 'Docker Compose / Kubernetes-ready'],
			] as [label, value]}
				<div class="rounded-md border border-border px-3 py-2">
					<dt class="text-xs font-medium text-muted-foreground">{label}</dt>
					<dd class="font-medium">{value}</dd>
				</div>
			{/each}
		</div>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Licence</h2>
		<p class="mt-3 text-sm leading-relaxed text-muted-foreground">
			Eòlas is open-source software. No per-user fee, no annual
			licence, no feature gating. The only cost is the infrastructure
			it runs on.
		</p>
	</section>

	<footer class="pb-8 text-center text-xs text-muted-foreground">
		&copy; 2026 Eòlas &middot; v1.0.0
	</footer>
</div>
