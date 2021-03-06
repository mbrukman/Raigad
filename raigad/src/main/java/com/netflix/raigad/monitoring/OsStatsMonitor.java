/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.raigad.monitoring;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.raigad.configuration.IConfiguration;
import com.netflix.raigad.scheduler.SimpleTimer;
import com.netflix.raigad.scheduler.Task;
import com.netflix.raigad.scheduler.TaskTimer;
import com.netflix.raigad.utils.ESTransportClient;
import com.netflix.raigad.utils.ElasticsearchProcessMonitor;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.monitor.os.OsStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

@Singleton
public class OsStatsMonitor extends Task
{
	private static final Logger logger = LoggerFactory.getLogger(OsStatsMonitor.class);
    public static final String METRIC_NAME = "Elasticsearch_OsStatsMonitor";
    private final Elasticsearch_OsStatsReporter osStatsReporter;

    @Inject
    public OsStatsMonitor(IConfiguration config)
    {
        super(config);
        osStatsReporter = new Elasticsearch_OsStatsReporter();
    	Monitors.registerObject(osStatsReporter);
    }

  	@Override
	public void execute() throws Exception {

		// If Elasticsearch is started then only start the monitoring
		if (!ElasticsearchProcessMonitor.isElasticsearchStarted()) {
			String exceptionMsg = "Elasticsearch is not yet started, check back again later";
			logger.info(exceptionMsg);
			return;
		}

  		OsStatsBean osStatsBean = new OsStatsBean();
  		try
  		{
  			NodesStatsResponse ndsStatsResponse = ESTransportClient.getNodesStatsResponse(config);
  			OsStats osStats = null;
  			NodeStats ndStat = null;
  			if (ndsStatsResponse.getNodes().length > 0) {
  				ndStat = ndsStatsResponse.getAt(0);
            }
			if (ndStat == null) {
				logger.info("NodeStats is null,hence returning (No OsStats).");
                resetOsStats(osStatsBean);
				return;
			}
			osStats = ndStat.getOs();
			if (osStats == null) {
				logger.info("OsStats is null,hence returning (No OsStats).");
                resetOsStats(osStatsBean);
				return;
			}

            //Mem
			osStatsBean.freeInBytes = osStats.getMem().getFree().getBytes();
			osStatsBean.usedInBytes = osStats.getMem().getUsed().getBytes();
			osStatsBean.actualFreeInBytes = osStats.getMem().getActualFree().getBytes();
            osStatsBean.actualUsedInBytes = osStats.getMem().getActualUsed().getBytes();
            osStatsBean.freePercent = osStats.getMem().getFreePercent();
            osStatsBean.usedPercent = osStats.getMem().getUsedPercent();
            //CPU
			osStatsBean.cpuSys = osStats.getCpu().getSys();
			osStatsBean.cpuUser = osStats.getCpu().getUser();
			osStatsBean.cpuIdle = osStats.getCpu().getIdle();
			osStatsBean.cpuStolen = osStats.getCpu().getStolen();
            //Swap
			osStatsBean.swapFreeInBytes = osStats.getSwap().getFree().getBytes();
            osStatsBean.swapUsedInBytes = osStats.getSwap().getUsed().getBytes();
            //Uptime
            osStatsBean.uptimeInMillis = osStats.getUptime().getMillis();
            //Load Average ??
            //Timestamp
			osStatsBean.osTimestamp = osStats.getTimestamp();
  		}
  		catch(Exception e)
  		{
            resetOsStats(osStatsBean);
  			logger.warn("failed to load Os stats data", e);
  		}

  		osStatsReporter.osStatsBean.set(osStatsBean);
	}

    public class Elasticsearch_OsStatsReporter
    {
        private final AtomicReference<OsStatsBean> osStatsBean;

        public Elasticsearch_OsStatsReporter()
        {
        		osStatsBean = new AtomicReference<OsStatsBean>(new OsStatsBean());
        }
        
        @Monitor(name ="free_in_bytes", type=DataSourceType.GAUGE)
        public long getFreeInBytes()
        {
            return osStatsBean.get().freeInBytes;
        }
        
        @Monitor(name ="used_in_bytes", type=DataSourceType.GAUGE)
        public long getUsedInBytes()
        {
            return osStatsBean.get().usedInBytes;
        }
        @Monitor(name ="actual_free_in_bytes", type=DataSourceType.GAUGE)
        public long getActualFreeInBytes()
        {
            return osStatsBean.get().actualFreeInBytes;
        }
        @Monitor(name ="actual_used_in_bytes", type=DataSourceType.GAUGE)
        public long geActualUsedInBytes()
        {
            return osStatsBean.get().actualUsedInBytes;
        }
        @Monitor(name ="free_percent", type=DataSourceType.GAUGE)
        public short getFreePercent()
        {
            return osStatsBean.get().freePercent;
        }
        @Monitor(name ="used_percent", type=DataSourceType.GAUGE)
        public short getUsedPercent()
        {
            return osStatsBean.get().usedPercent;
        }
        @Monitor(name ="cpu_sys", type=DataSourceType.GAUGE)
        public short getCpuSys()
        {
            return osStatsBean.get().cpuSys;
        }
        @Monitor(name ="cpu_user", type=DataSourceType.GAUGE)
        public short getCpuUser()
        {
            return osStatsBean.get().cpuUser;
        }
        @Monitor(name ="cpu_idle", type=DataSourceType.GAUGE)
        public short getCpuIdle()
        {
            return osStatsBean.get().cpuIdle;
        }
        @Monitor(name ="cpu_stolen", type=DataSourceType.GAUGE)
        public short getCpuStolen()
        {
            return osStatsBean.get().cpuStolen;
        }
        @Monitor(name ="swap_used_in_bytes", type=DataSourceType.GAUGE)
        public long getSwapUsedInBytes()
        {
            return osStatsBean.get().swapUsedInBytes;
        }
        @Monitor(name ="swap_free_in_bytes", type=DataSourceType.GAUGE)
        public long getSwapFreeInBytes()
        {
            return osStatsBean.get().swapFreeInBytes;
        }
        @Monitor(name ="uptime_in_millis", type=DataSourceType.GAUGE)
        public double getUptimeInMillis()
        {
            return osStatsBean.get().uptimeInMillis;
        }
        @Monitor(name ="os_timestamp", type=DataSourceType.GAUGE)
        public long getOsTimestamp()
        {
            return osStatsBean.get().osTimestamp;
        }
    }
    
    private static class OsStatsBean
    {
    	  private long freeInBytes = -1;
    	  private long usedInBytes = -1;
    	  private long actualFreeInBytes = -1;
          private long actualUsedInBytes = -1;
          private short freePercent = -1;
          private short usedPercent = -1;
          private short cpuSys = -1;
    	  private short cpuUser = -1;
          private short cpuIdle = -1;
          private short cpuStolen = -1;
          private long swapUsedInBytes = -1;
          private long swapFreeInBytes = -1;
          private long uptimeInMillis = -1;
          private long osTimestamp = -1;
    }

	public static TaskTimer getTimer(String name)
	{
		return new SimpleTimer(name, 60 * 1000);
	}

	@Override
	public String getName()
	{
		return METRIC_NAME;
	}

    private void resetOsStats(OsStatsBean osStatsBean){
        osStatsBean.freeInBytes = -1;
        osStatsBean.usedInBytes = -1;
        osStatsBean.actualFreeInBytes = -1;
        osStatsBean.actualUsedInBytes = -1;
        osStatsBean.freePercent = -1;
        osStatsBean.usedPercent = -1;
        osStatsBean.cpuSys = -1;
        osStatsBean.cpuUser = -1;
        osStatsBean.cpuIdle = -1;
        osStatsBean.cpuStolen = -1;
        osStatsBean.swapUsedInBytes = -1;
        osStatsBean.swapFreeInBytes = -1;
        osStatsBean.uptimeInMillis = -1;
        osStatsBean.osTimestamp = -1;
    }
}
