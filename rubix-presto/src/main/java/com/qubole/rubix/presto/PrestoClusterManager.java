/**
 * Copyright (c) 2019. Qubole Inc
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package com.qubole.rubix.presto;

import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.qubole.rubix.common.utils.ClusterUtil;
import com.qubole.rubix.spi.ClusterManager;
import com.qubole.rubix.spi.ClusterType;
import com.qubole.rubix.spi.thrift.ClusterNode;
import com.qubole.rubix.spi.thrift.NodeState;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Created by stagra on 14/1/16.
 */
public class PrestoClusterManager extends ClusterManager
{
  private int serverPort = 8081;
  private String serverAddress = "localhost";
  static LoadingCache<String, List<ClusterNode>> nodesCache;

  private Log log = LogFactory.getLog(PrestoClusterManager.class);

  public static String serverPortConf = "caching.fs.presto-server-port";
  public static String serverAddressConf = "master.hostname";
  public static String yarnServerAddressConf = "yarn.resourcemanager.address";

  // Safe to use single instance of HttpClient since Supplier.get() provides synchronization
  @Override
  public void initialize(Configuration conf)
  {
    super.initialize(conf);
    this.serverPort = conf.getInt(serverPortConf, serverPort);
    this.serverAddress = ClusterUtil.getMasterHostname(conf);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    nodesCache = CacheBuilder.newBuilder()
        .refreshAfterWrite(getNodeRefreshTime(), TimeUnit.SECONDS)
        .build(CacheLoader.asyncReloading(new CacheLoader<String, List<ClusterNode>>()
        {
          @Override
          public List<ClusterNode> load(String s)
              throws Exception
          {
            try {
              URL allNodesRequest = getNodeUrl();
              URL failedNodesRequest = getFailedNodeUrl();

              HttpURLConnection allHttpCon = (HttpURLConnection) allNodesRequest.openConnection();
              allHttpCon.setConnectTimeout(500); //ms
              allHttpCon.setRequestMethod("GET");

              int allNodesResponseCode = allHttpCon.getResponseCode();

              StringBuffer allResponse = new StringBuffer();
              StringBuffer failedResponse = new StringBuffer();
              try {
                if (allNodesResponseCode == HttpURLConnection.HTTP_OK) {
                  BufferedReader in = new BufferedReader(new InputStreamReader(allHttpCon.getInputStream()));
                  String inputLine = "";
                  try {
                    while ((inputLine = in.readLine()) != null) {
                      allResponse.append(inputLine);
                    }
                  }
                  catch (IOException e) {
                    throw new IOException(e);
                  }
                  finally {
                    in.close();
                  }
                }
                else {
                  log.warn(String.format("v1/node failed with code: setting this node as worker "));
                  return ImmutableList.of();
                }
              }
              catch (IOException e) {
                throw new IOException(e);
              }
              finally {
                allHttpCon.disconnect();
              }

              HttpURLConnection failHttpConn = (HttpURLConnection) failedNodesRequest.openConnection();
              failHttpConn.setConnectTimeout(500);    //ms
              failHttpConn.setRequestMethod("GET");
              int failedNodesResponseCode = failHttpConn.getResponseCode();
              // check on failed nodes
              try {
                if (failedNodesResponseCode == HttpURLConnection.HTTP_OK) {
                  BufferedReader in = new BufferedReader(new InputStreamReader(failHttpConn.getInputStream()));
                  String inputLine;
                  try {
                    while ((inputLine = in.readLine()) != null) {
                      failedResponse.append(inputLine);
                    }
                  }
                  catch (IOException e) {
                    throw new IOException(e);
                  }
                  finally {
                    in.close();
                  }
                }
              }
              catch (IOException e) {
                throw new IOException(e);
              }
              finally {
                failHttpConn.disconnect();
              }

              Gson gson = new Gson();
              Type type = new TypeToken<List<Stats>>()
              {
              }.getType();

              List<Stats> allNodes = gson.fromJson(allResponse.toString(), type);
              List<Stats> failedNodes = gson.fromJson(failedResponse.toString(), type);
              if (allNodes.isEmpty()) {
                // Empty result set => server up and only master node running, return localhost has the only node
                // Do not need to consider failed nodes list as 1node cluster and server is up since it replied to allNodesRequest
                return ImmutableList.of(new ClusterNode(InetAddress.getLocalHost().getHostAddress(), NodeState.ACTIVE));
              }

              if (failedNodes.isEmpty()) {
                failedNodes = ImmutableList.of();
              }

              // keep only the healthy nodes
              allNodes.removeAll(failedNodes);

              List<ClusterNode> hosts = new ArrayList<>();

              for (Stats node : allNodes) {
                hosts.add(new ClusterNode(node.getUri().getHost(), NodeState.ACTIVE));
              }
              if (hosts.isEmpty()) {
                // case of master only cluster
                hosts.add(new ClusterNode(InetAddress.getLocalHost().getHostAddress(), NodeState.ACTIVE));
              }

              return hosts;
            }
            catch (IOException e) {
              throw Throwables.propagate(e);
            }
          }
        }, executor));
  }

  /*
   * This returns list of worker nodes when there are worker nodes in the cluster
   * If it is a single node cluster, it will return localhost information
   */
  @Override
  public List<ClusterNode> getNodes()
  {
    try {
      return nodesCache.get("nodeList");
    }
    catch (ExecutionException e) {
      log.warn("Error fetching node list : ", e);
    }
    return null;
  }

  @Override
  public ClusterType getClusterType()
  {
    return ClusterType.PRESTO_CLUSTER_MANAGER;
  }

  public static void setPrestoServerPort(Configuration conf, int port)
  {
    conf.setInt(serverPortConf, port);
  }

  private URL getNodeUrl()
      throws MalformedURLException
  {
    return new URL("http://" + serverAddress + ":" + serverPort + "/v1/node");
  }

  private URL getFailedNodeUrl()
      throws MalformedURLException
  {
    return new URL("http://" + serverAddress + ":" + serverPort + "/v1/node/failed");
  }

  public static class Stats
  {
    URI uri;
    String lastResponseTime;

    public Stats()
    {
    }

    public Stats(URI uri, String lastResponseTime)
    {
      this.uri = uri;
      this.lastResponseTime = lastResponseTime;
    }

    public URI getUri()
    {
      return uri;
    }

    public void setURI(URI uri)
    {
      this.uri = uri;
    }

    String getLastResponseTime()
    {
      return lastResponseTime;
    }

    public void setLastResponseTime(String lastResponseTime)
    {
      this.lastResponseTime = lastResponseTime;
    }

    @Override
    public boolean equals(Object other)
    {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      Stats o = (Stats) other;

      if (!uri.equals(o.getUri())) {
        return false;
      }

      if (lastResponseTime != null && o.getLastResponseTime() != null) {
        return lastResponseTime.equals(o.getLastResponseTime());
      }

      return lastResponseTime == o.getLastResponseTime();
    }

    @Override
    public int hashCode()
    {
      return Objects.hash(uri, lastResponseTime);
    }
  }
}
