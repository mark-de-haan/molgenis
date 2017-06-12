package org.molgenis.data.elasticsearch;

import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.AtomicLongMap;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.IndexNotFoundException;
import org.molgenis.data.DataService;
import org.molgenis.data.Entity;
import org.molgenis.data.Query;
import org.molgenis.data.Repository;
import org.molgenis.data.aggregation.AggregateQuery;
import org.molgenis.data.aggregation.AggregateResult;
import org.molgenis.data.elasticsearch.index.ElasticsearchIndexSettings;
import org.molgenis.data.elasticsearch.index.MappingsBuilder;
import org.molgenis.data.elasticsearch.request.SearchRequestGenerator;
import org.molgenis.data.elasticsearch.response.ResponseParser;
import org.molgenis.data.elasticsearch.util.*;
import org.molgenis.data.index.IndexingMode;
import org.molgenis.data.index.SearchService;
import org.molgenis.data.meta.model.Attribute;
import org.molgenis.data.meta.model.EntityType;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.util.EntityUtils;
import org.molgenis.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Stream.concat;
import static java.util.stream.StreamSupport.stream;
import static org.molgenis.data.DataConverter.convert;
import static org.molgenis.data.elasticsearch.util.ElasticsearchEntityUtils.toElasticsearchId;
import static org.molgenis.data.support.EntityTypeUtils.createFetchForReindexing;

/**
 * Elasticsearch implementation of the SearchService interface.
 *
 * @author erwin
 */
@Component
public class ElasticsearchService implements SearchService
{
	private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchService.class);

	private static final int BATCH_SIZE = 1000;

	private final ElasticsearchClientFacade elasticsearchClientFacade;
	private final DataService dataService;
	private final ElasticsearchEntityFactory elasticsearchEntityFactory;
	private final DocumentIdGenerator documentIdGenerator;
	private final ResponseParser responseParser = new ResponseParser();
	private final SearchRequestGenerator searchRequestGenerator;

	public ElasticsearchService(ElasticsearchClientFacade elasticsearchClientFacade, DataService dataService,
			ElasticsearchEntityFactory elasticsearchEntityFactory, DocumentIdGenerator documentIdGenerator)
	{
		this.dataService = requireNonNull(dataService);
		this.elasticsearchEntityFactory = requireNonNull(elasticsearchEntityFactory);
		this.documentIdGenerator = requireNonNull(documentIdGenerator);
		this.elasticsearchClientFacade = requireNonNull(elasticsearchClientFacade);
		this.searchRequestGenerator = new SearchRequestGenerator(documentIdGenerator);
	}

	@Override
	public void createIndex(EntityType entityType)
	{
		String indexName = getIndexName(entityType);
		elasticsearchClientFacade.createIndex(indexName, ElasticsearchIndexSettings.getIndexSettings());

		try (XContentBuilder jsonBuilder = XContentFactory.jsonBuilder())
		{
			String documentType = getIndexName(entityType);
			MappingsBuilder.buildMapping(jsonBuilder, entityType, documentIdGenerator);
			elasticsearchClientFacade.createMapping(indexName, documentType, jsonBuilder);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public boolean hasIndex(EntityType entityType)
	{
		String indexName = getIndexName(entityType);
		return elasticsearchClientFacade.indexExists(indexName);
	}

	@Override
	public void deleteIndex(EntityType entityType)
	{
		String documentType = getIndexName(entityType);
		try
		{
			elasticsearchClientFacade.deleteIndex(documentType);
		}
		catch (IndexNotFoundException e)
		{
			// silently ignore
		}
	}

	@Override
	public void rebuildIndex(Repository<? extends Entity> repository)
	{
		EntityType entityType = repository.getEntityType();

		if (hasIndex(entityType))
		{
			LOG.debug("Delete index for repository {}...", repository.getName());
			deleteIndex(entityType);
		}

		createIndex(entityType);
		LOG.trace("Indexing {} repository in batches of size {}...", repository.getName(), BATCH_SIZE);
		repository.forEachBatched(createFetchForReindexing(entityType),
				entities -> index(entityType, entities.stream(), IndexingMode.ADD), BATCH_SIZE);
		LOG.debug("Create index for repository {}...", repository.getName());
	}

	@Override
	public void refreshIndex()
	{
		elasticsearchClientFacade.refreshIndex(null);
	}

	@Override
	public long count(EntityType entityType)
	{
		return count(entityType, null);
	}

	@Override
	public long count(EntityType entityType, Query<Entity> q)
	{
		String documentType = getIndexName(entityType);
		try
		{
			return elasticsearchClientFacade.getCount(q, entityType, documentType, documentType);
		}
		catch (IndexNotFoundException e)
		{
			return 0L;
		}
	}

	@Override
	public Stream<Object> search(EntityType entityType, Query<Entity> q)
	{
		String indexName = getIndexName(entityType);
		ElasticsearchDocumentIdIterable documentIdIterable = new ElasticsearchDocumentIdIterable(q, entityType,
				elasticsearchClientFacade, searchRequestGenerator, indexName, indexName);
		Stream<String> documentIdStream = stream(documentIdIterable.spliterator(), false);
		return ElasticsearchEntityUtils.toEntityIds(documentIdStream);
	}

	@Override
	public Object searchOne(EntityType entityType, Query<Entity> q)
	{
		return search(entityType, q).findFirst().orElse(null);
	}

	private SearchResult search(SearchRequest request)
	{
		String indexName = request.getEntityType() != null ? getIndexName(request.getEntityType()) : null;
		SearchResponse response = elasticsearchClientFacade.search(SearchType.QUERY_THEN_FETCH, request, indexName);
		return responseParser.parseSearchResponse(request, response, dataService);
	}

	@Override
	public AggregateResult aggregate(final EntityType entityType, AggregateQuery aggregateQuery)
	{
		Query<Entity> q = aggregateQuery.getQuery();
		Attribute xAttr = aggregateQuery.getAttributeX();
		Attribute yAttr = aggregateQuery.getAttributeY();
		Attribute distinctAttr = aggregateQuery.getAttributeDistinct();
		SearchRequest searchRequest = SearchRequest.create(entityType, q, xAttr, yAttr, distinctAttr);
		SearchResult searchResults = search(searchRequest);
		return searchResults.getAggregate();
	}

	@Override
	public void index(EntityType entityType, Entity entity, IndexingMode indexingMode)
	{
		LOG.debug("Indexing single {}.{} entity ...", entityType.getId(), entity.getIdValue());
		index(Stream.of(entity), entityType, indexingMode == IndexingMode.UPDATE);
	}

	@Override
	public long index(EntityType entityType, Stream<? extends Entity> entities, IndexingMode indexingMode)
	{
		LOG.debug("Indexing multiple {} entities...", entityType.getId());
		return index(entities, entityType, indexingMode == IndexingMode.UPDATE);
	}

	private long index(Stream<? extends Entity> entityStream, EntityType entityType, boolean addReferences)
	{
		String documentType = getIndexName(entityType);

		Stream<IndexRequest> indexRequestStream = entityStream
				.flatMap(entity -> createIndexRequestStreamForEntity(entity, entityType, documentType, addReferences));

		AtomicLongMap<String> counts = elasticsearchClientFacade.index(indexRequestStream, true);
		return counts.get(documentType);
	}

	/**
	 * Creates an {@link IndexRequest} to reindex an entity. Optionally also creates {@link IndexRequest}s for referencing
	 * entities.
	 *
	 * @param entity                            the entity that should be indexed
	 * @param entityType                        the {@link EntityType} of the entity
	 * @param type                              the sanitized mapping type of the entity
	 * @param addRequestsForReferencingEntities boolean indicating if {@link IndexRequest}s should be added for all
	 *                                          referencing entities.
	 * @return Stream of {@link IndexRequest}s for the entity
	 */
	private Stream<IndexRequest> createIndexRequestStreamForEntity(Entity entity, EntityType entityType, String type,
			boolean addRequestsForReferencingEntities)
	{
		Stream<IndexRequest> result = Stream.of(createIndexRequestForEntity(entity, entityType, type));
		if (addRequestsForReferencingEntities)
		{
			result = concat(result, createIndexRequestsForReferencingEntities(entity, entityType));
		}
		return result;
	}

	/**
	 * Creates {@link IndexRequest}s for {@link Entity}s that have a reference to a particular entity instance
	 *
	 * @param entity     the entity that is referenced by the entities that need to be updated
	 * @param entityType {@link EntityType} of the referenced entity
	 * @return Stream of {@link IndexRequest}s for the entities that reference entity.
	 */
	private Stream<IndexRequest> createIndexRequestsForReferencingEntities(Entity entity, EntityType entityType)
	{
		Stream<IndexRequest> references = Stream.of();
		// Find entity metadata that is currently, in the database, referring to the entity we're reindexing
		for (Pair<EntityType, List<Attribute>> pair : EntityUtils.getReferencingEntityType(entityType, dataService))
		{
			EntityType refEntityType = pair.getA();

			// Search the index for referring documents of this type
			Stream<Entity> referringEntitiesStream = findReferringDocuments(entity, refEntityType, pair.getB());

			// Get actual entities from the dataservice, skipping the ones that no longer exist and
			// fetching all of their attributes in one go
			referringEntitiesStream = dataService
					.findAll(refEntityType.getId(), referringEntitiesStream.map(Entity::getIdValue),
							createFetchForReindexing(refEntityType));

			references = concat(references, referringEntitiesStream
					.map(referencingEntity -> createIndexRequestForEntity(referencingEntity, refEntityType,
							documentIdGenerator.generateId(refEntityType))));
		}
		return references;
	}

	/**
	 * Searches the index for documents of a certain type that contain a reference to a specific entity.
	 * Uses {@link #searchInternalWithScanScroll(Query, EntityType)} to scroll through the existing referring
	 * entities in a context that remains valid even when the documents are getting updated.
	 *
	 * @param referredEntity      the entity that should be referred to in the documents
	 * @param referringEntityType {@link EntityType} of the referring documents
	 * @param referringAttributes {@link List} of {@link Attribute} of attributes that may reference the #referredEntity
	 * @return Stream of {@link Entity} references representing the documents.
	 */
	private Stream<Entity> findReferringDocuments(Entity referredEntity, EntityType referringEntityType,
			List<Attribute> referringAttributes)
	{
		// Find out which documents of this type currently, in ElasticSearch, contain a reference to
		// the entity we're reindexing
		QueryImpl<Entity> q = null;
		for (Attribute attribute : referringAttributes)
		{
			if (q == null)
			{
				q = new QueryImpl<>();
			}
			else
			{
				q.or();
			}
			q.eq(attribute.getName(), referredEntity);
		}
		LOG.debug("q: [{}], referringEntityType: [{}]", q != null ? q.toString() : "null", referringEntityType.getId());
		if (hasIndex(referringEntityType))
		{
			return searchInternalWithScanScroll(q, referringEntityType);
		}
		else
		{
			return Stream.empty();
		}
	}

	/**
	 * Creates an IndexRequest for an entity.
	 *
	 * @param entity     the entity that will be indexed
	 * @param entityType {@link EntityType} of the entity
	 * @param type       sanitized mapper type of the entity, so it need not be recomputed
	 */
	private IndexRequest createIndexRequestForEntity(Entity entity, EntityType entityType, String type)
	{
		String indexName = getIndexName(entityType);
		String id = toElasticsearchId(entity, entityType);
		XContentBuilder xContentBuilder = elasticsearchEntityFactory.create(entity);
		LOG.trace("Indexing [{}] with id [{}] in index [{}]...", type, id, indexName);
		return new IndexRequest().index(indexName).type(type).id(id).source(xContentBuilder);
	}

	@Override
	public void delete(EntityType entityType, Entity entity)
	{
		deleteById(entityType, entity.getIdValue());
	}

	@Override
	public void deleteById(EntityType entityType, Object entityId)
	{
		String indexName = getIndexName(entityType);
		try
		{
			elasticsearchClientFacade.deleteById(indexName, toElasticsearchId(entityId), indexName);
		}
		catch (IndexNotFoundException e)
		{
			// silently ignore
		}
	}

	@Override
	public void deleteAll(EntityType entityType, Stream<Object> entityIds)
	{
		entityIds.forEach(entityId -> deleteById(entityType, entityId));
	}

	@Override
	public void delete(EntityType entityType, Stream<? extends Entity> entities)
	{
		Stream<Object> entityIds = entities.map(Entity::getIdValue);
		Iterators.partition(entityIds.iterator(), BATCH_SIZE)
				.forEachRemaining(batchEntityIds -> deleteAll(entityType, batchEntityIds.stream()));
	}

	private Stream<Entity> searchInternalWithScanScroll(Query<Entity> query, EntityType entityType)
	{
		Consumer<SearchRequestBuilder> searchRequestBuilderConsumer = searchRequestBuilder -> searchRequestGenerator
				.buildSearchRequest(searchRequestBuilder, SearchType.QUERY_THEN_FETCH, entityType, query, null, null,
						null);

		String indexName = getIndexName(entityType);
		return elasticsearchClientFacade
				.searchForIdsWithScanScroll(searchRequestBuilderConsumer, query.toString(), indexName, indexName)
				.map(idString -> convert(idString, entityType.getIdAttribute()))
				.map(idObject -> elasticsearchEntityFactory.getReference(entityType, idObject));
	}

	private String getIndexName(EntityType entityType)
	{
		return documentIdGenerator.generateId(entityType);
	}
}
