<script lang="ts">
	import * as m from '$paraglide/messages';
</script>

<svelte:head>
	<title>Import runbook - {m.nav_app_title()}</title>
</svelte:head>

<div class="space-y-6">
	<div>
		<h1 class="text-2xl font-bold">Bulk import runbook</h1>
		<p class="mt-1 text-sm text-muted-foreground">
			Command-line importer for loading pre-existing documents into Eòlas from a directory and CSV manifest.
		</p>
	</div>

	<!-- When to use -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">When to use it</h2>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li>Initial migration from an existing DMS (SharePoint, M-Files, network shares) after exporting files and writing a CSV manifest</li>
			<li>Loading test datasets into a non-production instance</li>
			<li>Bulk re-import after a restore or environment rebuild</li>
		</ul>
	</section>

	<!-- Prerequisites -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Prerequisites</h2>
		<ol class="mt-3 list-decimal space-y-1 pl-5 text-sm text-muted-foreground">
			<li>Eòlas is running and reachable (<code class="rounded bg-muted px-1 text-xs">curl -sf /actuator/health/liveness</code> returns OK)</li>
			<li>A global admin account token can be obtained from Keycloak</li>
			<li>All <strong>users</strong>, <strong>departments</strong>, and <strong>document categories</strong> referenced by the CSV already exist. Unknown references fail the row.</li>
		</ol>
	</section>

	<!-- CSV schema -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">CSV schema</h2>
		<p class="mt-2 text-sm text-muted-foreground">Headers are required on the first line.</p>
		<div class="mt-4 overflow-x-auto">
			<table class="w-full text-sm">
				<thead>
					<tr class="border-b border-border text-left">
						<th class="pb-2 pr-4 font-medium">Column</th>
						<th class="pb-2 pr-4 font-medium">Required</th>
						<th class="pb-2 font-medium">Notes</th>
					</tr>
				</thead>
				<tbody class="text-muted-foreground">
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">file_path</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Path to the file, relative to the root directory</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">title</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Document title as it will appear in Eòlas</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">description</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2">Free text; blank allowed</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">department_name</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Exact match against department name (case-insensitive)</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">category_name</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2">Exact match against document category name; blank means no category</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">owner_email</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Exact match against user email; unknown = row fails</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">tags</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2">Semicolon-separated taxonomy term labels; unknown = row fails</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">requires_legal_review</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2"><code class="text-xs">true</code>/<code class="text-xs">false</code>; blank = false</td>
					</tr>
					<tr>
						<td class="py-2 pr-4"><code class="text-xs">legal_reviewer_email</code></td>
						<td class="py-2 pr-4">Cond.</td>
						<td class="py-2">Required when <code class="text-xs">requires_legal_review=true</code></td>
					</tr>
				</tbody>
			</table>
		</div>
	</section>

	<!-- Example CSV -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Example CSV</h2>
		<pre class="mt-3 overflow-x-auto rounded bg-muted p-4 text-xs leading-relaxed">file_path,title,description,department_name,category_name,owner_email,tags,requires_legal_review,legal_reviewer_email
./policies/travel.pdf,"Travel Expense Policy","Company travel reimbursement policy","Finance","Policy","maria@example.com","travel;expense;policy",false,
./contracts/acme-msa.docx,"Acme MSA","Master services agreement","Legal","Contract","anne@example.com","contract;msa",true,"legal-reviewer@example.com"
./forms/onboarding.docx,"New Hire Onboarding","HR onboarding checklist","Human Resources","Form","frank@example.com","hr;onboarding",false,</pre>
	</section>

	<!-- Running the importer -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Running the importer</h2>
		<p class="mt-2 text-sm text-muted-foreground">Build once with Gradle, then run as a fat jar.</p>
		<pre class="mt-3 overflow-x-auto rounded bg-muted p-4 text-xs leading-relaxed"># Build
./gradlew :kosha-import:bootJar

# Dry-run (validate without writing)
java -jar kosha-import/build/libs/kosha-import.jar \
  --csv ./manifest.csv \
  --root ./documents \
  --api-url http://localhost:8080 \
  --token "$KOSHA_ADMIN_TOKEN" \
  --dry-run

# Real run
java -jar kosha-import/build/libs/kosha-import.jar \
  --csv ./manifest.csv \
  --root ./documents \
  --api-url http://localhost:8080 \
  --token "$KOSHA_ADMIN_TOKEN"</pre>
	</section>

	<!-- Token acquisition -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Token acquisition (dev)</h2>
		<pre class="mt-3 overflow-x-auto rounded bg-muted p-4 text-xs leading-relaxed">KOSHA_ADMIN_TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/kosha/protocol/openid-connect/token" \
  -d grant_type=password \
  -d client_id=kosha-web \
  -d username=admin@kosha.dev \
  -d password=admin | jq -r .access_token)</pre>
	</section>

	<!-- Dry-run validation -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">What dry-run validates</h2>
		<ol class="mt-3 list-decimal space-y-1 pl-5 text-sm text-muted-foreground">
			<li>Parses every row and reports syntactic errors</li>
			<li>Resolves every <code class="text-xs">department_name</code> against departments</li>
			<li>Resolves every <code class="text-xs">category_name</code> against document categories</li>
			<li>Resolves every <code class="text-xs">owner_email</code> against user profiles</li>
			<li>Resolves every <code class="text-xs">legal_reviewer_email</code> against legal review departments</li>
			<li>Resolves every tag in <code class="text-xs">tags</code> against taxonomy terms</li>
			<li>Returns a per-row ok/error verdict</li>
		</ol>
		<p class="mt-3 text-sm text-muted-foreground">No files are touched during dry-run. After a green dry-run, re-run without <code class="text-xs">--dry-run</code> to execute.</p>
	</section>

	<!-- Resumability -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Resumability</h2>
		<p class="mt-2 text-sm text-muted-foreground">
			On each successful row the CLI writes to <code class="text-xs">.import-state.json</code>. Re-running the same CSV skips already-imported rows.
		</p>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li>A crashed or interrupted import can be resumed by re-running the same command</li>
			<li>Partial failures are recoverable: fix the failing row, re-run, only remaining rows are attempted</li>
			<li>To force a fresh import, delete <code class="text-xs">.import-state.json</code> before running</li>
		</ul>
	</section>

	<!-- Error handling -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Error handling</h2>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li><strong>Validation error</strong> (exit 2): per-row error summary, nothing written</li>
			<li><strong>Row error during real run</strong> (e.g. file missing, API 4xx): row marked failed, CLI continues. Summary reports succeeded/failed counts.</li>
			<li><strong>Network error</strong> (exit 3): CLI aborts. Re-run resumes from last completed row.</li>
		</ul>
	</section>

	<!-- Limitations -->
	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">Known limitations (v1)</h2>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li>Only the current version of each document is imported. Version history from the source system is discarded.</li>
			<li>Ownership is fail-loud: unknown <code class="text-xs">owner_email</code> fails the row.</li>
			<li>Imported documents land as DRAFT. The admin decides whether to submit them for review.</li>
			<li>No parallelism: ~1-2 docs/sec depending on AI sidecar load. Batches of 10k+ are practical but not fast.</li>
		</ul>
	</section>

	<div class="pb-4">
		<a href="/admin/import" class="text-sm text-primary underline hover:opacity-80">Back to import</a>
	</div>
</div>
