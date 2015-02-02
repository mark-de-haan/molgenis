package org.molgenis.data.repository.impl;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.molgenis.data.CrudRepository;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.mapping.model.AttributeMapping;
import org.molgenis.data.mapping.model.EntityMapping;
import org.molgenis.data.meta.EntityMappingMetaData;
import org.molgenis.data.meta.MetaDataService;
import org.molgenis.data.repository.AttributeMappingRepository;
import org.molgenis.data.repository.EntityMappingRepository;
import org.molgenis.data.support.MapEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.IdGenerator;

import com.google.common.collect.Lists;

/**
 * O/R mapping between EntityMapping Entity and EntityMapping POJO
 */
public class EntityMappingRepositoryImpl implements EntityMappingRepository
{
	public static final EntityMetaData META_DATA = new EntityMappingMetaData();

	@Autowired
	private MetaDataService metaDataService;

	@Autowired
	private IdGenerator idGenerator;

	private AttributeMappingRepository attributeMappingRepository;

	private final CrudRepository repository;

	public EntityMappingRepositoryImpl(CrudRepository repository, AttributeMappingRepository attributeMappingRepository)
	{
		this.repository = repository;
		this.attributeMappingRepository = attributeMappingRepository;
	}

	@Override
	public List<EntityMapping> toEntityMappings(List<Entity> entityMappingEntities)
	{
		return Lists.transform(entityMappingEntities, this::toEntityMapping);
	}

	private EntityMapping toEntityMapping(Entity entityMappingEntity)
	{
		String identifier = entityMappingEntity.getString(EntityMappingMetaData.IDENTIFIER);
		EntityMetaData targetEntityMetaData = metaDataService.getEntityMetaData(entityMappingEntity
				.getString(EntityMappingMetaData.TARGETENTITYMETADATA));
		EntityMetaData sourceEntityMetaData = metaDataService.getEntityMetaData(entityMappingEntity
				.getString(EntityMappingMetaData.SOURCEENTITYMETADATA));
		List<Entity> attributeMappingEntities = Lists.<Entity> newArrayList(entityMappingEntity
				.getEntities(EntityMappingMetaData.ATTRIBUTEMAPPINGS));
		List<AttributeMapping> attributeMappings = attributeMappingRepository.getAttributeMappings(
				attributeMappingEntities, sourceEntityMetaData, targetEntityMetaData);

		return new EntityMapping(identifier, sourceEntityMetaData, targetEntityMetaData, attributeMappings);
	}

	@Override
	public List<Entity> upsert(Collection<EntityMapping> entityMappings)
	{
		return entityMappings.stream().map(this::upsert).collect(Collectors.toList());
	}

	private Entity upsert(EntityMapping entityMapping)
	{
		List<Entity> attributeMappingEntities = attributeMappingRepository.upsert(entityMapping.getAttributeMappings());
		Entity entityMappingEntity;
		if (entityMapping.getIdentifier() == null)
		{
			entityMapping.setIdentifier(idGenerator.generateId().toString());
			entityMappingEntity = toEntityMappingEntity(entityMapping, attributeMappingEntities);
			repository.add(entityMappingEntity);
		}
		else
		{
			entityMappingEntity = toEntityMappingEntity(entityMapping, attributeMappingEntities);
			repository.update(entityMappingEntity);
		}
		return entityMappingEntity;
	}

	private Entity toEntityMappingEntity(EntityMapping entityMapping, List<Entity> attributeMappingEntities)
	{
		Entity entityMappingEntity = new MapEntity(META_DATA);
		entityMappingEntity.set(EntityMappingMetaData.IDENTIFIER, entityMapping.getIdentifier());
		entityMappingEntity
				.set(EntityMappingMetaData.SOURCEENTITYMETADATA,
						entityMapping.getSourceEntityMetaData() != null ? entityMapping.getSourceEntityMetaData()
								.getName() : null);
		entityMappingEntity
				.set(EntityMappingMetaData.TARGETENTITYMETADATA,
						entityMapping.getTargetEntityMetaData() != null ? entityMapping.getTargetEntityMetaData()
								.getName() : null);
		entityMappingEntity.set(EntityMappingMetaData.ATTRIBUTEMAPPINGS, attributeMappingEntities);
		return entityMappingEntity;
	}
}