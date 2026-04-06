package dev.kosha.search

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.http.HttpHost
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.search.SearchRequest
import org.opensearch.client.RequestOptions
import org.opensearch.client.RestClient
import org.opensearch.client.RestHighLevelClient
import org.opensearch.client.indices.CreateIndexRequest
import org.opensearch.client.indices.GetIndexRequest
import org.opensearch.common.xcontent.XContentType
import org.opensearch.index.query.BoolQueryBuilder
import org.opensearch.index.query.QueryBuilders
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.search.fetch.subphase.highlight.HighlightBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct

@Service
class OpenSearchService(
    @Value("\${kosha.opensearch.url}") private val opensearchUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val INDEX_NAME = "eolas-documents"
    }

    private var client: RestHighLevelClient? = null

    @PostConstruct
    fun init() {
        try {
            val url = java.net.URI.create(opensearchUrl)
            val host = HttpHost(url.host, url.port, url.scheme)
            client = RestHighLevelClient(RestClient.builder(host))
            ensureIndex()
            log.info("Connected to OpenSearch at {}", opensearchUrl)
        } catch (ex: Exception) {
            log.warn("Could not connect to OpenSearch at {}: {}", opensearchUrl, ex.message)
        }
    }

    private fun ensureIndex() {
        val c = client ?: return
        try {
            val exists = c.indices().exists(GetIndexRequest(INDEX_NAME), RequestOptions.DEFAULT)
            if (!exists) {
                val req = CreateIndexRequest(INDEX_NAME)
                req.mapping(
                    mapOf(
                        "properties" to mapOf(
                            "title" to mapOf("type" to "text", "analyzer" to "standard"),
                            "description" to mapOf("type" to "text", "analyzer" to "standard"),
                            "content" to mapOf("type" to "text", "analyzer" to "standard"),
                            "departmentName" to mapOf("type" to "text", "analyzer" to "standard"),
                            "departmentId" to mapOf("type" to "keyword"),
                            "status" to mapOf("type" to "keyword"),
                            "docNumber" to mapOf("type" to "keyword"),
                            "categoryName" to mapOf("type" to "text"),
                            "primaryOwnerName" to mapOf("type" to "text"),
                            "createdAt" to mapOf("type" to "date"),
                            "tags" to mapOf("type" to "keyword"),
                        ),
                    ),
                )
                c.indices().create(req, RequestOptions.DEFAULT)
                log.info("Created OpenSearch index '{}'", INDEX_NAME)
            }
        } catch (ex: Exception) {
            log.warn("Could not ensure OpenSearch index: {}", ex.message)
        }
    }

    fun indexDocument(doc: DocumentIndexData) {
        val c = client ?: return
        try {
            val json = objectMapper.writeValueAsString(doc)
            val req = IndexRequest(INDEX_NAME)
                .id(doc.id)
                .source(json, XContentType.JSON)
            c.index(req, RequestOptions.DEFAULT)
            log.debug("Indexed document {} ({})", doc.id, doc.title)
        } catch (ex: Exception) {
            log.warn("Failed to index document {}: {}", doc.id, ex.message)
        }
    }

    fun search(request: SearchQuery): SearchResponse {
        val c = client ?: return SearchResponse(emptyList(), 0, request.page, request.size)
        try {
            val boolQuery = BoolQueryBuilder()

            if (request.query.isNotBlank()) {
                boolQuery.must(
                    QueryBuilders.multiMatchQuery(request.query)
                        .field("title", 3.0f)
                        .field("description", 2.0f)
                        .field("content", 1.0f)
                        .field("docNumber", 2.0f)
                        .field("tags", 1.0f)
                        .field("categoryName", 1.0f)
                        .field("primaryOwnerName", 1.0f),
                )
            }

            request.departmentId?.let { deptId ->
                boolQuery.filter(QueryBuilders.termQuery("departmentId", deptId))
            }
            request.statuses?.takeIf { it.isNotEmpty() }?.let { statuses ->
                boolQuery.filter(QueryBuilders.termsQuery("status", statuses))
            }
            request.dateFrom?.let { from ->
                boolQuery.filter(QueryBuilders.rangeQuery("createdAt").gte(from))
            }
            request.dateTo?.let { to ->
                boolQuery.filter(QueryBuilders.rangeQuery("createdAt").lte(to))
            }

            val sourceBuilder = SearchSourceBuilder()
                .query(boolQuery)
                .from(request.page * request.size)
                .size(request.size)
                .highlighter(
                    HighlightBuilder()
                        .field("content", 200, 1)
                        .field("title", 200, 1)
                        .field("description", 200, 1)
                        .preTags("<mark>")
                        .postTags("</mark>"),
                )

            val searchReq = SearchRequest(INDEX_NAME).source(sourceBuilder)
            val response = c.search(searchReq, RequestOptions.DEFAULT)

            val hits = response.hits.hits.map { hit ->
                val source = objectMapper.readValue(hit.sourceAsString, DocumentIndexData::class.java)
                val snippet = hit.highlightFields.values.firstOrNull()?.fragments()?.firstOrNull()?.string()
                SearchHit(
                    id = source.id,
                    title = source.title,
                    docNumber = source.docNumber,
                    departmentName = source.departmentName,
                    status = source.status,
                    snippet = snippet,
                    relevance = hit.score,
                    taxonomyTerms = source.tags ?: emptyList(),
                    createdAt = source.createdAt,
                )
            }

            return SearchResponse(
                results = hits,
                total = response.hits.totalHits?.value ?: 0,
                page = request.page,
                size = request.size,
            )
        } catch (ex: Exception) {
            log.error("Search failed: {}", ex.message, ex)
            return SearchResponse(emptyList(), 0, request.page, request.size)
        }
    }

    fun suggest(query: String, limit: Int = 10): List<String> {
        val c = client ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return try {
            val sourceBuilder = SearchSourceBuilder()
                .query(QueryBuilders.matchPhrasePrefixQuery("title", query))
                .size(limit)
                .fetchSource(arrayOf("title"), null)

            val response = c.search(
                SearchRequest(INDEX_NAME).source(sourceBuilder),
                RequestOptions.DEFAULT,
            )
            response.hits.hits.mapNotNull {
                objectMapper.readValue(it.sourceAsString, DocumentIndexData::class.java).title
            }.distinct()
        } catch (ex: Exception) {
            log.warn("Suggest failed: {}", ex.message)
            emptyList()
        }
    }
}

data class DocumentIndexData(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    val content: String? = null,
    val departmentName: String = "",
    val departmentId: String = "",
    val status: String = "",
    val docNumber: String? = null,
    val categoryName: String? = null,
    val primaryOwnerName: String? = null,
    val createdAt: String? = null,
    val tags: List<String>? = null,
)

data class SearchQuery(
    val query: String,
    val departmentId: String? = null,
    val statuses: List<String>? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val page: Int = 0,
    val size: Int = 20,
)

data class SearchHit(
    val id: String,
    val title: String,
    val docNumber: String?,
    val departmentName: String,
    val status: String,
    val snippet: String?,
    val relevance: Float,
    val taxonomyTerms: List<String>,
    val createdAt: String?,
)

data class SearchResponse(
    val results: List<SearchHit>,
    val total: Long,
    val page: Int,
    val size: Int,
)
