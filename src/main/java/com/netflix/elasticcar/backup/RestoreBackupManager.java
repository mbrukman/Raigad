package com.netflix.elasticcar.backup;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.netflix.elasticcar.backup.exception.RestoreBackupException;
import com.netflix.elasticcar.configuration.IConfiguration;
import com.netflix.elasticcar.scheduler.SimpleTimer;
import com.netflix.elasticcar.scheduler.Task;
import com.netflix.elasticcar.scheduler.TaskTimer;
import com.netflix.elasticcar.utils.ESTransportClient;
import com.netflix.elasticcar.utils.ElasticsearchProcessMonitor;
import com.netflix.elasticcar.utils.EsUtils;
import com.netflix.elasticcar.utils.HttpModule;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.rest.RestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by sloke on 7/1/14.
 */
@Singleton
public class RestoreBackupManager extends Task
{
    private static final Logger logger = LoggerFactory.getLogger(RestoreBackupManager.class);
    public static String JOBNAME = "RestoreBackupManager";
    private final AbstractRepository repository;
    private final HttpModule httpModule;
    private static final AtomicBoolean isRestoreRunning = new AtomicBoolean(false);
    private static final String ALL_INDICES_TAG = "_all";
    private static final String SUFFIX_SEPARATOR_TAG = "-";


    @Inject
    public RestoreBackupManager(IConfiguration config,  @Named("s3")AbstractRepository repository, HttpModule httpModule) {
        super(config);
        this.repository = repository;
        this.httpModule = httpModule;
    }

    @Override
    public void execute()
    {
        try {
            //Confirm if Current Node is a Master Node
            if (EsUtils.amIMasterNode(config,httpModule))
            {
                // If Elasticsearch is started then only start Snapshot Backup
                if (!ElasticsearchProcessMonitor.isElasticsearchStarted()) {
                    String exceptionMsg = "Elasticsearch is not yet started, hence not Starting Restore Operation";
                    logger.info(exceptionMsg);
                    return;
                }

                logger.info("Current node is the Master Node. Running Restore now ...");
                runRestore(config.getRestoreRepositoryName(),
                        config.getRestoreRepositoryType(),
                        config.getRestoreSnapshotName(),
                        config.getCommaSeparatedIndicesToRestore());
            }
            else
            {
                logger.info("Current node is not a Master Node yet, hence not running a Restore");
            }
        } catch (Exception e) {
            logger.warn("Exception thrown while running Restore Backup", e);
        }
    }

    public void runRestore(String sourceRepositoryName, String repositoryType, String snapshotName, String indices) throws Exception
    {
        TransportClient esTransportClient = ESTransportClient.instance(config).getTransportClient();

        // Get Repository Name : This will serve as BasePath Suffix
        String sourceRepoName = StringUtils.isBlank(sourceRepositoryName) ? config.getRestoreRepositoryName() : sourceRepositoryName;
        if(StringUtils.isBlank(sourceRepoName))
            throw new RestoreBackupException("Repository Name is Null or Empty");

        //Attach suffix to the repository name so that it does not conflict with Snapshot Repository name
        String restoreRepositoryName = sourceRepoName + SUFFIX_SEPARATOR_TAG + config.getRestoreSourceClusterName();

        String repoType = StringUtils.isBlank(repositoryType) ? config.getRestoreRepositoryType().toLowerCase() : repositoryType;
        if(StringUtils.isBlank(repoType))
        {
            logger.info("RepositoryType is empty, hence Defaulting to <s3> type");
            repoType = AbstractRepository.RepositoryType.s3.name();
        }

        if(!repository.doesRepositoryExists(restoreRepositoryName, AbstractRepository.RepositoryType.valueOf(repoType.toLowerCase())))
        {
            //If repository does not exist, create new one
            repository.createRestoreRepository(restoreRepositoryName,sourceRepoName);
        }

        // Get Snapshot Name
        String snapshotN = StringUtils.isBlank(snapshotName) ? config.getRestoreSnapshotName() : snapshotName;
        if(StringUtils.isBlank(snapshotN))
        {
            //Pick the last Snapshot from the available Snapshots
            List<String> snapshots = EsUtils.getAvailableSnapshots(esTransportClient,restoreRepositoryName);
            if(snapshots.isEmpty())
                throw new RestoreBackupException("No available snapshots in <"+restoreRepositoryName+"> repository.");

            //Sorting Snapshot names in Reverse Order
            Collections.sort(snapshots,Collections.reverseOrder());

            //Use the Last available snapshot
            snapshotN = snapshots.get(0);
        }
        logger.info("Snapshot Name : <"+snapshotN+">");

        // Get Names of Indices
        String commaSeparatedIndices =  StringUtils.isBlank(indices) ? config.getCommaSeparatedIndicesToRestore() : indices;
        if(StringUtils.isBlank(commaSeparatedIndices) || commaSeparatedIndices.equalsIgnoreCase(ALL_INDICES_TAG))
        {
            commaSeparatedIndices = null;
            logger.info("Restoring all Indices.");
        }
        logger.info("Indices param : <"+commaSeparatedIndices+">");

        RestoreSnapshotResponse restoreSnapshotResponse = null;
        if (commaSeparatedIndices != null) {
            //This is a blocking call. It'll wait until Restore is finished.
            restoreSnapshotResponse = esTransportClient.admin().cluster().prepareRestoreSnapshot(restoreRepositoryName, snapshotN)
                    .setWaitForCompletion(true)
                    .setIndices(commaSeparatedIndices)   //"test-idx-*", "-test-idx-2"
                    .execute()
                    .actionGet();
        }else{
            // Not Setting Indices explicitly -- Seems to be a bug in Elasticsearch
            restoreSnapshotResponse = esTransportClient.admin().cluster().prepareRestoreSnapshot(restoreRepositoryName, snapshotN)
                    .setWaitForCompletion(true)
                    .execute()
                    .actionGet();
        }

        logger.info("Restore Status = "+restoreSnapshotResponse.status().toString());
        if(restoreSnapshotResponse.status() == RestStatus.OK)
        {
            printRestoreDetails(restoreSnapshotResponse);
        }
        else if (restoreSnapshotResponse.status() == RestStatus.INTERNAL_SERVER_ERROR)
            logger.info("Snapshot Completely Failed");

    }

    //TODO: Map to Java Class and Create JSON
    public void printRestoreDetails(RestoreSnapshotResponse restoreSnapshotResponse)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("Restore Details:");
        builder.append("\n\t Name = "+restoreSnapshotResponse.getRestoreInfo().name());
        builder.append("\n\t Indices : ");
        for(String index:restoreSnapshotResponse.getRestoreInfo().indices())
        {
            builder.append("\n\t\t Index = "+index);
        }
        builder.append("\n\t Total Shards = "+restoreSnapshotResponse.getRestoreInfo().totalShards());
        builder.append("\n\t Successful Shards = "+restoreSnapshotResponse.getRestoreInfo().successfulShards());
        builder.append("\n\t Total Failed Shards = "+restoreSnapshotResponse.getRestoreInfo().failedShards());

        logger.info(builder.toString());
    }

    public static TaskTimer getTimer(IConfiguration config)
    {
        return new SimpleTimer(JOBNAME);
    }

    @Override
    public String getName()
    {
        return JOBNAME;
    }

}
