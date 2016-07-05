package org.molgenis.data.elasticsearch.util;

import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BulkProcessorFactory
{
	private static final Logger LOG = LoggerFactory.getLogger(BulkProcessorFactory.class);

	public BulkProcessor create(Client client)
	{
		return BulkProcessor.builder(client, new BulkProcessor.Listener()
		{
			@Override
			public void beforeBulk(long executionId, BulkRequest request)
			{
				LOG.trace("Going to execute new bulk composed of {} actions", request.numberOfActions());
			}

			@Override
			public void afterBulk(long executionId, BulkRequest request, BulkResponse response)
			{
				LOG.trace("Executed bulk composed of {} actions", request.numberOfActions());
			}

			@Override
			public void afterBulk(long executionId, BulkRequest request, Throwable failure)
			{
				LOG.warn("Error executing bulk", failure);
			}
		}).build();
	}
}